package com.carnet.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Rational
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Foreground service of type camera|microphone that owns the entire CameraX lifecycle:
 * Preview, VideoCapture, the OverlayEffect (HUD + optional screen-capture PiP),
 * and the active recording. The activity becomes a thin UI shell that binds here.
 *
 * Why this lives in a service and not the activity: starting MediaProjection in the
 * same process as a camera-using activity makes Android's CameraService disconnect
 * the camera client for ~6 s while it re-evaluates resource ownership. With the
 * camera already FGS-covered by this service of type `camera`, ownership is settled,
 * and MediaProjection's separate FGS doesn't trigger a re-handshake — letting the
 * user arm screen capture mid-recording without freezing the file.
 */
class CameraCaptureService : LifecycleService() {

    sealed class RecordingState {
        object Idle : RecordingState()
        object Recording : RecordingState()
    }

    private val binder = LocalBinder()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentPreview: Preview? = null
    private var overlayEffect: OverlayEffect? = null
    private var overlayThread: HandlerThread? = null
    private var activeRecording: Recording? = null
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private lateinit var hudPainter: HudPainter
    private lateinit var introPainter: IntroSlidePainter
    private lateinit var biosClient: BiosClient
    private lateinit var sidecarWriter: SidecarWriter
    private lateinit var biosCompanionWriter: BiosCompanionWriter
    private var firstFrameLogged = false
    @Volatile private var hudRotation: Int = 0
    private var overlayAspectListener: ((Float) -> Unit)? = null
    private var lastSurfaceProvider: Preview.SurfaceProvider? = null
    @Volatile private var currentBoundRotation: Int = Surface.ROTATION_0
    @Volatile private var introState: IntroState? = null

    /** Slide deck snapshotted at startRecording. Cleared at finalize. */
    private data class IntroState(
        val slides: List<IntroSlide>,
        val seriesId: String,
        val seriesName: String,
        val snapshot: BiosSnapshot,
        val capturedAtMillis: Long,
        val startElapsedMs: Long,
    ) {
        /** Slide index for the given elapsedRealtime, or null once the deck is done. */
        fun currentSlideIndex(nowElapsed: Long): Int? {
            val elapsed = nowElapsed - startElapsedMs
            if (elapsed < 0) return null
            val idx = (elapsed / SLIDE_DURATION_MS).toInt()
            return if (idx in slides.indices) idx else null
        }
    }

