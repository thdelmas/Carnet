package com.carnet.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.carnet.app.databinding.ActivityMainBinding

/**
 * Carnet entry point.
 *
 * v0.1 scaffold: shows a placeholder layout. Real implementation lands in
 * follow-up commits:
 *   1. CameraX PreviewView + permission flow.
 *   2. Live HUD overlay (custom View drawing Subject / Session / Date / Time / REC).
 *   3. VideoCapture wired to Record button.
 *   4. Session-config screen (subject / session label / experiment label).
 *   5. Bios snapshot read on record-start, sidecar JSON write on record-stop.
 *   6. Bios companion-write of recording_session_completed event.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
