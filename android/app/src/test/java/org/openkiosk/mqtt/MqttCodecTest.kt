package org.openkiosk.mqtt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class MqttCodecTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun encodesRemainingLengthAsInTheSpec() {
        assertArrayEquals(bytes(0x00), MqttCodec.encodeRemainingLength(0))
        assertArrayEquals(bytes(0x7f), MqttCodec.encodeRemainingLength(127))
        assertArrayEquals(bytes(0x80, 0x01), MqttCodec.encodeRemainingLength(128))
        assertArrayEquals(bytes(0xff, 0x7f), MqttCodec.encodeRemainingLength(16_383))
        assertArrayEquals(bytes(0x80, 0x80, 0x01), MqttCodec.encodeRemainingLength(16_384))
        assertArrayEquals(bytes(0xff, 0xff, 0xff, 0x7f), MqttCodec.encodeRemainingLength(268_435_455))
    }

    @Test
    fun buildsConnectWithWillAndCredentials() {
        val will = MqttCodec.Will("k/availability", "offline".toByteArray(), retain = true)
        val packet = MqttCodec.connect("id", "user", "pw", 60, will)
        assertEquals(0x10, packet[0].toInt() and 0xff)
        // Variable header: "MQTT", level 4, flags, keep-alive.
        assertArrayEquals(bytes(0, 4, 'M'.code, 'Q'.code, 'T'.code, 'T'.code, 4), packet.copyOfRange(2, 9))
        assertEquals(0x80 or 0x40 or 0x20 or 0x04 or 0x02, packet[9].toInt() and 0xff)
        assertArrayEquals(bytes(0, 60), packet.copyOfRange(10, 12))
        assertEquals(packet.size - 2, packet[1].toInt())
    }

    @Test
    fun roundTripsPublish() {
        val wire = MqttCodec.publish("kiosk/x/cmd/browser/reload", "PRESS".toByteArray(), retain = true)
        val packet = MqttCodec.read(ByteArrayInputStream(wire))
        assertEquals(MqttCodec.PUBLISH, packet.type)
        assertEquals(1, packet.flags and 1)
        val publish = MqttCodec.parsePublish(packet)
        assertEquals("kiosk/x/cmd/browser/reload", publish.topic)
        assertEquals("PRESS", String(publish.payload))
    }

    @Test
    fun skipsThePacketIdentifierOnQos1Publishes() {
        val topic = "t".toByteArray()
        val body = bytes(0, 1) + topic + bytes(0x12, 0x34) + "hi".toByteArray()
        val publish = MqttCodec.parsePublish(MqttCodec.Packet(MqttCodec.PUBLISH, 0x02, body))
        assertEquals("hi", String(publish.payload))
    }

    @Test
    fun subscribeUsesTheReservedFlags() {
        val packet = MqttCodec.subscribe(7, listOf("a/#"))
        assertEquals(0x82, packet[0].toInt() and 0xff)
        assertArrayEquals(bytes(0, 7, 0, 3, 'a'.code, '/'.code, '#'.code, 0), packet.copyOfRange(2, packet.size))
    }

    @Test
    fun readsConnackCodes() {
        assertEquals(0, MqttCodec.connackCode(MqttCodec.read(ByteArrayInputStream(bytes(0x20, 2, 0, 0)))))
        assertEquals(5, MqttCodec.connackCode(MqttCodec.read(ByteArrayInputStream(bytes(0x20, 2, 0, 5)))))
    }

    @Test(expected = IOException::class)
    fun refusesOversizedPackets() {
        val length = MqttCodec.encodeRemainingLength(MqttCodec.MAX_INBOUND + 1)
        MqttCodec.read(ByteArrayInputStream(bytes(0x30) + length))
    }

    @Test(expected = IOException::class)
    fun rejectsTopicLengthsPastTheBody() {
        MqttCodec.parsePublish(MqttCodec.Packet(MqttCodec.PUBLISH, 0, bytes(0, 50, 'a'.code)))
    }
}
