package org.openkiosk.rest

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

class HttpException(val status: Int, message: String) : Exception(message)

/**
 * A deliberately small HTTP/1.1 reader: one request per connection, explicit
 * Content-Length bodies only, hard limits on every part.
 */
object Http {
    const val MAX_LINE = 8 * 1024
    const val MAX_HEADERS = 64
    const val MAX_BODY = 64 * 1024

    fun read(input: InputStream): HttpRequest {
        val requestLine = readLine(input) ?: throw HttpException(400, "Empty request")
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpException(400, "Malformed request line")
        val method = parts[0]
        if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")) throw HttpException(405, "Method not allowed")
        val target = parts[1]
        if (!target.startsWith("/")) throw HttpException(400, "Malformed path")
        val path = target.substringBefore('?')

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw HttpException(400, "Unexpected end of headers")
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw HttpException(431, "Too many headers")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpException(400, "Malformed header")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        if (headers.containsKey("transfer-encoding")) throw HttpException(411, "Send a Content-Length body")
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw HttpException(400, "Bad Content-Length") } ?: 0
        if (length < 0) throw HttpException(400, "Bad Content-Length")
        if (length > MAX_BODY) throw HttpException(413, "Body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw HttpException(400, "Body shorter than Content-Length")
            read += n
        }
        return HttpRequest(method, path, headers, String(body, Charsets.UTF_8))
    }

    fun write(output: OutputStream, status: Int, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    /** Reads one CRLF- or LF-terminated line; null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else throw HttpException(400, "Unterminated line")
            if (b == '\n'.code) break
            if (buffer.size() >= MAX_LINE) throw HttpException(431, "Line too long")
            buffer.write(b)
        }
        val line = buffer.toString(Charsets.ISO_8859_1.name())
        return if (line.endsWith('\r')) line.dropLast(1) else line
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Error"
    }
}
