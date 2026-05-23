package com.carnet.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.carnet.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Carnet entry point. v0.1 scaffold complete: CameraX preview + permissions, HUD burned in
 * via OverlayEffect, VideoCapture wired to record button, session config persisted, Bios
 * snapshot + sidecar JSON written on stop, recording_session_completed event posted back to
 * the Bios companion.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hudPainter: HudPainter
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var overlayEffect: OverlayEffect? = null
    private var overlayThread: HandlerThread? = null
    private var firstFrameLogged = false
    private var orientationListener: OrientationEventListener? = null
    @Volatile private var hudRotation: Int = 0
    private lateinit var sessionConfig: SessionConfig
    private lateinit var biosClient: BiosClient
    private lateinit var sidecarWriter: SidecarWriter
    private lateinit var biosCompanionWriter: BiosCompanionWriter

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) bindCamera() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionConfig = SessionConfig.load(this)
        biosClient = BiosClient(this)
        sidecarWriter = SidecarWriter(this)
        biosCompanionWriter = BiosCompanionWriter(this)
        hudPainter = HudPainter(this).apply { subject = sessionConfig.subject }
        binding.recordButton.setOnClickListener { onRecordButtonClick() }
        binding.configButton.setOnClickListener { showConfigDialog() }
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                // Quantize to the nearest 90° step. Drives only the HUD's in-canvas rotation —
                // we deliberately don't touch preview/videoCapture targetRotation because the
                // activity is portrait-locked and re-rotating the camera buffer into a portrait
                // FIT_CENTER box just squishes the image.
                hudRotation = ((degrees + 45) / 90 % 4) * 90
            }
        }

        if (hasAllPermissions()) {
            bindCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
    }

    override fun onPause() {
        orientationListener?.disable()
        super.onPause()
    }

    override fun onDestroy() {
        overlayEffect?.close()
        overlayEffect = null
        overlayThread?.quitSafely()
        overlayThread = null
        super.onDestroy()
    }

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun bindCamera() {
        binding.permissionMessage.visibility = View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUseCase = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            val glThread = HandlerThread("Carnet-OverlayGL").apply { start() }
            overlayThread = glThread
            val effect = OverlayEffect(
                CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                0,
                Handler(glThread.looper),
            ) { error -> Log.e(TAG, "overlay effect error", error) }
            effect.setOnDrawListener { frame ->
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    Log.i(
                        TAG,
                        "first overlay frame: size=${frame.size} rotation=${frame.rotationDegrees} " +
                            "mirror=${frame.isMirroring} crop=${frame.cropRect}",
                    )
                }
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                val bufferW = frame.size.width
                val bufferH = frame.size.height
                val rotation = frame.rotationDegrees
                val mirror = frame.isMirroring
                // Pipeline maps buffer(x,y) -> display(displayW-y, displayH-x) for the front
                // camera (rotation 270 CW then horizontal mirror in display). Invert it on the
                // canvas so HudPainter can draw in display coordinates and the HUD lands at the
                // display corners after the pipeline.
                val displayW: Int
                val displayH: Int
                if (rotation == 90 || rotation == 270) {
                    displayW = bufferH
                    displayH = bufferW
                } else {
                    displayW = bufferW
                    displayH = bufferH
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
                canvas.translate(bufferW / 2f, bufferH / 2f)
                canvas.rotate(-rotation.toFloat())
                if (mirror) canvas.scale(-1f, 1f)
                canvas.translate(-displayW / 2f, -displayH / 2f)
                // Additional pivot around the display center so the HUD reads upright in the
                // viewer's current physical orientation. Display dims fed to HudPainter swap
                // when deviceRot is 90/270 so corners still land at the rotated screen corners.
                canvas.translate(displayW / 2f, displayH / 2f)
                canvas.rotate(-deviceRot.toFloat())
                canvas.translate(-hudW / 2f, -hudH / 2f)
                hudPainter.draw(canvas, hudW, hudH)
                canvas.restore()
                true
            }
            overlayEffect = effect

            val group = UseCaseGroup.Builder()
                .addUseCase(previewUseCase)
                .addUseCase(videoCapture!!)
                .addEffect(effect)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, group)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showPermissionDenied() {
        binding.permissionMessage.visibility = View.VISIBLE
    }

    private fun onRecordButtonClick() {
        val recording = activeRecording
        if (recording != null) {
            recording.stop()
        } else {
            startRecording()
        }
    }

    @SuppressLint("MissingPermission") // gated by hasAllPermissions() above
    private fun startRecording() {
        val capture = videoCapture ?: return
        val today = DATE_FORMAT.format(Date())
        val uid = UUID.randomUUID().toString().take(8).uppercase()
        val sessionNumber = nextSessionNumber()
        val sessionTag = "V${sessionNumber.toString().padStart(2, '0')}_${sessionConfig.sessionLabel}"
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
        hudPainter.session = sessionTag

        // Snapshot Bios vitals AT record-start (per manifesto: vitals reflect the moment the
        // session began, not when the file was finalised). Null if Bios is unavailable.
        val biosSnapshot = biosClient.snapshot()
        val recordStartMillis = System.currentTimeMillis()
        val configAtStart = sessionConfig

        activeRecording = capture.output
            .prepareRecording(this, output)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        hudPainter.recording = true
                        binding.recordButton.setImageResource(R.drawable.btn_record_active)
                        binding.configButton.isEnabled = false
                        binding.configButton.alpha = 0.3f
                    }
                    is VideoRecordEvent.Finalize -> {
                        hudPainter.recording = false
                        hudPainter.uid = "--------"
                        binding.recordButton.setImageResource(R.drawable.btn_record_idle)
                        binding.configButton.isEnabled = true
                        binding.configButton.alpha = 1f
                        activeRecording = null
                        if (!event.hasError()) {
                            writeSidecar(
                                baseName = displayName,
                                subject = configAtStart.subject,
                                sessionLabel = configAtStart.sessionLabel,
                                experimentLabel = configAtStart.experimentLabel,
                                sessionNumber = sessionNumber,
                                uid = uid,
                                dateLocal = today,
                                recordStartMillis = recordStartMillis,
                                biosSnapshot = biosSnapshot,
                            )
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
        )
        sidecarWriter.write(baseName, CARNET_RELATIVE_PATH, metadata)
        biosCompanionWriter.postRecordingCompleted(metadata)
    }

    private fun showConfigDialog() {
        if (activeRecording != null) return
        val view = layoutInflater.inflate(R.layout.dialog_session_config, null)
        val subjectInput = view.findViewById<android.widget.EditText>(R.id.subject_input)
        val sessionInput = view.findViewById<android.widget.EditText>(R.id.session_label_input)
        val experimentInput = view.findViewById<android.widget.EditText>(R.id.experiment_label_input)
        subjectInput.setText(sessionConfig.subject)
        sessionInput.setText(sessionConfig.sessionLabel)
        experimentInput.setText(sessionConfig.experimentLabel)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.config_title)
            .setView(view)
            .setPositiveButton(R.string.config_save) { _, _ ->
                val newConfig = SessionConfig(
                    subject = SessionConfig.sanitise(
                        subjectInput.text.toString(), SessionConfig.DEFAULT_SUBJECT,
                    ),
                    sessionLabel = SessionConfig.sanitise(
                        sessionInput.text.toString(), SessionConfig.DEFAULT_SESSION_LABEL,
                    ),
                    experimentLabel = SessionConfig.sanitise(
                        experimentInput.text.toString(), SessionConfig.DEFAULT_EXPERIMENT_LABEL,
                    ),
                )
                sessionConfig = newConfig
                SessionConfig.save(this, newConfig)
                hudPainter.subject = newConfig.subject
            }
            .setNegativeButton(R.string.config_cancel, null)
            .show()
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

    companion object {
        private const val TAG = "Carnet"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        private const val CARNET_RELATIVE_PATH = "Movies/Carnet/"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val VERSION_PREFIX = Regex("^V(\\d+)$")
    }
}
