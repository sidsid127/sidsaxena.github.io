package com.sidsaxena.notes.security

import android.content.Context

/**
 * Stores a single on-device preference: whether the note list should be gated behind
 * the phone's own biometric/PIN check on launch. Nothing here leaves the device.
 */
class AppLockManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("notes_settings", Context.MODE_PRIVATE)

    var isLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()

    companion object {
        private const val KEY_LOCK_ENABLED = "lock_enabled"
    }
}
