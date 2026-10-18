package org.openkiosk.mqtt

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** The MQTT 3.1.1 packets this client needs, nothing more. QoS 0 throughout. */
object MqttCodec {
    const val CONNECT = 1
    const val CONNACK = 2
    const val PUBLISH = 3
    const val SUBSCRIBE = 8
    const val SUBACK = 9
    const val PINGREQ = 12
    const val PINGRESP = 13
    const val DISCONNECT = 14

    /** Larger inbound packets are refused; commands are small. */
    const val MAX_INBOUND = 256 * 1024

    data class Will(val topic: String, val payload: ByteArray, val retain: Boolean)

    data class Packet(val type: Int, val flags: Int, val body: ByteArray)

    data class Publish(val topic: String, val payload: ByteArray)

    fun connect(clientId: String, username: String?, password: String?, keepAliveSeconds: Int, will: Will?): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(string("MQTT"))
        body.write(4) // protocol level 3.1.1
        var flags = 0x02 // clean session
        if (will != null) flags = flags or 0x04 or (if (will.retain) 0x20 else 0)
        if (username != null) flags = flags or 0x80
        if (password != null) flags = flags or 0x40
        body.write(flags)
        body.write(keepAliveSeconds shr 8 and 0xff)
        body.write(keepAliveSeconds and 0xff)
        body.write(string(clientId))
        if (will != null) {
            body.write(string(will.topic))
            body.write(binary(will.payload))
        }
        if (username != null) body.write(string(username))
        if (password != null) body.write(string(password))
        return packet(CONNECT shl 4, body.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(string(topic))
        body.write(payload)
        return packet((PUBLISH shl 4) or (if (retain) 1 else 0), body.toByteArray())
    }

    fun subscribe(packetId: Int, topics: List<String>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(packetId shr 8 and 0xff)
        body.write(packetId and 0xff)
        for (topic in topics) {
            body.write(string(topic))
            body.write(0) // QoS 0
        }
        // SUBSCRIBE's fixed-header flags must be 0b0010.
        return packet((SUBSCRIBE shl 4) or 0x02, body.toByteArray())
    }

    fun pingreq(): ByteArray = byteArrayOf((PINGREQ shl 4).toByte(), 0)

    fun disconnect(): ByteArray = byteArrayOf((DISCONNECT shl 4).toByte(), 0)

    fun read(input: InputStream): Packet {
        val header = input.read()
        if (header < 0) throw EOFException("Connection closed")
        val length = readRemainingLength(input)
        if (length > MAX_INBOUND) throw IOException("Packet of $length bytes refused")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw EOFException("Connection closed mid-packet")
            read += n
        }
        return Packet(header ushr 4, header and 0x0f, body)
    }

    /** CONNACK return code: 0 accepted; 1–5 refused for the reasons in the spec. */
    fun connackCode(packet: Packet): Int {
        if (packet.type != CONNACK || packet.body.size != 2) throw IOException("Expected CONNACK")
        return packet.body[1].toInt() and 0xff
    }

    fun parsePublish(packet: Packet): Publish {
        val body = packet.body
        if (body.size < 2) throw IOException("Short PUBLISH")
        val topicLength = ((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff)
        var offset = 2 + topicLength
        if (offset > body.size) throw IOException("Bad PUBLISH topic length")
        val topic = String(body, 2, topicLength, Charsets.UTF_8)
        val qos = (packet.flags shr 1) and 0x03
        if (qos > 0) offset += 2 // packet identifier
        if (offset > body.size) throw IOException("Short PUBLISH")
        return Publish(topic, body.copyOfRange(offset, body.size))
    }

    fun encodeRemainingLength(value: Int): ByteArray {
        require(value in 0..268_435_455) { "remaining length out of range: $value" }
        val out = ByteArrayOutputStream()
        var x = value
        do {
            var digit = x % 128
            x /= 128
            if (x > 0) digit = digit or 0x80
            out.write(digit)
        } while (x > 0)
        return out.toByteArray()
    }

    private fun readRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        repeat(4) {
            val digit = input.read()
            if (digit < 0) throw EOFException("Connection closed")
            value += (digit and 0x7f) * multiplier
            if (digit and 0x80 == 0) return value
            multiplier *= 128
        }
        throw IOException("Malformed remaining length")
    }

    private fun packet(header: Int, body: ByteArray): ByteArray =
        byteArrayOf(header.toByte()) + encodeRemainingLength(body.size) + body

    private fun string(value: String): ByteArray = binary(value.toByteArray(Charsets.UTF_8))

    private fun binary(value: ByteArray): ByteArray {
        require(value.size <= 0xffff) { "field longer than 65535 bytes" }
        return byteArrayOf((value.size shr 8).toByte(), value.size.toByte()) + value
    }
}
