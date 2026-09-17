package com.ashim.ashforge.runtime

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Loopback-only reverse proxy between Claude Code and OpenCode Zen's free tier
 * (https://opencode.ai/zen). Zen already speaks the Anthropic Messages format
 * Claude Code sends, so nothing here translates the request — its two jobs are:
 *
 * 1. Attaching the identity Zen's gateway expects from its own official clients
 *    (a matching User-Agent/client header, and a stable per-conversation session
 *    header the gateway uses to group a conversation's requests). Without these,
 *    completions can 500 even with a valid credential, though listing models
 *    doesn't need it.
 *
 * 2. Falling back to another free model when the requested one fails. Zen's free
 *    tier is shared, best-effort capacity — individual models go down or 500
 *    intermittently. OpenCode's own client silently retries a failed request on
 *    a different model rather than surfacing the error; this does the same, so
 *    a single flaky model doesn't block the whole session.
 */
internal class OpenCodeZenProxy(
    private val upstreamBaseUrl: String,
    private val sessionId: String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"

    fun start(): OpenCodeZenProxy = apply {
        Thread({ acceptLoop() }, "mh-zen-proxy").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                Thread({ socket.use(::handle) }, "mh-zen-proxy-request").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val method = requestLine.substringBefore(' ')
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bodyBytes, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        val output = BufferedOutputStream(socket.getOutputStream())

        // Candidate models to try, in order, for this specific request. Only completion
        // requests (a JSON body with a "model" field) get fallback candidates; everything
        // else (e.g. GET /v1/models) is a single attempt with its body untouched.
        val requestedModel = runCatching {
            if (length > 0) JSONObject(bodyBytes.decodeToString()).optString("model").takeIf { it.isNotBlank() } else null
        }.getOrNull()
        val candidates = if (requestedModel != null) {
            listOf(requestedModel) + FALLBACK_MODELS.filterNot { it == requestedModel }
        } else {
            listOf<String?>(null)
        }

        var lastCode = 502
        var lastBytes = ByteArray(0)
        var lastContentType = "application/json"
        var succeeded = false

        for (model in candidates) {
            if (succeeded) break
            val attemptBody = if (model != null && model != requestedModel) {
                runCatching {
                    JSONObject(bodyBytes.decodeToString()).put("model", model).toString().toByteArray()
                }.getOrDefault(bodyBytes)
            } else {
                bodyBytes
            }
            runCatching {
                val connection = URL(upstreamBaseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 20_000
                connection.readTimeout = 180_000
                // Carry over what Claude Code sent (auth, content-type, anthropic-version, its
                // own request-id) — everything except hop-by-hop headers and the identity ones
                // we're about to override below.
                headers.forEach { (key, value) ->
                    if (key !in setOf("host", "content-length", "connection", "user-agent")) {
                        runCatching { connection.setRequestProperty(key, value) }
                    }
                }
                connection.setRequestProperty("User-Agent", "opencode/latest/1.18.15/desktop")
                connection.setRequestProperty("x-opencode-client", "desktop")
                connection.setRequestProperty("x-opencode-session", sessionId)
                if (attemptBody.isNotEmpty()) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(attemptBody) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val responseBytes = stream?.readBytes() ?: ByteArray(0)
                val contentType = connection.contentType ?: "application/json"
                connection.disconnect()
                lastCode = code
                lastBytes = responseBytes
                lastContentType = contentType
                if (code in 200..299) {
                    succeeded = true
                } else if (code in 500..599 && model != candidates.last()) {
                    Log.w("OpenCodeZenProxy", "Model '$model' returned $code, trying next fallback")
                } else {
                    succeeded = true // Not a 5xx (e.g. 400/401) — retrying a different model won't help.
                }
            }.onFailure { error ->
                Log.w("OpenCodeZenProxy", "Upstream request failed for model '$model': ${error.message}")
                lastCode = 502
                val message = (error.message ?: "Proxy request failed").replace("\"", "'")
                lastBytes = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"$message\"}}".toByteArray()
                lastContentType = "application/json"
            }
        }

        runCatching {
            output.write("HTTP/1.1 $lastCode ${if (lastCode in 200..299) "OK" else "Error"}\r\n".toByteArray())
            output.write("Content-Type: $lastContentType\r\n".toByteArray())
            output.write("Content-Length: ${lastBytes.size}\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.write(lastBytes)
            output.flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
            if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
            bytes += value.toByte()
        }
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }

    companion object {
        // Tried in order after whatever model was originally requested. Kept short and
        // deliberately diverse (different underlying model families) so one provider's
        // outage doesn't take out every candidate at once.
        private val FALLBACK_MODELS = listOf(
            "deepseek-v4-flash-free",
            "mimo-v2.5-free",
            "minimax-m2.5-free",
            "nemotron-3-super-free",
            "big-pickle",
        )
    }
}


