package com.embytv.api

import android.util.Log
import com.embytv.app.SmbServerConfig
import jcifs.smb.SmbRandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * SMB 本地 HTTP 代理：把 smb:// 文件通过本地 http 流暴露给播放器（EXO/MPV 都支持 http + Range）。
 * 服务器只监听 127.0.0.1，路径参数携带服务器标识 + 编码后的 smb 路径。
 */
object SmbHttpServer {
    private const val TAG = "SmbHttpServer"
    private const val PORT = 19191
    private const val BUF_SIZE = 128 * 1024
    private const val MAX_THREADS = 8

    private var serverSocket: ServerSocket? = null
    private var running = false
    private val threadPool: ExecutorService = Executors.newFixedThreadPool(MAX_THREADS)
    private val serverRegistry = ConcurrentHashMap<String, SmbServerConfig>()

    val localBaseUrl: String get() = "http://127.0.0.1:$PORT"

    @Synchronized
    fun start() {
        if (running) return
        try {
            val server = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            running = true
            thread {
                while (running) {
                    try {
                        val client = server.accept()
                        threadPool.execute { handle(client) }
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            }.apply { isDaemon = true }
            Log.i(TAG, "SMB HTTP proxy listening on $localBaseUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverRegistry.clear()
        threadPool.shutdownNow()
        try { threadPool.awaitTermination(3, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
    }

    /** 注册服务器，返回访问 key */
    fun register(srv: SmbServerConfig): String {
        start()
        val key = srv.id
        serverRegistry[key] = srv
        return key
    }

    /** 生成播放 URL */
    fun playUrl(srvKey: String, smbPath: String): String {
        val encoded = java.net.URLEncoder.encode(smbPath, "UTF-8")
        return "$localBaseUrl/$srvKey/$encoded"
    }

    private fun handle(socket: Socket) {
        var raf: SmbRandomAccessFile? = null
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val headerBuf = ByteArray(4096)
            var headerEnd = -1
            var total = 0
            while (total < headerBuf.size) {
                val n = input.read(headerBuf, total, headerBuf.size - total)
                if (n < 0) break
                total += n
                val idx = indexOfHeaderEnd(headerBuf, total)
                if (idx >= 0) { headerEnd = idx; break }
            }
            if (headerEnd < 0) { socket.close(); return }

            val head = String(headerBuf, 0, headerEnd, Charsets.ISO_8859_1)
            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull() ?: run { socket.close(); return }
            val parts = requestLine.split(" ")
            if (parts.size < 2) { try { socket.close() } catch(_: Exception) {}; return }
            val method = parts[0]
            val path = parts[1]

            val pathSegs = path.trimStart('/').split("/", limit = 2)
            if (pathSegs.size < 2) { writeError(socket, 400, "Bad Request"); return }
            val srvKey = URLDecoder.decode(pathSegs[0], "UTF-8")
            val smbPath = URLDecoder.decode(pathSegs[1], "UTF-8")
            val srv = serverRegistry[srvKey]
            if (srv == null) { writeError(socket, 404, "Server not found"); return }

            // Range 头解析
            var rangeStart = 0L
            var rangeEnd = -1L
            for (line in lines) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    val rangeVal = line.substringAfter(':').trim()
                    if (rangeVal.startsWith("bytes=")) {
                        val spec = rangeVal.substring(6).trim()
                        val dashIdx = spec.indexOf('-')
                        if (dashIdx >= 0) {
                            rangeStart = spec.substring(0, dashIdx).toLongOrNull() ?: 0L
                            rangeEnd = spec.substring(dashIdx + 1).toLongOrNull() ?: -1L
                        }
                    }
                }
            }

            try {
                raf = SmbRandomAccessFile("smb://${srv.host}/$smbPath", "r", 8192, SmbClient.contextFor(srv))
            } catch (e: Exception) {
                writeError(socket, 404, "File not found: ${e.message}")
                return
            }

            val fileLen = raf.length()
            val effectiveStart = rangeStart.coerceIn(0, (fileLen - 1).coerceAtLeast(0))
            val effectiveEnd = if (rangeEnd >= 0) rangeEnd.coerceAtMost(fileLen - 1) else fileLen - 1
            val contentLen = if (fileLen > 0) effectiveEnd - effectiveStart + 1 else 0L
            val isRange = rangeStart > 0 || rangeEnd >= 0

            val out = socket.getOutputStream()
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ${if (isRange && fileLen > 0) "206 Partial Content" else "200 OK"}\r\n")
            sb.append("Content-Type: video/mp4\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Content-Length: $contentLen\r\n")
            if (isRange && fileLen > 0) {
                sb.append("Content-Range: bytes $effectiveStart-$effectiveEnd/$fileLen\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.flush()

            if (method == "HEAD") { return }

            // 流式传输（try/finally 保证 raf 关闭）
            try {
                raf.seek(effectiveStart)
                val buf = ByteArray(BUF_SIZE)
                var remaining = contentLen
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = raf.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                out.flush()
            } finally {
                raf.close()
                raf = null
                try { socket.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle error: ${e.message}")
            if (raf != null) { try { raf.close() } catch (_: Exception) {} }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun indexOfHeaderEnd(buf: ByteArray, len: Int): Int {
        for (i in 0 until len - 3) {
            if (buf[i] == '\r'.code.toByte() && buf[i + 1] == '\n'.code.toByte() &&
                buf[i + 2] == '\r'.code.toByte() && buf[i + 3] == '\n'.code.toByte()) return i
        }
        return -1
    }

    private fun writeError(socket: Socket, code: Int, msg: String) {
        try {
            val out = socket.getOutputStream()
            val body = "$code $msg"
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $code $msg\r\n")
            sb.append("Content-Type: text/plain\r\n")
            sb.append("Content-Length: ${body.length}\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            out.write(body.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }
}