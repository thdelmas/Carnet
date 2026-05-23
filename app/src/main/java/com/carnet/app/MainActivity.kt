package com.carnet.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.carnet.app.databinding.ActivityMainBinding

/**
 * Carnet entry point.
 *
 * v0.1 scaffold: live camera preview + permission flow. Real implementation
 * lands in follow-up commits:
 *   1. CameraX PreviewView + permission flow.   <- this commit
 *   2. Live HUD overlay (custom View drawing Subject / Session / Date / Time / REC).
 *   3. VideoCapture wired to Record button.
 *   4. Session-config screen (subject / session label / experiment label).
 *   5. Bios snapshot read on record-start, sidecar JSON write on record-stop.
 *   6. Bios companion-write of recording_session_completed event.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) bindPreview() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (hasCameraPermission()) {
            bindPreview()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun bindPreview() {
        binding.permissionMessage.visibility = android.view.View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showPermissionDenied() {
        binding.permissionMessage.visibility = android.view.View.VISIBLE
    }
}
