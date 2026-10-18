package org.openkiosk.mqtt

import org.json.JSONObject

/** An inbound command, after unwrapping. */
data class InboundCommand(
    val name: String,
    val payload: JSONObject,
    /** Set when the sender asked for an acknowledgement. */
    val id: String?,
)

sealed interface Inbound {
    data class Accept(val command: InboundCommand) : Inbound
    data class Reject(val id: String?, val reason: String) : Inbound
    data object Ignore : Inbound
}

/**
 * Turns `kiosk/{deviceId}/cmd/<name>` messages into commands. Two payload
 * shapes are accepted:
 *
 * - The versioned envelope: `{"v":1,"id":"…","expires":<epoch ms>,"payload":{…}}`.
 *   Expired envelopes are rejected and every envelope with an id is acknowledged.
 * - Plain values, as Home Assistant's MQTT buttons, numbers and text entities
 *   send them: `PRESS`, `42`, `ON`, a URL or a sentence.
 */
object MqttMessages {
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun parse(commandTopicPrefix: String, topic: String, raw: ByteArray, now: Long): Inbound {
        if (!topic.startsWith(commandTopicPrefix)) return Inbound.Ignore
        val name = topic.removePrefix(commandTopicPrefix)
        if (name.isEmpty()) return Inbound.Ignore
        val text = String(raw, Charsets.UTF_8).trim()

        val json = if (text.startsWith("{")) runCatching { JSONObject(text) }.getOrNull() else null
        if (json != null && json.has("v")) {
            val id = json.optString("id").takeIf { ID.matches(it) }
            if (json.optInt("v") != 1) return Inbound.Reject(id, "Unsupported envelope version")
            if (json.has("expires") && json.optLong("expires") < now) return Inbound.Reject(id, "Command expired")
            val payload = json.optJSONObject("payload") ?: JSONObject()
            return accept(name, payload, id)
        }
        return accept(name, json ?: plainPayload(name, text), null)
    }

    private fun accept(name: String, payload: JSONObject, id: String?): Inbound =
        // Home Assistant switches send ON/OFF to one topic; map it onto the two commands.
        if (name == "screensaver/set") {
            when (payload.optString("state").uppercase()) {
                "ON" -> Inbound.Accept(InboundCommand("screensaver/activate", JSONObject(), id))
                "OFF" -> Inbound.Accept(InboundCommand("screensaver/deactivate", JSONObject(), id))
                else -> Inbound.Reject(id, "Expected ON or OFF")
            }
        } else {
            Inbound.Accept(InboundCommand(name, payload, id))
        }

    private fun plainPayload(name: String, text: String): JSONObject = when (name) {
        "browser/navigate" -> JSONObject().put("url", text)
        "screen/brightness" -> JSONObject().apply { text.toIntOrNull()?.let { put("brightness", it) } }
        "audio/tts" -> JSONObject().put("text", text)
        "apps/launch" -> JSONObject().put("package", text)
        "screensaver/set" -> JSONObject().put("state", text)
        else -> JSONObject()
    }
}
