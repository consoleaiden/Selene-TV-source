package org.moontechlab.selenetv.service.adfilter

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.moontechlab.selenetv.service.AppDataService
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/**
 * 去广告 DataSource：包装既有 DataSource，对 .m3u8 响应体做原位重写。
 *
 * 移植自 late-show-app 的同名实现，逐字保留其 DataSource 契约修复：
 *  - open() 期间【绝不关闭上游】：media3 的 StatsDataSource 会紧接着调用 getUri()，
 *    DefaultHttpDataSource.getUri() 在 connection 关闭后返回 null → NPE → 播放失败；
 *  - URI 在 open() 内缓存，close() 前始终可返回（相对分片路径解析需要）；
 *  - 超限（>16MB）降级：交付已读前缀后继续直读上游，绝不截断播放列表。
 *
 * 分片 ts 请求原样透传、仍直连 CDN —— 零额外网络跳数、零带宽成本。
 */
class AdFilterDataSource(private val up: DataSource) : DataSource {

    companion object {
        private const val MAX_PLAYLIST_BYTES = 16 * 1024 * 1024
    }

    /** 包装既有 Factory；开关关闭时零开销直通（每次请求都会调用，运行中开关即时生效）。 */
    class Factory(private val base: DataSource.Factory) : DataSource.Factory {
        /** 兜底开关：过滤后播放失败时由播放器置位，直连原始播放链接（不过滤）重试。 */
        @Volatile
        var bypass: Boolean = false

        override fun createDataSource(): DataSource {
            if (!AppDataService.isAdFilterEnabledSync() || bypass) return base.createDataSource()
            return AdFilterDataSource(base.createDataSource())
        }
    }

    private var body: ByteArray? = null
    private var prefix: ByteArray? = null
    private var pos: Long = 0
    private var buffered = false
    private var overflow = false
    private var upstreamClosed = false
    private var cachedUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        up.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        body = null
        prefix = null
        pos = 0
        buffered = false
        overflow = false
        upstreamClosed = false
        cachedUri = null

        val len = up.open(dataSpec)
        val openedUri = up.uri
        cachedUri = openedUri ?: dataSpec.uri

        val path = dataSpec.uri.path
        val isPlaylist = path != null && path.lowercase(Locale.ROOT).endsWith(".m3u8")
        if (!isPlaylist) return len

        val bos = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(16 * 1024)
        try {
            var n: Int
            while (up.read(buf, 0, buf.size).also { n = it } != C.RESULT_END_OF_INPUT) {
                bos.write(buf, 0, n)
                if (bos.size() > MAX_PLAYLIST_BYTES) {
                    overflow = true
                    break
                }
            }
        } catch (e: IOException) {
            closeUpstream()
            throw e
        }

        val input = bos.toByteArray()
        if (overflow) {
            prefix = input
            buffered = true
            return C.LENGTH_UNSET.toLong()
        }

        val out = AdFilter.filter(dataSpec.uri, input)
        body = out ?: input
        pos = 0
        buffered = true
        return body!!.size.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (!buffered) {
            val n = up.read(target, offset, length)
            if (n == C.RESULT_END_OF_INPUT) closeUpstream()
            return n
        }
        if (length == 0) return 0

        val cur = if (overflow) prefix else body
        if (cur != null && pos < cur.size) {
            val n = minOf(length, cur.size - pos.toInt())
            System.arraycopy(cur, pos.toInt(), target, offset, n)
            pos += n
            return n
        }
        if (overflow) {
            val n = up.read(target, offset, length)
            if (n == C.RESULT_END_OF_INPUT) closeUpstream()
            return n
        }
        closeUpstream()
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = cachedUri ?: up.uri

    override fun close() {
        body = null
        prefix = null
        pos = 0
        buffered = false
        overflow = false
        cachedUri = null
        if (upstreamClosed) return
        upstreamClosed = true
        up.close()
    }

    override fun getResponseHeaders(): Map<String, List<String>> = up.responseHeaders

    private fun closeUpstream() {
        if (upstreamClosed) return
        upstreamClosed = true
        try {
            up.close()
        } catch (e: Exception) {
            // 关闭异常不影响播放
        }
    }
}
