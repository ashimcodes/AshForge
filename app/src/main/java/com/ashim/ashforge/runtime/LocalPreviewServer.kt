package com.ashim.ashforge.runtime

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal, loopback-only static file server used to preview web pages the
 * agent has written into a project's workspace (the "Open in Local Server"
 * button). It only ever binds to 127.0.0.1 on a random free port and only
 * ever serves files that resolve inside [root], so it cannot be reached from
 * outside the device and cannot escape the project's own directory.
 */
internal class LocalPreviewServer(private val root: File) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val url: String = "http://127.0.0.1:${server.localPort}"
    private val canonicalRoot: File = root.canonicalFile

    fun start(): LocalPreviewServer = apply {
        Thread({ acceptLoop() }, "mh-preview-server").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                Thread({ socket.use(::handle) }, "mh-preview-request").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        // Drain (and ignore) request headers; this server only serves GET/HEAD.
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        val method = requestLine.substringBefore(' ')
        if (method != "GET" && method != "HEAD") {
            writeText(output, 405, "text/plain", "Method not allowed")
            return
        }
        val rawPath = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val decodedPath = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)
        val file = resolveFile(decodedPath)
        if (file == null) {
            writeText(output, 404, "text/plain", "Not found: $decodedPath")
            return
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null) {
            writeText(output, 500, "text/plain", "Could not read file")
            return
        }
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: ${mimeType(file.name)}\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        output.write(headers.toByteArray())
        if (method != "HEAD") output.write(bytes)
        output.flush()
    }

    /** Resolves a request path to a file under [root], guarding against path traversal. */
    private fun resolveFile(requestPath: String): File? {
        val relative = requestPath.trim('/')
        var candidate = if (relative.isEmpty()) canonicalRoot else File(canonicalRoot, relative)
        candidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!candidate.toPath().startsWith(canonicalRoot.toPath())) {
            Log.w("LocalPreviewServer", "Blocked path escaping project root: $requestPath")
            return null
        }
        if (candidate.isDirectory) candidate = File(candidate, "index.html")
        return candidate.takeIf { it.isFile }
    }

    private fun writeText(output: BufferedOutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray()
        val reason = if (code in 200..299) "OK" else "Error"
        output.write("HTTP/1.1 $code $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
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

    private fun mimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "txt" -> "text/plain; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        else -> "application/octet-stream"
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }
}
