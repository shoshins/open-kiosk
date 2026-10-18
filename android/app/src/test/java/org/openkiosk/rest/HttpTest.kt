package org.openkiosk.rest

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HttpTest {
    private fun read(raw: String) = Http.read(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    private fun status(raw: String): Int =
        try {
            read(raw)
            fail("expected an HttpException")
            0
        } catch (e: HttpException) {
            e.status
        }

    @Test
    fun parsesARequestWithBody() {
        val request = read("POST /api/v1/browser/reload?x=1 HTTP/1.1\r\nAuthorization: Bearer t\r\nContent-Length: 2\r\n\r\n{}")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/browser/reload", request.path)
        assertEquals("Bearer t", request.header("AUTHORIZATION"))
        assertEquals("{}", request.body)
    }

    @Test
    fun acceptsBareLineFeeds() {
        assertEquals("/x", read("GET /x HTTP/1.1\nHost: a\n\n").path)
    }

    @Test
    fun enforcesLimits() {
        assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: ${Http.MAX_BODY + 1}\r\n\r\n"))
        assertEquals(431, status("GET /${"a".repeat(Http.MAX_LINE)} HTTP/1.1\r\n\r\n"))
        val headers = (1..Http.MAX_HEADERS + 1).joinToString("") { "X-$it: 1\r\n" }
        assertEquals(431, status("GET / HTTP/1.1\r\n$headers\r\n"))
    }

    @Test
    fun rejectsMalformedRequests() {
        assertEquals(400, status("NONSENSE\r\n\r\n"))
        assertEquals(405, status("BREW / HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET http://evil/ HTTP/1.1\r\n\r\n"))
        assertEquals(411, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"))
        assertEquals(400, status("GET / HTTP/1.1\r\nno-colon\r\n\r\n"))
    }

    @Test
    fun writesJsonResponses() {
        val out = ByteArrayOutputStream()
        Http.write(out, 401, """{"error":{}}""")
        val text = out.toString(Charsets.UTF_8.name())
        assertEquals(true, text.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
        assertEquals(true, text.contains("Content-Length: 12\r\n"))
        assertEquals(true, text.contains("X-Content-Type-Options: nosniff\r\n"))
        assertEquals(true, text.endsWith("\r\n\r\n{\"error\":{}}"))
    }
}
