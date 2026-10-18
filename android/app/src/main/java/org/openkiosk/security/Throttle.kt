package org.openkiosk.security

/**
 * Slows down guessing: after [freeAttempts] failures each further failure
 * locks entry for twice as long as the last, up to [maxLockMs].
 */
class Throttle(
    private val freeAttempts: Int = 5,
    private val baseLockMs: Long = 30_000,
    private val maxLockMs: Long = 15 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var lockedUntil = 0L

    /** Milliseconds until another attempt is allowed; 0 when allowed now. */
    fun remainingLockMs(): Long = (lockedUntil - clock()).coerceAtLeast(0)

    fun recordFailure() {
        failures += 1
        if (failures >= freeAttempts) {
            val doublings = (failures - freeAttempts).coerceAtMost(20)
            lockedUntil = clock() + (baseLockMs shl doublings).coerceAtMost(maxLockMs)
        }
    }

    fun recordSuccess() {
        failures = 0
        lockedUntil = 0
    }
}
