package com.phlox.tvwebbrowser.phone

import android.content.Context
import android.util.Log
import com.phlox.tvwebbrowser.sync.SyncCrypto
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A small local-only HTTP server for a phone remote. It is deliberately separate from SyncServer:
 * it has no access to bookmarks/files and only accepts the commands below.
 *
 * The phone page is served from assets/phone. The one-time token is in the QR URL and is checked on
 * the page request and on every command. The static assets of the page carry nothing personal and
 * are served without it. The server binds to the LAN and stops when the caller stops it.
 */
class PhoneRemoteServer(
    private val context: Context,
    private val host: Host,
) {
    interface Host {
        fun openUrl(url: String)
        fun sendKey(key: String)
        fun sendText(text: String)
        fun scroll(dy: Int)
    }

    /** Called once when the phone opens the page: the TV hides the QR and gives the page the focus */
    @Volatile
    var onPageServed: (() -> Unit)? = null

    /** Called when the phone ends the session (after the response is sent) */
    @Volatile
    var onStopped: (() -> Unit)? = null

    companion object {
        private const val TAG = "PhoneRemoteServer"
        private const val MAX_HEADER = 16 * 1024
        private const val MAX_BODY = 32 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor = Executors.newFixedThreadPool(3)
    private val running = AtomicBoolean(false)
    private val pageServed = AtomicBoolean(false)
    private var token: String? = null
    private var port: Int = 0
    private var address: String? = null

    val currentUrl: String? get() = if (running.get()) urlOf() else null

    @Synchronized
    fun start(): String? {
        if (running.get()) return currentUrl
        address = localIpv4() ?: return null
        val token = SyncCrypto.randomId()
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot bind", e)
            return null
        }
        this.token = token
        this.port = socket.localPort
        this.serverSocket = socket
        pageServed.set(false)
        running.set(true)
        executor = Executors.newFixedThreadPool(3)
        acceptThread = thread(name = "PhoneRemoteServer", isDaemon = true) {
            acceptLoop(socket)
        }
        Log.d(TAG, "listening on $port")
        return currentUrl
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        try {
            executor.shutdownNow()
        } catch (_: Exception) {
        }
        token = null
        port = 0
    }

    private fun urlOf(): String? {
        val ip = address ?: return null
        val t = token ?: return null
        return "http://$ip:$port/?t=$t"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            try {
                val client = socket.accept()
                try {
                    executor.execute { handle(client) }
                } catch (_: Exception) {
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val header = readHeader(input) ?: return
            val lines = header.split("\r\n")
            if (lines.isEmpty()) return
            val request = lines[0].split(' ')
            if (request.size < 2) return
            val method = request[0].uppercase()
            val target = request[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            var contentLength = 0
            for (line in lines.drop(1)) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            if (contentLength !in 0..MAX_BODY) {
                respond(socket, 413, "text/plain", "too large")
                return
            }
            if (method == "GET") {
                //the static files of the page carry nothing personal: no token needed
                when (path) {
                    "/app.js" -> {
                        serveAsset(socket, "phone/app.js", "application/javascript; charset=utf-8")
                        return
                    }
                    "/style.css" -> {
                        serveAsset(socket, "phone/style.css", "text/css; charset=utf-8")
                        return
                    }
                    "/favicon.ico" -> {
                        respond(socket, 204, "text/plain", "")
                        return
                    }
                }
            }
            val suppliedToken = queryParam(query, "t") ?: headerToken(lines)
            if (!validToken(suppliedToken)) {
                respond(socket, 401, "text/plain; charset=utf-8", "unauthorized")
                return
            }
            if (method == "GET") {
                when (path) {
                    "/", "/index.html" -> {
                        serveAsset(socket, "phone/index.html", "text/html; charset=utf-8")
                        if (!pageServed.getAndSet(true)) onPageServed?.invoke()
                    }
                    else -> respond(socket, 404, "text/plain", "not found")
                }
                return
            }
            if (method == "POST" && path == "/api/command") {
                val body = readBody(input, contentLength)
                val json = JSONObject(String(body, StandardCharsets.UTF_8))
                val stop = handleCommand(json)
                respond(socket, 200, "application/json; charset=utf-8", "{\"ok\":true}")
                if (stop) {
                    stop()
                    onStopped?.invoke()
                }
                return
            }
            respond(socket, 404, "text/plain", "not found")
        } catch (e: Exception) {
            Log.d(TAG, "request failed: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleCommand(json: JSONObject): Boolean {
        when (json.optString("type")) {
            "url" -> {
                val url = json.optString("url").trim()
                if (url.isNotEmpty() && isAllowedUrl(url)) host.openUrl(url)
            }
            "key" -> {
                val key = json.optString("key").trim()
                if (key in ALLOWED_KEYS) host.sendKey(key)
            }
            "text" -> host.sendText(json.optString("text").take(2000))
            "scroll" -> {
                val dy = json.optInt("dy", 0).coerceIn(-2000, 2000)
                if (dy != 0) host.scroll(dy)
            }
            "stop" -> return true
        }
        return false
    }

    private fun isAllowedUrl(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 2048 || value.any { it.isISOControl() }) return false
        //a search text is fine: the shell turns it into a search url
        val lower = value.lowercase()
        return !lower.startsWith("javascript:") && !lower.startsWith("data:") &&
            !lower.startsWith("file:") && !lower.startsWith("content:") &&
            !lower.startsWith("intent:")
    }

    private fun readHeader(input: java.io.InputStream): String? {
        val out = ByteArrayOutputStream()
        var last = -1
        var b: Int
        while (out.size() < MAX_HEADER) {
            b = input.read()
            if (b < 0) return null
            out.write(b)
            if (last == '\r'.code && b == '\n'.code) {
                val text = out.toString(StandardCharsets.UTF_8.name())
                if (text.endsWith("\r\n\r\n")) return text
            }
            last = b
        }
        return null
    }

    private fun readBody(input: java.io.InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == length) body else body.copyOf(offset)
    }

    private fun serveAsset(socket: Socket, name: String, type: String) {
        val bytes = try {
            context.assets.open(name).use { it.readBytes() }
        } catch (_: Exception) {
            respond(socket, 404, "text/plain", "not found")
            return
        }
        respond(socket, 200, type, bytes)
    }

    private fun respond(socket: Socket, code: Int, type: String, body: String) {
        respond(socket, code, type, body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun respond(socket: Socket, code: Int, type: String, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            401 -> "Unauthorized"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        val out = socket.getOutputStream()
        out.write(header)
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun validToken(supplied: String?): Boolean {
        val expected = token ?: return false
        val actual = supplied ?: return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun queryParam(query: String, name: String): String? {
        if (query.isEmpty()) return null
        for (part in query.split('&')) {
            val key = part.substringBefore('=')
            if (key == name) {
                return try {
                    URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8.name())
                } catch (_: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun headerToken(lines: List<String>): String? =
        lines.firstOrNull { it.startsWith("X-Remote-Token:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()

    private fun localIpv4(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = interfaces.toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            for (nif in candidates) {
                for (address in nif.inetAddresses) {
                    if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private val ALLOWED_KEYS = setOf(
        "up", "down", "left", "right", "ok", "back", "home", "reload", "playpause"
    )
}
