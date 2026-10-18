package org.openkiosk

import java.net.URI

/**
 * Decides which URLs the kiosk may open. By default only the origin of the
 * configured start URL is allowed, so a link on a dashboard cannot walk the
 * panel off to the open web.
 */
object UrlPolicy {

    /** A start URL must be absolute http(s) with a host. */
    fun isValidStartUrl(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme in WEB_SCHEMES && !uri.host.isNullOrEmpty()
    }

    fun isAllowed(startUrl: String, url: String): Boolean {
        val start = parse(startUrl) ?: return false
        val target = parse(url) ?: return false
        if (target.scheme !in WEB_SCHEMES) return false
        return start.scheme.equals(target.scheme, ignoreCase = true) &&
            start.host.equals(target.host, ignoreCase = true) &&
            effectivePort(start) == effectivePort(target)
    }

    private val WEB_SCHEMES = setOf("http", "https")

    private fun parse(url: String): URI? =
        try {
            URI(url.trim())
        } catch (_: Exception) {
            null
        }

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
}
