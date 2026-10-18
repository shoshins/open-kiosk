package org.openkiosk

import android.content.Context

/**
 * Persistent kiosk configuration. Passwords and tokens are not stored here
 * in the clear: PIN and API token are kept as salted hashes, and the MQTT
 * password goes through [SecretStore].
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("open_kiosk", Context.MODE_PRIVATE)

    var startUrl: String?
        get() = prefs.getString("start_url", null)
        set(value) = prefs.edit().putString("start_url", value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keep_screen_on", true)
        set(value) = prefs.edit().putBoolean("keep_screen_on", value).apply()

    /** Window brightness, 0–100, or -1 to follow the system setting. */
    var brightness: Int
        get() = prefs.getInt("brightness", -1)
        set(value) = prefs.edit().putInt("brightness", value).apply()

    // Settings PIN
    var pinHash: String?
        get() = prefs.getString("pin_hash", null)
        set(value) = prefs.edit().putString("pin_hash", value).apply()

    // Screensaver
    var screensaverMode: ScreensaverMode
        get() = ScreensaverMode.parse(prefs.getString("screensaver_mode", null)) ?: ScreensaverMode.OFF
        set(value) = prefs.edit().putString("screensaver_mode", value.id).apply()

    var screensaverTimeoutMinutes: Int
        get() = prefs.getInt("screensaver_timeout_min", 10)
        set(value) = prefs.edit().putInt("screensaver_timeout_min", value).apply()

    var screensaverUrl: String?
        get() = prefs.getString("screensaver_url", null)
        set(value) = prefs.edit().putString("screensaver_url", value).apply()

    /** Brightness while the "dim" screensaver is showing, 0–100. */
    var screensaverDimBrightness: Int
        get() = prefs.getInt("screensaver_dim", 5)
        set(value) = prefs.edit().putInt("screensaver_dim", value).apply()

    // REST API
    var restEnabled: Boolean
        get() = prefs.getBoolean("rest_enabled", false)
        set(value) = prefs.edit().putBoolean("rest_enabled", value).apply()

    /** Off: loopback only. On: every interface, so other machines can reach it. */
    var restLan: Boolean
        get() = prefs.getBoolean("rest_lan", false)
        set(value) = prefs.edit().putBoolean("rest_lan", value).apply()

    var restPort: Int
        get() = prefs.getInt("rest_port", 2323)
        set(value) = prefs.edit().putInt("rest_port", value).apply()

    var restTokenHash: String?
        get() = prefs.getString("rest_token_hash", null)
        set(value) = prefs.edit().putString("rest_token_hash", value).apply()

    // MQTT
    var mqttEnabled: Boolean
        get() = prefs.getBoolean("mqtt_enabled", false)
        set(value) = prefs.edit().putBoolean("mqtt_enabled", value).apply()

    var mqttHost: String?
        get() = prefs.getString("mqtt_host", null)
        set(value) = prefs.edit().putString("mqtt_host", value).apply()

    var mqttPort: Int
        get() = prefs.getInt("mqtt_port", 1883)
        set(value) = prefs.edit().putInt("mqtt_port", value).apply()

    var mqttTls: Boolean
        get() = prefs.getBoolean("mqtt_tls", false)
        set(value) = prefs.edit().putBoolean("mqtt_tls", value).apply()

    var mqttUsername: String?
        get() = prefs.getString("mqtt_username", null)
        set(value) = prefs.edit().putString("mqtt_username", value).apply()

    var mqttDiscovery: Boolean
        get() = prefs.getBoolean("mqtt_discovery", true)
        set(value) = prefs.edit().putBoolean("mqtt_discovery", value).apply()

    /** Stable per-install identifier, used in MQTT topics. */
    val deviceId: String
        get() = prefs.getString("device_id", null) ?: Ids.newDeviceId().also {
            prefs.edit().putString("device_id", it).apply()
        }

    // JavaScript bridge and its capabilities
    var bridgeEnabled: Boolean
        get() = prefs.getBoolean("bridge_enabled", false)
        set(value) = prefs.edit().putBoolean("bridge_enabled", value).apply()

    var secondaryScreenEnabled: Boolean
        get() = prefs.getBoolean("secondary_screen_enabled", false)
        set(value) = prefs.edit().putBoolean("secondary_screen_enabled", value).apply()

    /** Packages the app launcher may start, one per entry. */
    var launchAllowlist: Set<String>
        get() = prefs.getStringSet("launch_allowlist", emptySet())!!.toSet()
        set(value) = prefs.edit().putStringSet("launch_allowlist", value).apply()
}

enum class ScreensaverMode(val id: String) {
    OFF("off"),
    BLACK("black"),
    CLOCK("clock"),
    DIM("dim"),
    URL("url");

    companion object {
        fun parse(id: String?): ScreensaverMode? = entries.firstOrNull { it.id == id }
    }
}