    inner class LocalBinder : Binder() {
        // Getter, not a field initializer — `binder` is declared before `_state` in the
        // service so `_state.asStateFlow()` would be null at LocalBinder construction.
        val state: StateFlow<RecordingState> get() = _state.asStateFlow()

        fun bindCamera(surfaceProvider: Preview.SurfaceProvider, displayRotation: Int) =
            this@CameraCaptureService.bindCamera(surfaceProvider, displayRotation)

        /**
         * Re-issue a SurfaceRequest on the existing Preview without tearing down the
         * camera pipeline. Used when the activity returns to foreground *during* a
         * recording — full rebind would close the Recorder's DeferrableSurface and
         * orphan the take (`SurfaceProcessorNode: Downstream node failed to provide
         * Surface. Target: VIDEO_CAPTURE`). Returns false when there is no Preview to
         * refresh; the caller should then fall back to bindCamera.
         */
        fun refreshPreviewSurface(surfaceProvider: Preview.SurfaceProvider): Boolean {
            val preview = currentPreview ?: return false
            lastSurfaceProvider = surfaceProvider
            preview.setSurfaceProvider(ContextCompat.getMainExecutor(this@CameraCaptureService), surfaceProvider)
            return true
        }

        fun setHudConfig(config: SessionConfig) {
            hudPainter.subject = config.subject
            hudPainter.session = config.sessionLabel
            hudPainter.experiment = config.experimentLabel
        }

        fun setHudRotation(degrees: Int) {
            hudRotation = degrees
        }

        fun setOverlayAspectListener(listener: ((Float) -> Unit)?) {
            overlayAspectListener = listener
        }

        fun startRecording(config: SessionConfig) =
            this@CameraCaptureService.startRecording(config)

        fun stopRecording() {
            activeRecording?.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        hudPainter = HudPainter(this)
        introPainter = IntroSlidePainter(this)
        biosClient = BiosClient(this)
        sidecarWriter = SidecarWriter(this)
        biosCompanionWriter = BiosCompanionWriter(this)
        CadenceReminder.ensureChannel(this)
        ensureChannel()
        startForegroundCompat()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Service is started + bound; activity owns the lifecycle and calls stopService
        // on finish. Don't auto-restart if killed — without the activity there's no UI.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeRecording?.close()
        activeRecording = null
        runCatching {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        }
        overlayEffect?.close()
        overlayEffect = null
        overlayThread?.quitSafely()
        overlayThread = null
        super.onDestroy()
    }

    private fun bindCamera(surfaceProvider: Preview.SurfaceProvider, displayRotation: Int) {
        lastSurfaceProvider = surfaceProvider
        bindInternal(displayRotation, onBound = null)
    }

    private fun bindInternal(displayRotation: Int, onBound: (() -> Unit)?) {
        val surfaceProvider = lastSurfaceProvider ?: return
        currentBoundRotation = displayRotation
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            // Release the prior binding + OverlayEffect before allocating new ones.
            // Skipping this leaks the old effect: bindToLifecycle below would detach it
            // from the camera (CameraUseCaseAdapter logs "Unused effects: …"), but its
            // EGLContext stays alive on Carnet-OverlayGL and crashes on its next frame
            // with "checkAndUpdateEglState: invalid current EGLContext" →
            // nativeUpdateTexImage. Fires on every rebind — rotation-triggered take
            // rebinds and activity restarts both hit it.
            provider.unbindAll()
            overlayEffect?.close()
            overlayEffect = null

            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(surfaceProvider)
            }
            currentPreview = previewUseCase
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .build()
            // Front-camera VideoCapture defaults to recording the un-mirrored ("true") image
            // while PreviewView mirrors for the selfie view — that asymmetry left the HUD
            // looking right in the preview and horizontally flipped in the encoded file.
            // Force mirroring on the front camera so both consumers see the same pipeline.
            val newVideoCapture = VideoCapture.Builder(recorder)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                .build()
            videoCapture = newVideoCapture

            if (overlayThread == null) {
                overlayThread = HandlerThread("Carnet-OverlayGL").apply { start() }
            }
            val effect = OverlayEffect(
                CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                0,
                Handler(overlayThread!!.looper),
            ) { error -> Log.e(TAG, "overlay effect error", error) }
            effect.setOnDrawListener { frame ->
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                val rotation = frame.rotationDegrees
                val mirror = frame.isMirroring
                val crop = frame.cropRect
                val cropW = crop.width()
                val cropH = crop.height()
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    Log.i(
                        TAG,
                        "first overlay frame: size=${frame.size} rotation=$rotation " +
                            "mirror=$mirror crop=$crop",
                    )
                    val aspect = if (rotation == 90 || rotation == 270) {
                        cropH.toFloat() / cropW.toFloat()
                    } else {
                        cropW.toFloat() / cropH.toFloat()
                    }
                    overlayAspectListener?.invoke(aspect)
                }
                // Pipeline rotates the cropRect (not the full buffer) CW by rotation then
                // mirrors in display orientation. Anchoring to cropRect makes the HUD land at
                // the corners both Preview and VideoCapture actually consume, so it ends up
                // burned into the encoded video, not just the preview.
                val displayW: Int
                val displayH: Int
                if (rotation == 90 || rotation == 270) {
                    displayW = cropH
                    displayH = cropW
                } else {
                    displayW = cropW
                    displayH = cropH
                }
                val deviceRot = hudRotation
                val hudW: Int
                val hudH: Int
                if (deviceRot == 90 || deviceRot == 270) {
                    hudW = displayH
                    hudH = displayW
                } else {
                    hudW = displayW
                    hudH = displayH
                }
                canvas.save()
                canvas.translate(crop.exactCenterX(), crop.exactCenterY())
                canvas.rotate(-rotation.toFloat())
                if (mirror) canvas.scale(-1f, 1f)
                canvas.translate(-displayW / 2f, -displayH / 2f)
                canvas.translate(displayW / 2f, displayH / 2f)
                canvas.rotate(-deviceRot.toFloat())
                canvas.translate(-hudW / 2f, -hudH / 2f)

                // Background layer: intro slide (opaque), or screen-capture full-frame
                // (opaque when armed), or — falling through — the camera frame underneath.
                // The HUD frame + corner infos draw last on every branch so the recording
                // is always identifiable: UID, timestamps, and the corner brackets stay
                // visible across the data slides and the cast-app footage too.
                val intro = introState
                val slideIdx = intro?.currentSlideIndex(SystemClock.elapsedRealtime())
                if (intro != null && slideIdx != null) {
                    introPainter.draw(
                        canvas, hudW, hudH,
                        intro.slides[slideIdx],
                        intro.seriesName,
                        intro.capturedAtMillis,
                    )
                } else {
                    drawScreenFullFrameIfArmed(canvas, hudW, hudH)
                }
                hudPainter.draw(canvas, hudW, hudH)
                canvas.restore()
                true
            }
            overlayEffect = effect

