package org.openkiosk

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONObject

/** Live state the kiosk reports about itself. */
data class KioskState(
    val currentUrl: String?,
    val screensaverActive: Boolean,
    val brightness: Int,
    val lastError: String?,
)

/**
 * Device health for the REST API, MQTT and the settings screen. Nothing here
 * leaves the device unless the owner has enabled REST or MQTT.
 */
object Telemetry {

    fun device(context: Context): JSONObject {
        val webView = WebView.getCurrentWebViewPackage()
        return JSONObject()
            .put("app", JSONObject().put("version", appVersion(context)))
            .put("android", JSONObject().put("release", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT))
            .put("webview", JSONObject().put("package", webView?.packageName).put("version", webView?.versionName))
            .put("model", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL))
            .put("capabilities", Capabilities.detect(context).toJson())
    }

    fun snapshot(context: Context, state: KioskState): JSONObject {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val stat = StatFs(Environment.getDataDirectory().path)
        return JSONObject()
            .put("uptimeSeconds", SystemClock.elapsedRealtime() / 1000)
            .put("memory", JSONObject()
                .put("availableMb", memory.availMem / MB)
                .put("totalMb", memory.totalMem / MB)
                .put("low", memory.lowMemory)
                .put("appPssMb", Debug.getPss() / 1024))
            .put("storage", JSONObject()
                .put("freeMb", stat.availableBytes / MB)
                .put("totalMb", stat.totalBytes / MB))
            .put("network", network(context))
            .put("battery", battery(context))
            .put("browser", JSONObject()
                .put("url", state.currentUrl ?: JSONObject.NULL)
                .put("lastError", state.lastError ?: JSONObject.NULL))
            .put("screen", JSONObject()
                .put("brightness", state.brightness)
                .put("screensaver", state.screensaverActive))
    }

    private fun network(context: Context): JSONObject {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        return JSONObject()
            .put("connected", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            .put("type", type)
    }

    private fun battery(context: Context): JSONObject {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return JSONObject().put("present", false)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return JSONObject()
            .put("present", intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false))
            .put("percent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("charging", plugged != 0)
    }

    private fun appVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"

    private const val MB = 1024L * 1024L
}
