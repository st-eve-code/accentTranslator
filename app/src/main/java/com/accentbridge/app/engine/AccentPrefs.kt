package com.accentbridge.app.engine

import android.content.Context
import org.json.JSONObject

object AccentPrefs {
    private const val PREFS = "accent_bridge"
    private const val KEY_ACTIVE_ACCENT = "active_accent"

    const val OFF_ID = "none"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE_ACCENT, null)

    fun getOrDefault(context: Context): String {
        val stored = get(context) ?: return AccentEngine.defaultAccentId(context)
        return stored
    }

    fun set(context: Context, value: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_ACCENT, value ?: OFF_ID)
            .apply()
    }
}
