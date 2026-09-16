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

/**
 * Loopback-only reverse proxy between Claude Code and OpenCode Zen's free tier
 * (https://opencode.ai/zen). Zen already speaks the Anthropic Messages format
 * Claude Code sends, so nothing here translates the request — the only job is
 * attaching the identity Zen's gateway expects from its own official clients:
 * a matching User-Agent/client header, and a stable per-conversation session
 * header the gateway uses to group a conversation's requests together.
 *
 * Without these, Zen's completions endpoint intermittently rejects otherwise
 * valid "public"-credential requests with a plain HTTP 500, even though the
 * same credential works fine for listing models (which needs no session
 * header). The header names/values below match what OpenCode's own desktop
 * client sends — not a secret or a bypassed credential, just matching the
 * shape of a request their own client already sends for this exact free tier.
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
            if (length > 0) {
                connection.doOutput = true
                connection.outputStream.use { it.write(bodyBytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBytes = stream?.readBytes() ?: ByteArray(0)
            val contentType = connection.contentType ?: "application/json"
            connection.disconnect()
            output.write("HTTP/1.1 $code ${if (code in 200..299) "OK" else "Error"}\r\n".toByteArray())
            output.write("Content-Type: $contentType\r\n".toByteArray())
            output.write("Content-Length: ${responseBytes.size}\r\n".toByteArray())
            output.write("Connection: close\r\n\r\n".toByteArray())
            output.write(responseBytes)
            output.flush()
        }.onFailure { error ->
            Log.w("OpenCodeZenProxy", "Upstream request failed: ${error.message}")
            val message = (error.message ?: "Proxy request failed").replace("\"", "'")
            val body = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"$message\"}}"
            val bytes = body.toByteArray()
            output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes)
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
}
