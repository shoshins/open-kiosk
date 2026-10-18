package org.openkiosk.bridge

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import org.openkiosk.Capabilities
import org.openkiosk.Settings
import org.openkiosk.UrlPolicy
import org.openkiosk.command.Command
import org.openkiosk.command.CommandBus
import org.openkiosk.command.ErrorCode
import org.openkiosk.command.Outcome
import org.openkiosk.command.Parsed
import org.openkiosk.hardware.SecondaryScreen

/**
 * The native side of `window.openKiosk`. Off unless the owner enables it,
 * and every call re-checks that the page showing is on the dashboard's
 * origin. Pages get typed capabilities only: no shell, no files, no intents.
 *
 * Limitation: Android cannot tell which frame made a call, so a cross-origin
 * iframe inside the trusted page could reach the bridge. Only enable it for
 * dashboards whose iframes you trust.
 */
class JsBridge(
    private val context: Context,
    private val settings: Settings,
    private val currentUrl: () -> String?,
) {
    @JavascriptInterface
    fun capabilities(): String = guarded {
        Outcome.ok(Capabilities.detect(context).toJson().put("secondaryScreenEnabled", settings.secondaryScreenEnabled))
    }

    @JavascriptInterface
    fun command(name: String?, payload: String?): String = guarded {
        val json = runCatching { JSONObject(payload ?: "{}") }.getOrNull()
            ?: return@guarded Outcome.error(ErrorCode.INVALID_ARGUMENT, "payload must be a JSON object")
        when (val parsed = Command.parse(name.orEmpty(), json)) {
            is Parsed.Ok -> CommandBus.submit(parsed.command, "bridge")
            is Parsed.Invalid -> Outcome.error(ErrorCode.INVALID_ARGUMENT, parsed.message)
            Parsed.Unknown -> Outcome.error(ErrorCode.UNKNOWN_COMMAND, "Unknown command: $name")
        }
    }

    /** Draws a `data:image/png` or `data:image/jpeg` URL, as produced by `canvas.toDataURL()`. */
    @JavascriptInterface
    fun drawSecondaryScreen(dataUrl: String?): String = guarded {
        val screen = secondaryScreen() ?: return@guarded unsupported()
        val match = DATA_URL.matchEntire(dataUrl.orEmpty())
            ?: return@guarded Outcome.error(ErrorCode.INVALID_ARGUMENT, "Expected a PNG or JPEG data URL")
        if (match.groupValues[1].length > MAX_IMAGE_BASE64) {
            return@guarded Outcome.error(ErrorCode.PAYLOAD_TOO_LARGE, "The image is too large")
        }
        val bytes = runCatching { Base64.decode(match.groupValues[1], Base64.DEFAULT) }.getOrNull()
            ?: return@guarded Outcome.error(ErrorCode.INVALID_ARGUMENT, "The image is not valid Base64")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return@guarded Outcome.error(ErrorCode.INVALID_ARGUMENT, "The image could not be decoded")
        try {
            if (screen.draw(bitmap)) Outcome.OK else Outcome.error(ErrorCode.UNAVAILABLE, "Writing to the screen failed")
        } finally {
            bitmap.recycle()
        }
    }

    @JavascriptInterface
    fun setSecondaryBacklight(on: Boolean): String = guarded {
        val screen = secondaryScreen() ?: return@guarded unsupported()
        if (screen.backlight(on)) Outcome.OK else Outcome.error(ErrorCode.UNSUPPORTED_CAPABILITY, "This screen has no controllable backlight")
    }

    private fun secondaryScreen(): SecondaryScreen? =
        if (settings.secondaryScreenEnabled) SecondaryScreen.detect() else null

    private fun unsupported() =
        Outcome.error(ErrorCode.UNSUPPORTED_CAPABILITY, "No secondary screen is available or it is disabled in settings")

    private inline fun guarded(block: () -> Outcome): String {
        val start = settings.startUrl
        val page = currentUrl()
        val outcome = when {
            !settings.bridgeEnabled -> Outcome.error(ErrorCode.NOT_ALLOWED, "The JavaScript bridge is disabled")
            start == null || page == null || !UrlPolicy.isAllowed(start, page) ->
                Outcome.error(ErrorCode.NOT_ALLOWED, "This page may not use the bridge")
            else -> block()
        }
        return outcome.toJson().toString()
    }

    companion object {
        const val NATIVE_NAME = "OpenKioskNative"
        private const val MAX_IMAGE_BASE64 = 700_000
        private val DATA_URL = Regex("^data:image/(?:png|jpeg);base64,([A-Za-z0-9+/=\\s]+)$")

        /**
         * Injected after each page load: a friendlier `window.openKiosk` over
         * the flat native object, and an `openkioskready` event to wait for.
         */
        const val SHIM = """
(function () {
  if (!window.$NATIVE_NAME || window.openKiosk) return;
  var n = window.$NATIVE_NAME;
  function call(json) { var r = JSON.parse(json); if (r.error) { var e = new Error(r.error.message); e.code = r.error.code; throw e; } return r.data; }
  window.openKiosk = {
    capabilities: function () { return call(n.capabilities()); },
    command: function (name, payload) { return call(n.command(name, JSON.stringify(payload || {}))); },
    secondaryScreen: {
      draw: function (canvas) { return call(n.drawSecondaryScreen(canvas.toDataURL('image/png'))); },
      backlight: function (on) { return call(n.setSecondaryBacklight(!!on)); }
    }
  };
  window.dispatchEvent(new Event('openkioskready'));
})();
"""
    }
}
