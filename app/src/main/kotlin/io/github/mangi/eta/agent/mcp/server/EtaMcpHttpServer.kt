package io.github.mangi.eta.agent.mcp.server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class McpHttpRequest(
    val method: String,
    /** 不含查询串的路径。 */
    val path: String,
    /** 头名统一小写；同名头取最后一个。 */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

internal class McpHttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 只服务 MCP Streamable HTTP 的最小 HTTP/1.1 服务器：每个连接处理一个请求后关闭，
 * 响应一律带完整长度，不做服务端推送。请求行、头部与正文都有上限，超限直接拒绝。
 */
internal class EtaMcpHttpServer(
    private val bindAddress: InetAddress,
    private val port: Int,
    private val handler: (McpHttpRequest) -> McpHttpResponse,
    private val maxConcurrentRequests: Int = 4,
    private val onHandlerFailure: (Throwable) -> Unit = {},
) : Closeable {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var closed = false
    private val workers = ThreadPoolExecutor(
        maxConcurrentRequests, maxConcurrentRequests, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
    ) { runnable -> Thread(runnable, "eta-mcp-request").apply { isDaemon = true } }.apply {
        allowCoreThreadTimeOut(true)
    }

    val boundPort: Int get() = serverSocket?.localPort ?: -1

    /** 绑定端口并开始接受连接；端口被占用等失败直接抛出，由调用方展示。 */
    @Throws(IOException::class)
    fun start() {
        check(serverSocket == null && !closed) { "MCP 服务已启动或已关闭" }
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port), BACKLOG)
        }
        serverSocket = socket
        Thread({ acceptLoop(socket) }, "eta-mcp-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!closed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                continue
            }
            try {
                workers.execute { serve(client) }
            } catch (_: RejectedExecutionException) {
                respondAndClose(client, McpHttpResponse(503, "server busy".toByteArray(), TEXT_PLAIN))
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val response = try {
                val request = readRequest(BufferedInputStream(socket.getInputStream()))
                try {
                    handler(request)
                } catch (failure: Throwable) {
                    onHandlerFailure(failure)
                    McpHttpResponse(500, "internal error".toByteArray(), TEXT_PLAIN)
                }
            } catch (failure: HttpParseException) {
                McpHttpResponse(failure.status, failure.message.orEmpty().toByteArray(), TEXT_PLAIN)
            } catch (_: IOException) {
                return
            }
            runCatching { writeResponse(socket.getOutputStream(), response) }
        }
    }

    private fun respondAndClose(client: Socket, response: McpHttpResponse) {
        client.use { socket -> runCatching { writeResponse(socket.getOutputStream(), response) } }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket?.close() }
        workers.shutdownNow()
    }

    internal class HttpParseException(val status: Int, message: String) : IOException(message)

    internal companion object {
        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_HEADERS = 100
        const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private const val TEXT_PLAIN = "text/plain; charset=utf-8"

        fun readRequest(input: InputStream): McpHttpRequest {
            val requestLine = readLine(input, MAX_LINE_BYTES) ?: throw IOException("connection closed")
            val parts = requestLine.split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpParseException(400, "bad request line")
            val headers = linkedMapOf<String, String>()
            var headerBytes = 0
            while (true) {
                val line = readLine(input, MAX_LINE_BYTES) ?: throw HttpParseException(400, "truncated headers")
                if (line.isEmpty()) break
                headerBytes += line.length
                if (headerBytes > MAX_HEADER_BYTES || headers.size >= MAX_HEADERS) {
                    throw HttpParseException(431, "headers too large")
                }
                val colon = line.indexOf(':')
                if (colon <= 0) throw HttpParseException(400, "bad header")
                headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            }
            val chunked = headers["transfer-encoding"]?.lowercase(Locale.ROOT)?.contains("chunked") == true
            val body = when {
                chunked -> readChunked(input)
                headers["content-length"] != null -> {
                    val length = headers["content-length"]?.toLongOrNull()
                        ?: throw HttpParseException(400, "bad content-length")
                    if (length < 0) throw HttpParseException(400, "bad content-length")
                    if (length > MAX_BODY_BYTES) throw HttpParseException(413, "body too large")
                    readExactly(input, length.toInt())
                }
                else -> ByteArray(0)
            }
            return McpHttpRequest(
                method = parts[0].uppercase(Locale.ROOT),
                path = parts[1].substringBefore('?'),
                headers = headers,
                body = body,
            )
        }

        fun writeResponse(output: OutputStream, response: McpHttpResponse) {
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            response.contentType?.let { head.append("Content-Type: ").append(it).append("\r\n") }
            head.append("Content-Length: ").append(response.body.size).append("\r\n")
            head.append("Connection: close\r\n")
            response.headers.forEach { (name, value) ->
                // 头值来自服务端自身，仍防御换行注入。
                if ('\r' !in value && '\n' !in value) head.append(name).append(": ").append(value).append("\r\n")
            }
            head.append("\r\n")
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            output.write(response.body)
            output.flush()
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input, MAX_LINE_BYTES) ?: throw HttpParseException(400, "truncated chunk")
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                    ?: throw HttpParseException(400, "bad chunk size")
                if (size < 0) throw HttpParseException(400, "bad chunk size")
                if (size == 0) {
                    while (true) {
                        val trailer = readLine(input, MAX_LINE_BYTES) ?: break
                        if (trailer.isEmpty()) break
                    }
                    return out.toByteArray()
                }
                if (out.size() + size > MAX_BODY_BYTES) throw HttpParseException(413, "body too large")
                out.write(readExactly(input, size))
                readLine(input, MAX_LINE_BYTES)
            }
        }

        private fun readExactly(input: InputStream, length: Int): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) throw HttpParseException(400, "truncated body")
                offset += read
            }
            return bytes
        }

        /** 读取以 CRLF 或 LF 结尾的一行；流在行首结束时返回 null。 */
        private fun readLine(input: InputStream, limit: Int): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
                if (next == '\n'.code) break
                if (next != '\r'.code) buffer.write(next)
                if (buffer.size() > limit) throw HttpParseException(431, "line too long")
            }
            return buffer.toString(Charsets.ISO_8859_1.name())
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> if (status >= 500) "Internal Server Error" else "Error"
        }
    }
}
