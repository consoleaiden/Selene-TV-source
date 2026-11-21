package org.moontechlab.selenetv.service.adfilter

import android.content.Context
import android.net.Uri
import android.util.Log
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.service.MediaHttp
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * MPV 内核去广告预处理。
 *
 * MPV 走 libmpv 自己的 demuxer，无法像 ExoPlayer 那样在 DataSource 层拦截响应体，
 * 故采用「预取播放列表 → 端上过滤 → 绝对化 URL 写本地文件 → loadfile 本地文件」：
 *  - 开关关闭 / 非 m3u8 → 直接回调原始 URL，零开销；
 *  - master 列表自动跟一层 variant 再过滤（HlsAdFilter 本身拒绝 master）；
 *  - 任何一步失败 → 回调原始 URL（fail-open，广告过滤永远不影响播放）；
 *  - 过滤生效后分片 URL 全部绝对化（本地文件无法解析相对路径）。
 *
 * 注意：过滤后时间轴与原片不同（广告被删），传入的起播位置在 MPV 侧直接作用于
 * 过滤后的时间轴——续播位置可能略有偏移，可接受（起播位置主要来自记忆播放）。
 */
object MpvAdFilterPrep {
    private const val TAG = "Selene.AdFilter"
    private const val TIMEOUT_MS = 12_000
    private const val UA = "AptvPlayer/1.4.10"

    private val cacheFile = arrayOf<File?>(null)

    /**
     * 异步预处理播放地址，完成后在回调线程回调最终 URL（可能是本地文件路径）。
     * 回调不保证线程；调用方自行 main.post。
     */
    fun prepare(context: Context, url: String, onReady: (String) -> Unit) {
        // 非 HLS 或开关关闭：不预取，直接放行（与 DataSource 直通语义一致）。
        if (!url.lowercase().contains(".m3u8") || !AppDataService.isAdFilterEnabledSync()) {
            onReady(url)
            return
        }
        Thread({
            val final = try { prep(context, url) } catch (e: Throwable) {
                Log.w(TAG, "mpv prep fail -> passthrough: ${e.message}")
                url
            }
            onReady(final)
        }, "mpv-adfilter-prep").apply { isDaemon = true }.start()
    }

    private fun prep(context: Context, url: String): String {
        var target = url
        var text = fetch(target) ?: return url
        // master 列表：跟第一层 variant 后再过滤（HlsAdFilter 对 master fail-open）。
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.split("\r?\n".toRegex())
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: return url
            target = resolve(target, variant)
            text = fetch(target) ?: return url
            if (text.contains("#EXT-X-STREAM-INF")) return url  // 两层 master：放弃
        }
        val filtered = AdFilter.filter(Uri.parse(target), text.toByteArray(StandardCharsets.UTF_8))
            ?: return url  // 直播流/无广告/规则拒绝等，一律原样播放
        val out = absolutize(target, String(filtered, StandardCharsets.UTF_8))
        val file = cacheFile[0] ?: File(context.cacheDir, "mpv_adfilter.m3u8").also { cacheFile[0] = it }
        file.writeText(out, StandardCharsets.UTF_8)
        Log.d(TAG, "mpv prep ok: ${file.length()}B url=${target.substringBefore('?')}")
        return file.absolutePath
    }

    /** 过滤后播放列表的分片 URL 绝对化（注释/头行原样保留）。 */
    private fun absolutize(baseUrl: String, text: String): String =
        text.split("\r?\n".toRegex()).joinToString("\n") { ln ->
            val t = ln.trim()
            if (t.isEmpty() || t.startsWith("#")) ln else resolve(baseUrl, t)
        }

    private fun resolve(base: String, uri: String): String = try {
        URI(base).resolve(uri).toString()
    } catch (_: Exception) {
        uri
    }

    private fun fetch(url: String): String? {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()
        MediaHttp.httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "mpv prep HTTP ${resp.code}")
                return null
            }
            val body = resp.body ?: return null
            if (body.contentLength() > 4 * 1024 * 1024) return null  // 防御：播放列表不应超 4MB
            return body.string()
        }
    }
}