            // ViewPort forces Preview, VideoCapture and the OverlayEffect to share a single
            // crop rectangle. Without it, VideoCapture quietly crops the buffer to its target
            // aspect inside the encoder while Preview shows the full buffer — the HUD ends up
            // anchored to corners that exist in preview but not in the encoded file.
            //
            // FILL_CENTER (not FIT) so the encoded video is true 9:16: the Pixel front sensor
            // is 4:3, and FIT preserved that ratio in the file (1080×1440), which Reels and
            // other vertical-video platforms reject as non-portrait. FILL_CENTER center-crops
            // the 4:3 buffer to 9:16, producing a 1080×1920 file that uploads cleanly.
            val viewPort = ViewPort.Builder(Rational(9, 16), displayRotation)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(previewUseCase)
                .addUseCase(newVideoCapture)
                .addEffect(effect)
                .build()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, group)
            Log.i(TAG, "bound camera at displayRotation=$displayRotation")
            onBound?.invoke()
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission", "RestrictedApi")
    private fun startRecording(config: SessionConfig) {
        if (activeRecording != null) return

        // Match the file's container orientation to how the phone is held NOW. Setting
        // VideoCapture.targetRotation alone is a no-op here because UseCaseGroup's ViewPort
        // pins the rotation of every bound use case; the only way to actually change the
        // encoded rotation is to rebuild the ViewPort with the new rotation and rebind.
        // We do this only at startRecording (one brief preview re-handshake per take, not
        // on every phone rotation) so idle preview stays stable.
        val targetRotation = when (hudRotation) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
        if (targetRotation != currentBoundRotation && lastSurfaceProvider != null) {
            Log.i(TAG, "rebinding for take: $currentBoundRotation → $targetRotation")
            bindInternal(targetRotation) { startRecordingNow(config) }
        } else {
            startRecordingNow(config)
        }
    }

    @SuppressLint("MissingPermission", "RestrictedApi")
    private fun startRecordingNow(config: SessionConfig) {
        val capture = videoCapture ?: return
        if (activeRecording != null) return

        val today = DATE_FORMAT.format(Date())
        val uid = UUID.randomUUID().toString().take(8).uppercase()
        val sessionNumber = nextSessionNumber()
        val sessionTag = "V${sessionNumber.toString().padStart(2, '0')}_${config.sessionLabel}"
        val displayName = "${sessionTag}_${today}_$uid"

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, CARNET_RELATIVE_PATH)
        }
        val output = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        hudPainter.uid = uid

        // Snapshot Bios metrics AT record-start (manifesto: vitals reflect the moment the
        // session began, not when the file was finalised). If a series is selected we pull
        // exactly the metrics that series asks for and pre-build the intro slide deck;
        // without a series the snapshot is empty and recording starts normally with no
        // intro. Either way the snapshot is best-effort — null values render as "--".
        val recordStartMillis = System.currentTimeMillis()
        val series = SeriesPreferences.selected(this)
        val specs = series?.specs() ?: emptyList()
        val biosSnapshot = if (specs.isNotEmpty()) {
            biosClient.snapshot(specs, recordStartMillis)
        } else {
            BiosSnapshot(values = emptyMap(), capturedAtMillis = recordStartMillis)
        }
        introState = if (series != null) {
            // Walk every prior sidecar for this series so each metric slide can anchor on
            // the first-ever value and draw the trajectory. Sync read on the main thread;
            // typical series sizes are tiny and we need the data ready before the first
            // encoded frame so the intro deck is whole when playback starts.
            val past = SeriesHistory.loadForSeries(this, series.id)
            // Metadata slide leads the deck so the take is self-identifying even if the
            // file is shared without its filename. Renders unconditionally when a series
            // is active, even if the series has no metrics pinned yet.
            val metadataSlide = IntroSlide.Metadata(
                seriesName = series.name,
                subject = config.subject,
                sessionLabel = config.sessionLabel,
                sessionTag = sessionTag,
                experimentLabel = config.experimentLabel,
            )
            val metricSlides = specs.map { spec ->
                val currentRaw = biosSnapshot.values[spec.key]
                val pastValues = past.mapNotNull { it.values[spec.key] }
                val startRaw = pastValues.firstOrNull()
                val history = pastValues + listOfNotNull(currentRaw)
                val deltaText = if (startRaw != null && currentRaw != null) {
                    val delta = currentRaw - startRaw
                    val sign = if (delta >= 0) "+" else "-"
                    val magnitude = spec.formatValue(kotlin.math.abs(delta))
                    "$sign$magnitude vs ${spec.formatValue(startRaw)} at start"
                } else null
                IntroSlide.Metric(
                    label = spec.label,
                    value = spec.formatValue(currentRaw),
                    unit = spec.unit,
                    startValue = startRaw?.let(spec::formatValue),
                    deltaText = deltaText,
                    history = history,
                )
            }
            IntroState(
                slides = listOf<IntroSlide>(metadataSlide) + metricSlides,
                seriesId = series.id,
                seriesName = series.name,
                snapshot = biosSnapshot,
                capturedAtMillis = recordStartMillis,
                startElapsedMs = SystemClock.elapsedRealtime(),
            )
        } else null

        // asPersistentRecording is a defensive belt-and-braces: with camera ownership
        // FGS-covered by this service, MediaProjection should no longer kick the camera,
        // but if some other transient unbinds the VideoCapture the recording survives.
        activeRecording = capture.output
            .prepareRecording(this, output)
            .withAudioEnabled()
            .asPersistentRecording()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        hudPainter.recording = true
                        _state.value = RecordingState.Recording
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(
                                TAG,
                                "recording finalized with error code=${event.error} " +
                                    "cause=${event.cause?.javaClass?.simpleName}: ${event.cause?.message}",
                                event.cause,
                            )
                        }
                        hudPainter.recording = false
                        hudPainter.uid = "--------"
                        activeRecording = null
                        introState = null
                        _state.value = RecordingState.Idle
                        if (!event.hasError()) {
                            writeSidecar(
                                baseName = displayName,
                                subject = config.subject,
                                sessionLabel = config.sessionLabel,
                                experimentLabel = config.experimentLabel,
                                sessionNumber = sessionNumber,
                                uid = uid,
                                dateLocal = today,
                                recordStartMillis = recordStartMillis,
                                biosSnapshot = biosSnapshot,
                                series = series,
                            )
                            CadenceReminder.scheduleNext(this)
                        }
                    }
                }
            }
    }

    private fun writeSidecar(
        baseName: String,
        subject: String,
        sessionLabel: String,
        experimentLabel: String,
        sessionNumber: Int,
        uid: String,
        dateLocal: String,
        recordStartMillis: Long,
        biosSnapshot: BiosSnapshot?,
        series: Series?,
    ) {
        val metadata = SidecarMetadata(
            schemaVersion = SidecarWriter.SCHEMA_VERSION,
            filename = "$baseName.mp4",
            subject = subject,
            sessionLabel = sessionLabel,
            experimentLabel = experimentLabel,
            sessionNumber = sessionNumber,
            uid = uid,
            dateLocal = dateLocal,
            recordStartMillis = recordStartMillis,
            recordEndMillis = System.currentTimeMillis(),
            biosSnapshot = biosSnapshot,
            seriesId = series?.id,
            seriesName = series?.name,
        )
        sidecarWriter.write(baseName, CARNET_RELATIVE_PATH, metadata)
        // Respect the Settings toggle — opt-out users get a local-only file with no
        // Bios round-trip. Sidecar JSON is always written either way.
        if (BiosIntegrationPreferences.isSendEventsEnabled(this)) {
            biosCompanionWriter.postRecordingCompleted(metadata)
        }
    }

    /** Scan Movies/Carnet/ for existing V## files and return next free number. */
    private fun nextSessionNumber(): Int {
        var max = 0
        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(CARNET_RELATIVE_PATH)
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val match = VERSION_PREFIX.matchEntire(cursor.getString(nameIdx).substringBefore('_'))
                val n = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                if (n > max) max = n
            }
        }
        return max + 1
    }

    private fun drawScreenFullFrameIfArmed(canvas: Canvas, hudW: Int, hudH: Int) {
        val bmp = ScreenCaptureService.activeSource?.latestBitmap ?: return
        if (bmp.isRecycled) return
        // Opaque background paints over the camera frame underneath; any
        // letterbox left by aspect-mismatch then reads as black bars rather than
        // leaked face.
        canvas.drawColor(Color.BLACK)
        val canvasAspect = hudW.toFloat() / hudH.toFloat()
        val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val drawW: Float
        val drawH: Float
        if (canvasAspect > bmpAspect) {
            drawH = hudH.toFloat()
            drawW = drawH * bmpAspect
        } else {
            drawW = hudW.toFloat()
            drawH = drawW / bmpAspect
        }
        val left = (hudW - drawW) / 2f
        val top = (hudH - drawH) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + drawW, top + drawH), null)
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.camera_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun startForegroundCompat() {
        // Route notification taps back into the existing MainActivity (paired with
        // android:launchMode="singleTask" in the manifest). Without a content intent,
        // some launchers fell back to the default launcher intent and spawned a fresh
        // MainActivity, which rebound the camera mid-take.
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_config)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.camera_service_active))
            .setContentIntent(contentPending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "Carnet"
        private const val CHANNEL_ID = "carnet_camera"
        private const val NOTIF_ID = 4710
        private const val CARNET_RELATIVE_PATH = "Movies/Carnet/"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val VERSION_PREFIX = Regex("^V(\\d+)$")
        /** How long each intro slide stays on screen (and in the encoded file). */
        private const val SLIDE_DURATION_MS = 1500L
    }
}
