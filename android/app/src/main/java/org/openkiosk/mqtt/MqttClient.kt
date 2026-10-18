package org.openkiosk.mqtt

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class MqttConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val clientId: String,
    val username: String?,
    val password: String?,
    val will: MqttCodec.Will,
)

/**
 * A small MQTT 3.1.1 client: one connection, QoS 0, automatic reconnect with
 * backoff. [onConnected] runs on every (re)connection, so it is where
 * subscriptions and birth messages belong.
 */
class MqttClient(
    private val config: MqttConfig,
    private val onConnected: (MqttClient) -> Unit,
    private val onMessage: (topic: String, payload: ByteArray) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    private var thread: Thread? = null
    private var packetId = 0

    val connected: Boolean get() = output != null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "open-kiosk-mqtt").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { send(MqttCodec.disconnect()) }
        runCatching { socket?.close() }
        thread?.interrupt()
    }

    fun publish(topic: String, payload: String, retain: Boolean = false) {
        runCatching { send(MqttCodec.publish(topic, payload.toByteArray(Charsets.UTF_8), retain)) }
            .onFailure { Log.d(TAG, "publish dropped while disconnected") }
    }

    fun subscribe(vararg topics: String) {
        packetId = packetId % 0xffff + 1
        send(MqttCodec.subscribe(packetId, topics.toList()))
    }

    @Synchronized
    private fun send(bytes: ByteArray) {
        val out = output ?: throw IOException("Not connected")
        out.write(bytes)
        out.flush()
    }

    private fun loop() {
        var backoffMs = MIN_BACKOFF_MS
        while (running) {
            try {
                session()
                backoffMs = MIN_BACKOFF_MS
            } catch (e: Exception) {
                if (running) Log.w(TAG, "MQTT connection lost: ${e.message}")
            } finally {
                output = null
                runCatching { socket?.close() }
            }
            if (!running) return
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                return
            }
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun session() {
        val raw = Socket()
        raw.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
        val s = if (config.tls) upgrade(raw) else raw
        socket = s
        s.soTimeout = KEEPALIVE_SECONDS * 1000 / 2
        val input = s.getInputStream()
        output = s.getOutputStream()

        send(MqttCodec.connect(config.clientId, config.username, config.password, KEEPALIVE_SECONDS, config.will))
        val code = MqttCodec.connackCode(MqttCodec.read(input))
        if (code != 0) throw IOException("Broker refused the connection (code $code)")
        Log.i(TAG, "MQTT connected to ${config.host}:${config.port}")
        onConnected(this)

        var awaitingPong = false
        while (running) {
            val packet = try {
                MqttCodec.read(input)
            } catch (_: SocketTimeoutException) {
                if (awaitingPong) throw IOException("Broker stopped answering pings")
                send(MqttCodec.pingreq())
                awaitingPong = true
                continue
            }
            awaitingPong = false
            when (packet.type) {
                MqttCodec.PUBLISH -> {
                    val message = MqttCodec.parsePublish(packet)
                    runCatching { onMessage(message.topic, message.payload) }
                        .onFailure { Log.w(TAG, "MQTT message handler failed", it) }
                }
                MqttCodec.PINGRESP, MqttCodec.SUBACK -> Unit
                else -> Log.d(TAG, "MQTT packet type ${packet.type} ignored")
            }
        }
    }

    /** TLS with the system trust store and explicit hostname verification, which SSLSocket skips. */
    private fun upgrade(raw: Socket): Socket {
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, config.host, config.port, true) as SSLSocket
        tls.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(config.host, tls.session)) {
            tls.close()
            throw IOException("TLS certificate does not match ${config.host}")
        }
        return tls
    }

    private companion object {
        const val TAG = "OpenKiosk"
        const val KEEPALIVE_SECONDS = 60
        const val CONNECT_TIMEOUT_MS = 10_000
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
