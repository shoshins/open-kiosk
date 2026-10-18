package org.openkiosk.command

import org.json.JSONObject

/**
 * Everything a remote caller can ask the kiosk to do. REST, MQTT and the
 * JavaScript bridge all parse into these, so validation lives in one place.
 */
sealed interface Command {
    data class Navigate(val url: String) : Command
    data object Reload : Command
    /** 0–100, or -1 to follow the system brightness. */
    data class SetBrightness(val percent: Int) : Command
    data object Wake : Command
    data object Sleep : Command
    data object ScreensaverOn : Command
    data object ScreensaverOff : Command
    data class Speak(val text: String, val language: String?) : Command
    data object StopSpeaking : Command
    data class LaunchApp(val packageName: String) : Command

    companion object {
        const val MAX_TTS_CHARS = 1_000
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val LANGUAGE = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

        /** Names as used in REST paths and MQTT topics. */
        val NAMES = listOf(
            "browser/navigate", "browser/reload", "screen/brightness", "screen/wake", "screen/sleep",
            "screensaver/activate", "screensaver/deactivate", "audio/tts", "audio/stop", "apps/launch",
        )

        fun parse(name: String, payload: JSONObject): Parsed = when (name) {
            "browser/navigate" -> {
                val url = payload.optString("url").trim()
                if (url.isEmpty()) Parsed.Invalid("url is required") else Parsed.Ok(Navigate(url))
            }
            "browser/reload" -> Parsed.Ok(Reload)
            "screen/brightness" -> {
                if (!payload.has("brightness")) {
                    Parsed.Invalid("brightness is required")
                } else {
                    val value = payload.optInt("brightness", Int.MIN_VALUE)
                    if (value == -1 || value in 0..100) Parsed.Ok(SetBrightness(value))
                    else Parsed.Invalid("brightness must be 0–100, or -1 for the system setting")
                }
            }
            "screen/wake" -> Parsed.Ok(Wake)
            "screen/sleep" -> Parsed.Ok(Sleep)
            "screensaver/activate" -> Parsed.Ok(ScreensaverOn)
            "screensaver/deactivate" -> Parsed.Ok(ScreensaverOff)
            "audio/tts" -> {
                val text = payload.optString("text").trim()
                val language = payload.optString("language").trim().ifEmpty { null }
                when {
                    text.isEmpty() -> Parsed.Invalid("text is required")
                    text.length > MAX_TTS_CHARS -> Parsed.Invalid("text is longer than $MAX_TTS_CHARS characters")
                    language != null && !LANGUAGE.matches(language) -> Parsed.Invalid("language must be a BCP 47 tag such as en-US")
                    else -> Parsed.Ok(Speak(text, language))
                }
            }
            "audio/stop" -> Parsed.Ok(StopSpeaking)
            "apps/launch" -> {
                val pkg = payload.optString("package").trim()
                if (PACKAGE.matches(pkg)) Parsed.Ok(LaunchApp(pkg)) else Parsed.Invalid("package must be an Android package name")
            }
            else -> Parsed.Unknown
        }
    }
}

sealed interface Parsed {
    data class Ok(val command: Command) : Parsed
    data class Invalid(val message: String) : Parsed
    data object Unknown : Parsed
}

/** The result of a command, shared by every transport. */
data class Outcome(val code: ErrorCode?, val message: String? = null, val data: JSONObject? = null) {
    val ok: Boolean get() = code == null

    fun toJson(): JSONObject =
        if (ok) JSONObject().put("ok", true).also { json -> data?.let { json.put("data", it) } }
        else JSONObject().put("error", JSONObject().put("code", code!!.name).put("message", message ?: code.name))

    companion object {
        val OK = Outcome(null)
        fun ok(data: JSONObject) = Outcome(null, data = data)
        fun error(code: ErrorCode, message: String) = Outcome(code, message)
    }
}

enum class ErrorCode(val httpStatus: Int) {
    INVALID_ARGUMENT(400),
    UNAUTHORIZED(401),
    NOT_ALLOWED(403),
    NOT_FOUND(404),
    UNKNOWN_COMMAND(404),
    PAYLOAD_TOO_LARGE(413),
    UNSUPPORTED_CAPABILITY(501),
    UNAVAILABLE(503),
    TIMEOUT(504),
}
