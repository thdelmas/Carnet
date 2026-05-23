package com.carnet.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
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
 * Carnet entry point.
 *
 * v0.1 scaffold roadmap:
 *   1. CameraX PreviewView + permission flow.
 *   2. Live HUD overlay.
 *   3. VideoCapture wired to Record button.
 *   4. Session-config screen (subject / session label / experiment label).   <- next
 *   5. Bios snapshot read on record-start, sidecar JSON write on record-stop.
 *   6. Bios companion-write of recording_session_completed event.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // Placeholders until step 4 wires the session-config screen.
    private val subject = "subject"
    private val sessionLabel = "SCAFFOLD"

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) bindCamera() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.hud.subject = subject
        binding.recordButton.setOnClickListener { onRecordButtonClick() }

        if (hasAllPermissions()) {
            bindCamera()
        } else {
            requestPermissions.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun bindCamera() {
        binding.permissionMessage.visibility = View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                videoCapture,
            )
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
        val displayName = "V${sessionNumber.toString().padStart(2, '0')}_${sessionLabel}_${today}_$uid"

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, CARNET_RELATIVE_PATH)
        }
        val output = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        binding.hud.uid = uid
        binding.hud.session = "V${sessionNumber.toString().padStart(2, '0')}_$sessionLabel"

        activeRecording = capture.output
            .prepareRecording(this, output)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        binding.hud.recording = true
                        binding.recordButton.setImageResource(R.drawable.btn_record_active)
                    }
                    is VideoRecordEvent.Finalize -> {
                        binding.hud.recording = false
                        binding.hud.uid = "--------"
                        binding.recordButton.setImageResource(R.drawable.btn_record_idle)
                        activeRecording = null
                    }
                }
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

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        private const val CARNET_RELATIVE_PATH = "Movies/Carnet/"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val VERSION_PREFIX = Regex("^V(\\d+)$")
    }
}
