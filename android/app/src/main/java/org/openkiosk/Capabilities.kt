package org.openkiosk

import android.content.Context
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import org.openkiosk.hardware.SecondaryScreen

/**
 * What this device can actually do, probed at runtime rather than assumed.
 * Commands for a missing capability fail with UNSUPPORTED_CAPABILITY.
 */
data class Capabilities(
    val secondaryScreen: Boolean,
    val textToSpeech: Boolean,
    val wakeScreen: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("secondaryScreen", secondaryScreen)
        .put("textToSpeech", textToSpeech)
        // The screen is never truly switched off without device-owner
        // rights; "sleep" is a black screensaver at minimum brightness.
        .put("screenOff", false)
        .put("wakeScreen", wakeScreen)

    companion object {
        fun detect(context: Context): Capabilities = Capabilities(
            secondaryScreen = SecondaryScreen.detect() != null,
            textToSpeech = context.packageManager.queryIntentServices(
                android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0,
            ).isNotEmpty(),
            wakeScreen = true,
        )
    }
}
