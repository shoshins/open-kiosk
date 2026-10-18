package org.openkiosk.rest

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.openkiosk.KioskRuntime
import org.openkiosk.Settings
import org.openkiosk.Telemetry
import org.openkiosk.command.Command
import org.openkiosk.command.CommandBus
import org.openkiosk.command.ErrorCode
import org.openkiosk.command.Outcome
import org.openkiosk.command.Parsed
import org.openkiosk.security.Hashing
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

/**
 * The local REST API, `/api/v1`. Off by default; loopback-only unless the
 * owner opts into LAN access; every call except loopback `/health` needs the
 * Bearer token. See docs/api.md.
 */
class RestServer(private val context: Context, private val settings: Settings) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(WORKERS) as ThreadPoolExecutor

    fun start() {
        val bind = if (settings.restLan) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress()
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bind, settings.restPort), BACKLOG)
        server = socket
        Thread({ acceptLoop(socket) }, "open-kiosk-rest").apply { isDaemon = true }.start()
        Log.i(TAG, "REST API listening on ${bind.hostAddress}:${settings.restPort}")
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                return
            }
            // Shed load instead of queueing without bound.
            if (pool.activeCount >= WORKERS) {
                runCatching { client.close() }
                continue
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use {
            it.soTimeout = SOCKET_TIMEOUT_MS
            val (status, body) = try {
                route(Http.read(it.getInputStream()), it.inetAddress.isLoopbackAddress)
            } catch (e: HttpException) {
                e.status to Outcome.error(ErrorCode.INVALID_ARGUMENT, e.message ?: "Bad request").toJson()
            } catch (e: Exception) {
                Log.w(TAG, "REST request failed", e)
                500 to Outcome.error(ErrorCode.UNAVAILABLE, "Internal error").toJson()
            }
            runCatching { Http.write(it.getOutputStream(), status, body.toString()) }
        }
    }

    internal fun route(request: HttpRequest, fromLoopback: Boolean): Pair<Int, JSONObject> {
        if (!request.path.startsWith(PREFIX)) return reply(Outcome.error(ErrorCode.NOT_FOUND, "Not found"))
        val path = request.path.removePrefix(PREFIX)

        if (path == "health" && request.method == "GET" && fromLoopback) {
            return 200 to JSONObject().put("status", "ok")
        }
        if (!authorized(request)) return reply(Outcome.error(ErrorCode.UNAUTHORIZED, "Missing or invalid Bearer token"))

        return when (request.method to path) {
            "GET" to "health" -> 200 to JSONObject().put("status", "ok")
            "GET" to "device" -> reply(Outcome.ok(Telemetry.device(context)))
            "GET" to "telemetry" -> reply(withState { Outcome.ok(Telemetry.snapshot(context, it)) })
            "GET" to "browser" -> reply(withState { Outcome.ok(JSONObject().put("url", it.currentUrl ?: JSONObject.NULL)) })
            "GET" to "screen" -> reply(withState {
                Outcome.ok(JSONObject().put("brightness", it.brightness).put("screensaver", it.screensaverActive))
            })
            "GET" to "config" -> reply(Outcome.ok(publicConfig()))
            else -> command(request, path)
        }
    }

    private fun command(request: HttpRequest, path: String): Pair<Int, JSONObject> {
        val isCommand = path in Command.NAMES
        val methodOk = request.method == "POST" || (request.method == "PUT" && path == "screen/brightness")
        if (!isCommand) return reply(Outcome.error(ErrorCode.NOT_FOUND, "Not found"))
        if (!methodOk) return 405 to Outcome.error(ErrorCode.INVALID_ARGUMENT, "Use POST").toJson()
        val payload = if (request.body.isBlank()) JSONObject()
        else runCatching { JSONObject(request.body) }.getOrNull()
            ?: return reply(Outcome.error(ErrorCode.INVALID_ARGUMENT, "Body must be a JSON object"))
        return reply(
            when (val parsed = Command.parse(path, payload)) {
                is Parsed.Ok -> CommandBus.submit(parsed.command, "rest")
                is Parsed.Invalid -> Outcome.error(ErrorCode.INVALID_ARGUMENT, parsed.message)
                Parsed.Unknown -> Outcome.error(ErrorCode.UNKNOWN_COMMAND, "Unknown command")
            },
        )
    }

    private fun authorized(request: HttpRequest): Boolean {
        val header = request.header("authorization") ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        return Hashing.verifyToken(header.substring(7).trim(), settings.restTokenHash)
    }

    private fun withState(block: (org.openkiosk.KioskState) -> Outcome): Outcome {
        val state = KioskRuntime.state() ?: return Outcome.error(ErrorCode.UNAVAILABLE, "The kiosk is not running")
        return block(state)
    }

    /** Configuration without secrets. Changing it remotely is deliberately not offered. */
    private fun publicConfig() = JSONObject()
        .put("startUrl", settings.startUrl)
        .put("keepScreenOn", settings.keepScreenOn)
        .put("screensaver", JSONObject()
            .put("mode", settings.screensaverMode.id)
            .put("timeoutMinutes", settings.screensaverTimeoutMinutes))
        .put("rest", JSONObject().put("lan", settings.restLan).put("port", settings.restPort))
        .put("mqtt", JSONObject().put("enabled", settings.mqttEnabled).put("discovery", settings.mqttDiscovery))
        .put("bridge", JSONObject().put("enabled", settings.bridgeEnabled).put("secondaryScreen", settings.secondaryScreenEnabled))
        .put("launchAllowlist", org.json.JSONArray(settings.launchAllowlist.sorted()))

    private fun reply(outcome: Outcome): Pair<Int, JSONObject> =
        (if (outcome.ok) 200 else outcome.code!!.httpStatus) to outcome.toJson()

    private companion object {
        const val TAG = "OpenKiosk"
        const val PREFIX = "/api/v1/"
        const val WORKERS = 4
        const val BACKLOG = 16
        const val SOCKET_TIMEOUT_MS = 10_000
    }
}
