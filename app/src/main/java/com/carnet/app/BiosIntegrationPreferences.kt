package com.carnet.app

import android.content.Context

/**
 * Backing store for the "Send recording events to Bios" toggle shown in Settings
 * (Bios design system §6.d). Default is ON — the manifesto-coherent stance is that
 * Carnet is a Bios-spine writer; opt-out is a deliberate user choice, not a missing
 * step in setup.
 */
object BiosIntegrationPreferences {

    private const val PREFS = "carnet_bios_integration"
    private const val KEY_SEND_EVENTS = "send_events"
    private const val DEFAULT_SEND_EVENTS = true

    fun isSendEventsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEND_EVENTS, DEFAULT_SEND_EVENTS)

    fun setSendEventsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SEND_EVENTS, enabled)
            .apply()
    }
}
