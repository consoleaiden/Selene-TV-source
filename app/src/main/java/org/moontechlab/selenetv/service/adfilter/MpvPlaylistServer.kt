package org.moontechlab.selenetv.service.adfilter

import android.util.Log
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 本地回环 HTTP 播放列表服务（仅供 MPV 内核使用）。
 *
 * 为什么不直接写本地文件交给 mpv：
 *  mpv 对「本地路径」输入会把 libavformat 的 protocol_whitelist 收窄为 file,crypto,data
 *  （本地输入的默认安全策略）。HLS demuxer 去取 https 分片时会被 libavformat 直接拒绝：
 *      https: Protocol 'https' not on whitelist 'file,crypto,data'!
 *  → 过滤后的播放列表一个分片都取不到，表现为「播放失败」或退化成兜底直连。
 *
 *  改由本机回环 HTTP 提供播放列表后，lavf 的父输入是 http，白名单天然包含 http/https，
 *  分片可直连 CDN；对 mpv 而言仍是标准网络 HLS（配合 loadfile 的 demuxer=lavf）。
 *
 *  只监听 127.0.0.1、单客户端、只服务一条固定路径的播放列表，进程退出即释放。
 */
internal object MpvPlaylistServer {
    private const val TAG = "Selene.AdFilter"
    private const val PATH = "/adfilter.m3u8"

    @Volatile
    private var payload: ByteArray? = null

    private var server: ServerSocket? = null
    private var port = 0
    private var thread: Thread? = null

    /** 发布新的过滤后播放列表，返回可供 mpv loadfile 的本地 URL；失败返回 null。 */
    @Synchronized
    fun publish(playlist: String): String? {
        payload = playlist.toByteArray(Charsets.UTF_8)
        val p = ensureStarted() ?: return null
        return "http://127.0.0.1:$p$PATH"
    }

    @Synchronized
    private fun ensureStarted(): Int? {
        val existing = server
        if (existing != null && !existing.isClosed) return port
        return try {
            // 端口交给系统分配（0），只绑回环地址
            val s = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            server = s
            port = s.localPort
            thread = Thread({ acceptLoop(s) }, "mpv-playlist-server").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "playlist server on 127.0.0.1:$port")
            port
        } catch (e: Exception) {
            Log.w(TAG, "playlist server start fail: ${e.message}")
            null
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (_: Exception) {
                return
            }
            try {
                handle(client)
            } catch (_: Exception) {
                // 单次请求失败不影响后续
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
        val input = client.getInputStream()
        val requestLine = readAsciiLine(input) ?: return
        // 读掉请求头（直到空行）
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
        }

        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: ""
        val body = payload
        val out = BufferedOutputStream(client.getOutputStream())

        if (body == null || path != PATH) {
            out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val header = buildString {
            append("HTTP/1.0 200 OK\r\n")
            append("Content-Type: application/vnd.apple.mpegURL\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }
}
