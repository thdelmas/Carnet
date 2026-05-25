package com.carnet.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.OrientationEventListener
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carnet.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Carnet UI shell. All CameraX work — Preview, VideoCapture, OverlayEffect, HudPainter,
 * recording lifecycle, sidecar + Bios writes — lives in [CameraCaptureService]. This
 * activity binds to the service on create, hands over the PreviewView surface provider,
 * pushes session-config + device-rotation updates, forwards record-button taps, and
 * observes the recording-state flow to drive button UI.
 *
 * The split exists so the camera client is owned by a foreground service of type
 * `camera|microphone` — that prevents Android from disconnecting the camera when
 * [ScreenCaptureService]'s `mediaProjection` FGS starts, which is what makes
 * mid-recording screen-capture arming work without freezing the file.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serviceBinder: CameraCaptureService.LocalBinder? = null
    private var orientationListener: OrientationEventListener? = null
    @Volatile private var hudRotation: Int = 0
    private lateinit var sessionConfig: SessionConfig
    private var hasInitiallyBoundCamera = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = REQUIRED_PERMISSIONS.all { results[it] == true }
        if (granted) startAndBindCameraService() else showPermissionDenied()
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort: cadence worker no-ops when denied */ }

    private val requestScreenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            Log.i(TAG, "MediaProjection consent granted")
            ScreenCaptureService.start(this, result.resultCode, data)
            updateScreenCaptureButton(armed = true)
        } else {
            Toast.makeText(this, R.string.screen_capture_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? CameraCaptureService.LocalBinder ?: return
            serviceBinder = binder
            binder.setHudConfig(sessionConfig)
            binder.setHudRotation(hudRotation)
            binder.setOverlayAspectListener { aspect ->
                binding.grid.post { binding.grid.contentAspect = aspect }
            }
            // PreviewView.display is null until the view is attached to a window. The
            // service connection can fire before that, so fall back to the window's
            // display (and 0 as a final safety net for the portrait-locked activity).
            val rotation = binding.preview.display?.rotation
                ?: @Suppress("DEPRECATION") windowManager.defaultDisplay?.rotation
                ?: 0
            binder.bindCamera(binding.preview.surfaceProvider, rotation)
            hasInitiallyBoundCamera = true
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    binder.state.collect { onRecordingStateChanged(it) }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder?.setOverlayAspectListener(null)
            serviceBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionConfig = SessionConfig.load(this)

        binding.recordButton.setOnClickListener { onRecordButtonClick() }
        binding.configButton.setOnClickListener { openSettings() }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.screenCaptureButton.setOnClickListener { onScreenCaptureToggle() }
        updateScreenCaptureButton(armed = ScreenCaptureService.activeSource != null)

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                // Quantize to the nearest 90° step. Drives only the HUD's in-canvas rotation —
                // we deliberately don't touch preview/videoCapture targetRotation because the
                // activity is portrait-locked and re-rotating the camera buffer into a portrait
                // FIT_CENTER box just squishes the image.
                val newRotation = ((degrees + 45) / 90 % 4) * 90
                if (newRotation != hudRotation) {
                    hudRotation = newRotation
                    serviceBinder?.setHudRotation(newRotation)
                }
            }
        }

        if (hasAllPermissions()) {
            startAndBindCameraService()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        // Backgrounding tears down PreviewView's SurfaceView surface and closes the
        // bound SurfaceRequest. The existing Preview use case won't ask for a new one,
        // so on return the camera renders into nothing — symptom: black preview after
        // returning from another app. Skip the first onStart (right after onCreate) —
        // bindCamera runs from onServiceConnected once the service binds, and the
        // activity uses singleTask so subsequent foregrounds reuse this instance.
        //
        // Branch on recording state: a full rebind during recording closes the
        // Recorder's DeferrableSurface and kills the take. When recording is live,
        // just re-set the Preview's surface provider to trigger a fresh SurfaceRequest
        // without touching VideoCapture or the OverlayEffect.
        val binder = serviceBinder
        if (binder == null || !hasInitiallyBoundCamera) return
        val recording = binder.state.value is CameraCaptureService.RecordingState.Recording
        if (recording && binder.refreshPreviewSurface(binding.preview.surfaceProvider)) {
            return
        }
        val rotation = binding.preview.display?.rotation
            ?: @Suppress("DEPRECATION") windowManager.defaultDisplay?.rotation
            ?: 0
        binder.bindCamera(binding.preview.surfaceProvider, rotation)
    }

    override fun onResume() {
        super.onResume()
        orientationListener?.enable()
        updateScreenCaptureButton(armed = ScreenCaptureService.activeSource != null)
        // Settings may have changed session config; reload + push to the service so the
        // HUD reflects the new subject/session/experiment without restarting the app.
        val latest = SessionConfig.load(this)
        if (latest != sessionConfig) {
            sessionConfig = latest
            serviceBinder?.setHudConfig(latest)
        }
    }

    override fun onPause() {
        orientationListener?.disable()
        super.onPause()
    }

    override fun onDestroy() {
        serviceBinder?.setOverlayAspectListener(null)
        runCatching { unbindService(connection) }
        serviceBinder = null
        // Per-session lifecycle: the camera FGS releases on stop, the screen-capture
        // service ditto so the next launch re-prompts for MediaProjection consent.
        if (isFinishing) {
            stopService(Intent(this, CameraCaptureService::class.java))
            ScreenCaptureService.stop(this)
        }
        super.onDestroy()
    }

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startAndBindCameraService() {
        binding.permissionMessage.visibility = View.GONE
        val intent = Intent(this, CameraCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun showPermissionDenied() {
        binding.permissionMessage.visibility = View.VISIBLE
    }

    private fun onRecordButtonClick() {
        val binder = serviceBinder ?: return
        if (binder.state.value is CameraCaptureService.RecordingState.Recording) {
            binder.stopRecording()
        } else {
            binder.startRecording(sessionConfig)
        }
    }

    private fun onRecordingStateChanged(state: CameraCaptureService.RecordingState) {
        when (state) {
            is CameraCaptureService.RecordingState.Recording -> {
                binding.recordButton.setImageResource(R.drawable.btn_record_active)
                binding.configButton.isEnabled = false
                binding.configButton.alpha = 0.3f
            }
            is CameraCaptureService.RecordingState.Idle -> {
                binding.recordButton.setImageResource(R.drawable.btn_record_idle)
                binding.configButton.isEnabled = true
                binding.configButton.alpha = 1f
                maybeRequestNotificationPermission()
            }
        }
    }

    private fun onScreenCaptureToggle() {
        if (ScreenCaptureService.activeSource != null) {
            ScreenCaptureService.stop(this)
            updateScreenCaptureButton(armed = false)
            return
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        requestScreenCapture.launch(mgr.createScreenCaptureIntent())
    }

    private fun updateScreenCaptureButton(armed: Boolean) {
        binding.screenCaptureButton.alpha = if (armed) 1f else 0.4f
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (CadenceReminder.hasNotificationPermission(this)) return
        // Only ask after a successful take so the prompt has context — "we finished a
        // session, here's why we want to ping you next week" rather than out of the blue.
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openSettings() {
        if (serviceBinder?.state?.value is CameraCaptureService.RecordingState.Recording) return
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    companion object {
        private const val TAG = "Carnet"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
