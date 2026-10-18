package org.openkiosk.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTest {
    @Test
    fun pinHashesVerifyAndAreSalted() {
        val a = Hashing.hash("1234", iterations = 1_000)
        val b = Hashing.hash("1234", iterations = 1_000)
        assertNotEquals(a, b)
        assertTrue(Hashing.verify("1234", a))
        assertFalse(Hashing.verify("1235", a))
    }

    @Test
    fun malformedStoredHashesNeverVerify() {
        assertFalse(Hashing.verify("1234", null))
        assertFalse(Hashing.verify("1234", ""))
        assertFalse(Hashing.verify("1234", "pbkdf2\$abc\$x\$y"))
        assertFalse(Hashing.verify("1234", "pbkdf2\$0\$AAAA\$AAAA"))
        assertFalse(Hashing.verify("1234", "md5\$1\$AAAA\$AAAA"))
    }

    @Test
    fun tokensAreRandomAndVerifyByHash() {
        val token = Hashing.newToken()
        assertEquals(43, token.length)
        assertNotEquals(token, Hashing.newToken())
        val stored = Hashing.tokenHash(token)
        assertTrue(Hashing.verifyToken(token, stored))
        assertFalse(Hashing.verifyToken(token + "x", stored))
        assertFalse(Hashing.verifyToken(token, null))
        assertFalse(Hashing.verifyToken(token, token))
    }

    @Test
    fun throttleLocksAfterFreeAttemptsAndDoubles() {
        var now = 0L
        val throttle = Throttle(freeAttempts = 3, baseLockMs = 1_000, maxLockMs = 3_000) { now }
        repeat(2) { throttle.recordFailure() }
        assertEquals(0, throttle.remainingLockMs())
        throttle.recordFailure()
        assertEquals(1_000, throttle.remainingLockMs())
        now = 1_000
        throttle.recordFailure()
        assertEquals(2_000, throttle.remainingLockMs())
        now = 3_000
        throttle.recordFailure()
        assertEquals(3_000, throttle.remainingLockMs()) // capped
        throttle.recordSuccess()
        assertEquals(0, throttle.remainingLockMs())
    }
}
