package org.openkiosk

import java.security.SecureRandom

object Ids {
    private val random = SecureRandom()

    /** Short, random, topic-safe: "ok-" and 10 lowercase hex characters. */
    fun newDeviceId(): String {
        val bytes = ByteArray(5).also(random::nextBytes)
        return "ok-" + bytes.joinToString("") { "%02x".format(it) }
    }
}
