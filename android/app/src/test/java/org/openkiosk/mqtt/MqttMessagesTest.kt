package org.openkiosk.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttMessagesTest {
    private val prefix = "kiosk/ok-1/cmd/"
    private fun parse(name: String, payload: String, now: Long = 1_000) =
        MqttMessages.parse(prefix, prefix + name, payload.toByteArray(), now)

    private fun accepted(inbound: Inbound) = (inbound as Inbound.Accept).command

    @Test
    fun acceptsTheVersionedEnvelope() {
        val command = accepted(parse("browser/navigate", """{"v":1,"id":"abc","expires":5000,"payload":{"url":"http://a/"}}"""))
        assertEquals("browser/navigate", command.name)
        assertEquals("http://a/", command.payload.getString("url"))
        assertEquals("abc", command.id)
    }

    @Test
    fun rejectsExpiredAndUnknownVersionEnvelopes() {
        val expired = parse("browser/reload", """{"v":1,"id":"x1","expires":999}""") as Inbound.Reject
        assertEquals("x1", expired.id)
        assertTrue(parse("browser/reload", """{"v":2,"id":"x2"}""") is Inbound.Reject)
    }

    @Test
    fun dropsUnsafeAckIds() {
        val command = accepted(parse("browser/reload", """{"v":1,"id":"../../evil/#"}"""))
        assertNull(command.id)
    }

    @Test
    fun mapsHomeAssistantPlainPayloads() {
        assertEquals(42, accepted(parse("screen/brightness", "42")).payload.getInt("brightness"))
        assertEquals("http://a/", accepted(parse("browser/navigate", "http://a/")).payload.getString("url"))
        assertEquals("Dinner is ready", accepted(parse("audio/tts", "Dinner is ready")).payload.getString("text"))
        assertEquals("browser/reload", accepted(parse("browser/reload", "PRESS")).name)
        assertEquals("screensaver/activate", accepted(parse("screensaver/set", "ON")).name)
        assertEquals("screensaver/deactivate", accepted(parse("screensaver/set", "off")).name)
        assertTrue(parse("screensaver/set", "maybe") is Inbound.Reject)
    }

    @Test
    fun acceptsBareJsonPayloads() {
        assertEquals(10, accepted(parse("screen/brightness", """{"brightness":10}""")).payload.getInt("brightness"))
    }

    @Test
    fun ignoresOtherTopics() {
        assertEquals(Inbound.Ignore, MqttMessages.parse(prefix, "kiosk/other/cmd/browser/reload", ByteArray(0), 0))
        assertEquals(Inbound.Ignore, MqttMessages.parse(prefix, prefix, ByteArray(0), 0))
    }
}
