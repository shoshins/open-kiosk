package org.openkiosk.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted, slow hashes for secrets the app only ever needs to verify: the
 * settings PIN and the REST API token. Stored as
 * `pbkdf2$<iterations>$<salt>$<hash>`, all Base64.
 */
object Hashing {
    // PBKDF2 at this cost takes a fraction of a second on a PX30-class SoC.
    const val DEFAULT_ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun hash(secret: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return format(iterations, salt, derive(secret, salt, iterations))
    }

    fun verify(secret: String, stored: String?): Boolean {
        val parts = stored?.split('$') ?: return false
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(secret, salt, iterations), expected)
    }

    /**
     * Fast hash for high-entropy random tokens. A 256-bit token cannot be
     * brute-forced, so a slow KDF would only make every API call a DoS lever.
     */
    fun tokenHash(token: String): String =
        "sha256$" + encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    fun verifyToken(token: String, stored: String?): Boolean {
        if (stored == null || !stored.startsWith("sha256$")) return false
        return MessageDigest.isEqual(tokenHash(token).toByteArray(), stored.toByteArray())
    }

    /** A random token for API access, shown to the owner once. */
    fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun derive(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun format(iterations: Int, salt: ByteArray, hash: ByteArray) =
        "pbkdf2\$$iterations\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()
}
