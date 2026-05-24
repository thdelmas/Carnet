package com.carnet.app

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Settings — operator's home for both session configuration and the cross-app
 * Bios integration block (Bios design system §6.d). Replaces the modal session-
 * config dialog now that there's a real screen to host both.
 *
 * The Bios block is mandatory across every specialist: section header, toggle,
 * status pill, conditional pending-approval banner, conditional Open-Bios button.
 * Status is re-probed onResume so the user gets a current reading every time
 * they walk back in from Bios's permission screen.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var biosClient: BiosClient
    private lateinit var biosCompanionWriter: BiosCompanionWriter
    private lateinit var sessionConfig: SessionConfig

    private lateinit var subjectInput: EditText
    private lateinit var sessionInput: EditText
    private lateinit var experimentInput: EditText
    private lateinit var sendEventsToggle: MaterialSwitch
    private lateinit var statusPill: TextView
    private lateinit var pendingBanner: LinearLayout
    private lateinit var pendingOpenButton: MaterialButton
    private lateinit var openBiosButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        biosClient = BiosClient(this)
        biosCompanionWriter = BiosCompanionWriter(this)
        sessionConfig = SessionConfig.load(this)

        subjectInput = findViewById(R.id.subject_input)
        sessionInput = findViewById(R.id.session_label_input)
        experimentInput = findViewById(R.id.experiment_label_input)
        sendEventsToggle = findViewById(R.id.bios_send_events_toggle)
        statusPill = findViewById(R.id.bios_status_pill)
        pendingBanner = findViewById(R.id.bios_pending_banner)
        pendingOpenButton = findViewById(R.id.bios_pending_open)
        openBiosButton = findViewById(R.id.bios_open_button)

        subjectInput.setText(sessionConfig.subject)
        sessionInput.setText(sessionConfig.sessionLabel)
        experimentInput.setText(sessionConfig.experimentLabel)

        sendEventsToggle.isChecked = BiosIntegrationPreferences.isSendEventsEnabled(this)
        sendEventsToggle.setOnCheckedChangeListener { _, checked ->
            BiosIntegrationPreferences.setSendEventsEnabled(this, checked)
        }

        pendingOpenButton.setOnClickListener { openBios() }
        openBiosButton.setOnClickListener { openBios() }
    }

    override fun onPause() {
        super.onPause()
        // Save session config on the way out — matches the prior dialog's "save on
        // dismiss" behaviour, but without a separate Save button cluttering the UI.
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
        if (newConfig != sessionConfig) {
            SessionConfig.save(this, newConfig)
            sessionConfig = newConfig
        }
    }

    override fun onResume() {
        super.onResume()
        renderBiosBlock()
    }

    private fun renderBiosBlock() {
        val status = biosClient.status()
        val lastOutcome = biosCompanionWriter.lastPushOutcome
        val pending = lastOutcome == BiosCompanionWriter.LastPushOutcome.PENDING_APPROVAL

        when (status) {
            BiosClient.Status.NOT_INSTALLED -> {
                statusPill.background = getDrawable(R.drawable.pill_dimmed)
                statusPill.setTextColor(getColor(R.color.role_text_secondary))
                statusPill.text = getString(R.string.settings_bios_status_not_installed)
            }
            BiosClient.Status.NOT_ENABLED -> {
                statusPill.background = getDrawable(R.drawable.pill_outlined)
                statusPill.setTextColor(getColor(R.color.role_text_secondary))
                statusPill.text = getString(R.string.settings_bios_status_not_enabled)
            }
            BiosClient.Status.CONNECTED -> {
                statusPill.background = getDrawable(R.drawable.pill_filled)
                statusPill.setTextColor(getColor(R.color.role_on_primary))
                statusPill.text = getString(R.string.settings_bios_status_connected)
            }
        }

        // Pending banner takes priority over the standalone Open-Bios button when
        // there's a per-app permission to grant. Both lead to the same destination.
        pendingBanner.visibility = if (pending) android.view.View.VISIBLE else android.view.View.GONE
        openBiosButton.visibility = if (status != BiosClient.Status.NOT_INSTALLED && !pending) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun openBios() {
        val launch = packageManager.getLaunchIntentForPackage(BiosClient.BIOS_PACKAGE) ?: return
        launch.putExtra(BiosClient.BIOS_EXTRA_NAVIGATE_TO_COMPANIONS, true)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }
}
