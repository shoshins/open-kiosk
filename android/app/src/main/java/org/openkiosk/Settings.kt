package org.openkiosk

import android.content.Context

/** Persistent kiosk configuration. Secrets do not belong here. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("open_kiosk", Context.MODE_PRIVATE)

    var startUrl: String?
        get() = prefs.getString(KEY_START_URL, null)
        set(value) = prefs.edit().putString(KEY_START_URL, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    private companion object {
        const val KEY_START_URL = "start_url"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    }
}
