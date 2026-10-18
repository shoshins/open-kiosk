package org.openkiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    private val start = "http://homeassistant.local:8123/lovelace/0"

    @Test
    fun allowsPagesOnTheStartOrigin() {
        assertTrue(UrlPolicy.isAllowed(start, "http://homeassistant.local:8123/config"))
        assertTrue(UrlPolicy.isAllowed(start, "http://HomeAssistant.local:8123/"))
    }

    @Test
    fun rejectsOtherHostsPortsAndSchemes() {
        assertFalse(UrlPolicy.isAllowed(start, "http://example.com/"))
        assertFalse(UrlPolicy.isAllowed(start, "http://homeassistant.local:8124/"))
        assertFalse(UrlPolicy.isAllowed(start, "https://homeassistant.local:8123/"))
        assertFalse(UrlPolicy.isAllowed(start, "file:///sdcard/secret.txt"))
        assertFalse(UrlPolicy.isAllowed(start, "javascript:alert(1)"))
        assertFalse(UrlPolicy.isAllowed(start, "intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun treatsDefaultPortsAsExplicitOnes() {
        assertTrue(UrlPolicy.isAllowed("https://dash.example/", "https://dash.example:443/x"))
        assertTrue(UrlPolicy.isAllowed("http://dash.example/", "http://dash.example:80/x"))
    }

    @Test
    fun rejectsLookalikeHosts() {
        assertFalse(UrlPolicy.isAllowed("https://dash.example/", "https://dash.example.evil.test/"))
        assertFalse(UrlPolicy.isAllowed("https://dash.example/", "https://user@evil.test/"))
    }

    @Test
    fun validatesStartUrls() {
        assertTrue(UrlPolicy.isValidStartUrl("http://192.168.1.10:8123/"))
        assertTrue(UrlPolicy.isValidStartUrl("https://dash.example/path?x=1"))
        assertFalse(UrlPolicy.isValidStartUrl("homeassistant.local"))
        assertFalse(UrlPolicy.isValidStartUrl("file:///etc/hosts"))
        assertFalse(UrlPolicy.isValidStartUrl("http://"))
        assertFalse(UrlPolicy.isValidStartUrl("not a url"))
    }
}
