package com.accentbridge.app.engine

import android.content.Context

object AccentPrefs {
    private const val PREFS = "accent_bridge"
    private const val KEY_ACTIVE_ACCENT = "active_accent"

    const val OFF_ID = "none"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_ACCENT, null)

    fun set(context: Context, value: String?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (value.isNullOrEmpty() || value == OFF_ID) {
            editor.remove(KEY_ACTIVE_ACCENT)
        } else {
            editor.putString(KEY_ACTIVE_ACCENT, value)
        }
        editor.apply()
    }
}
