package org.openkiosk.command

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTest {
    private fun parse(name: String, json: String = "{}") = Command.parse(name, JSONObject(json))

    @Test
    fun parsesEveryKnownCommand() {
        assertEquals(Parsed.Ok(Command.Navigate("http://a/")), parse("browser/navigate", """{"url":"http://a/"}"""))
        assertEquals(Parsed.Ok(Command.Reload), parse("browser/reload"))
        assertEquals(Parsed.Ok(Command.SetBrightness(40)), parse("screen/brightness", """{"brightness":40}"""))
        assertEquals(Parsed.Ok(Command.SetBrightness(-1)), parse("screen/brightness", """{"brightness":-1}"""))
        assertEquals(Parsed.Ok(Command.Wake), parse("screen/wake"))
        assertEquals(Parsed.Ok(Command.Sleep), parse("screen/sleep"))
        assertEquals(Parsed.Ok(Command.ScreensaverOn), parse("screensaver/activate"))
        assertEquals(Parsed.Ok(Command.ScreensaverOff), parse("screensaver/deactivate"))
        assertEquals(Parsed.Ok(Command.Speak("Hello", "en-GB")), parse("audio/tts", """{"text":" Hello ","language":"en-GB"}"""))
        assertEquals(Parsed.Ok(Command.StopSpeaking), parse("audio/stop"))
        assertEquals(Parsed.Ok(Command.LaunchApp("io.homeassistant.companion.android")), parse("apps/launch", """{"package":"io.homeassistant.companion.android"}"""))
    }

    @Test
    fun rejectsBadArguments() {
        assertTrue(parse("browser/navigate") is Parsed.Invalid)
        assertTrue(parse("screen/brightness") is Parsed.Invalid)
        assertTrue(parse("screen/brightness", """{"brightness":101}""") is Parsed.Invalid)
        assertTrue(parse("screen/brightness", """{"brightness":-2}""") is Parsed.Invalid)
        assertTrue(parse("audio/tts", """{"text":""}""") is Parsed.Invalid)
        assertTrue(parse("audio/tts", JSONObject().put("text", "x".repeat(Command.MAX_TTS_CHARS + 1)).toString()) is Parsed.Invalid)
        assertTrue(parse("audio/tts", """{"text":"hi","language":"en_US; rm -rf"}""") is Parsed.Invalid)
        assertTrue(parse("apps/launch", """{"package":"../../evil"}""") is Parsed.Invalid)
        assertTrue(parse("apps/launch", """{"package":"single"}""") is Parsed.Invalid)
    }

    @Test
    fun unknownCommandsAreReportedAsSuch() {
        assertEquals(Parsed.Unknown, parse("shell/exec"))
        assertEquals(Parsed.Unknown, parse(""))
    }

    @Test
    fun everyListedNameParses() {
        val samples = mapOf(
            "browser/navigate" to """{"url":"http://a/"}""",
            "screen/brightness" to """{"brightness":1}""",
            "audio/tts" to """{"text":"x"}""",
            "apps/launch" to """{"package":"a.b"}""",
        )
        for (name in Command.NAMES) assertTrue(name, parse(name, samples[name] ?: "{}") is Parsed.Ok)
    }

    @Test
    fun outcomesSerialiseConsistently() {
        assertEquals("""{"ok":true}""", Outcome.OK.toJson().toString())
        val error = Outcome.error(ErrorCode.NOT_ALLOWED, "nope").toJson().getJSONObject("error")
        assertEquals("NOT_ALLOWED", error.getString("code"))
        assertEquals("nope", error.getString("message"))
    }
}
