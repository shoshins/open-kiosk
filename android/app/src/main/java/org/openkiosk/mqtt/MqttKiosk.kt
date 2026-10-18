package org.openkiosk.mqtt

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import org.openkiosk.KioskRuntime
import org.openkiosk.Settings
import org.openkiosk.Telemetry
import org.openkiosk.command.Command
import org.openkiosk.command.CommandBus
import org.openkiosk.command.ErrorCode
import org.openkiosk.command.Outcome
import org.openkiosk.command.Parsed
import org.openkiosk.security.SecretStore

/**
 * The kiosk on MQTT: topics under `kiosk/{deviceId}/`, a retained
 * availability with Last Will, periodic state and telemetry, commands with
 * acknowledgements, and optional Home Assistant discovery. See docs/mqtt.md.
 */
class MqttKiosk(private val context: Context, private val settings: Settings) {
    private val id = settings.deviceId
    private val root = "kiosk/$id/"
    private val cmdPrefix = "${root}cmd/"
    private val worker = HandlerThread("open-kiosk-mqtt-worker").apply { start() }
    private val handler = Handler(worker.looper)
    private var client: MqttClient? = null

    fun start() {
        val host = settings.mqttHost?.takeIf { it.isNotBlank() } ?: return
        val config = MqttConfig(
            host = host,
            port = settings.mqttPort,
            tls = settings.mqttTls,
            clientId = "open-kiosk-$id",
            username = settings.mqttUsername?.takeIf { it.isNotBlank() },
            password = SecretStore(context).get(PASSWORD_SECRET),
            will = MqttCodec.Will("${root}availability", "offline".toByteArray(), retain = true),
        )
        client = MqttClient(config, ::onConnected, ::onMessage).also { it.start() }
        handler.postDelayed(publishLoop, PUBLISH_INTERVAL_MS)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val c = client
        client = null
        // Network I/O is not allowed on the main thread, and a clean DISCONNECT
        // suppresses the Last Will, so announce "offline" explicitly first.
        handler.post {
            c?.publish("${root}availability", "offline", retain = true)
            c?.stop()
        }
        worker.quitSafely()
    }

    /** Called by the kiosk when screen state changes, so Home Assistant updates promptly. */
    fun stateChanged() {
        handler.post { publishState() }
    }

    private fun onConnected(c: MqttClient) {
        c.publish("${root}availability", "online", retain = true)
        c.subscribe("${cmdPrefix}#")
        if (settings.mqttDiscovery) publishDiscovery(c)
        handler.post { publishState(); publishTelemetry() }
    }

    private fun onMessage(topic: String, payload: ByteArray) {
        when (val inbound = MqttMessages.parse(cmdPrefix, topic, payload, System.currentTimeMillis())) {
            Inbound.Ignore -> Unit
            is Inbound.Reject -> ack(inbound.id, Outcome.error(ErrorCode.INVALID_ARGUMENT, inbound.reason))
            is Inbound.Accept -> handler.post {
                val command = inbound.command
                val outcome = when (val parsed = Command.parse(command.name, command.payload)) {
                    is Parsed.Ok -> CommandBus.submit(parsed.command, "mqtt")
                    is Parsed.Invalid -> Outcome.error(ErrorCode.INVALID_ARGUMENT, parsed.message)
                    Parsed.Unknown -> Outcome.error(ErrorCode.UNKNOWN_COMMAND, "Unknown command: ${command.name}")
                }
                ack(command.id, outcome)
                publishState()
            }
        }
    }

    private fun ack(commandId: String?, outcome: Outcome) {
        if (commandId == null) return
        client?.publish("${root}ack/$commandId", outcome.toJson().toString())
    }

    private val publishLoop = object : Runnable {
        override fun run() {
            publishState()
            publishTelemetry()
            handler.postDelayed(this, PUBLISH_INTERVAL_MS)
        }
    }

    private fun publishState() {
        val c = client ?: return
        val state = KioskRuntime.state() ?: return
        c.publish("${root}screen/state", JSONObject()
            .put("brightness", state.brightness)
            .put("screensaver", state.screensaverActive).toString(), retain = true)
        c.publish("${root}browser/state", JSONObject().put("url", state.currentUrl ?: JSONObject.NULL).toString(), retain = true)
        c.publish("${root}state", JSONObject().put("running", true).toString(), retain = true)
    }

    private fun publishTelemetry() {
        val c = client ?: return
        val state = KioskRuntime.state() ?: return
        c.publish("${root}telemetry", Telemetry.snapshot(context, state).toString(), retain = true)
    }

    private fun publishDiscovery(c: MqttClient) {
        val device = JSONObject()
            .put("identifiers", JSONArray().put("open_kiosk_$id"))
            .put("name", "Open Kiosk $id")
            .put("manufacturer", "Open Kiosk")
            .put("model", android.os.Build.MODEL)
        val availability = "${root}availability"

        fun entity(component: String, key: String, name: String, extra: JSONObject.() -> Unit) {
            val config = JSONObject()
                .put("name", name)
                .put("unique_id", "open_kiosk_${id}_$key")
                .put("object_id", "open_kiosk_${id}_$key")
                .put("availability_topic", availability)
                .put("device", device)
            config.extra()
            c.publish("homeassistant/$component/open_kiosk_$id/$key/config", config.toString(), retain = true)
        }

        entity("button", "reload", "Reload") { put("command_topic", "${cmdPrefix}browser/reload") }
        entity("button", "wake", "Wake screen") { put("command_topic", "${cmdPrefix}screen/wake") }
        entity("button", "sleep", "Sleep screen") { put("command_topic", "${cmdPrefix}screen/sleep") }
        entity("number", "brightness", "Brightness") {
            put("command_topic", "${cmdPrefix}screen/brightness")
            put("state_topic", "${root}screen/state")
            put("value_template", "{{ value_json.brightness }}")
            put("min", 0).put("max", 100).put("step", 1).put("unit_of_measurement", "%")
        }
        entity("switch", "screensaver", "Screensaver") {
            put("command_topic", "${cmdPrefix}screensaver/set")
            put("state_topic", "${root}screen/state")
            put("value_template", "{{ 'ON' if value_json.screensaver else 'OFF' }}")
        }
        entity("text", "url", "Dashboard URL") {
            put("command_topic", "${cmdPrefix}browser/navigate")
            put("state_topic", "${root}browser/state")
            put("value_template", "{{ value_json.url }}")
            put("max", 255)
        }
        entity("text", "speak", "Speak") {
            put("command_topic", "${cmdPrefix}audio/tts")
            put("max", Command.MAX_TTS_CHARS)
        }
        entity("sensor", "memory", "Free memory") {
            put("state_topic", "${root}telemetry")
            put("value_template", "{{ value_json.memory.availableMb }}")
            put("unit_of_measurement", "MB")
            put("entity_category", "diagnostic")
        }
        entity("sensor", "uptime", "Uptime") {
            put("state_topic", "${root}telemetry")
            put("value_template", "{{ value_json.uptimeSeconds }}")
            put("unit_of_measurement", "s")
            put("device_class", "duration")
            put("entity_category", "diagnostic")
        }
        entity("sensor", "battery", "Battery") {
            put("state_topic", "${root}telemetry")
            put("value_template", "{{ value_json.battery.percent }}")
            put("unit_of_measurement", "%")
            put("device_class", "battery")
            put("entity_category", "diagnostic")
        }
    }

    companion object {
        const val PASSWORD_SECRET = "mqtt_password"
        private const val PUBLISH_INTERVAL_MS = 60_000L
    }
}
