package org.moontechlab.selenetv.service.adfilter

import android.util.Log
import java.net.URI
import kotlin.math.min

/**
 * HLS 播放列表广告过滤（纯逻辑，无 Android/播放器依赖）。
 *
 * 移植自 late-show-app 的同名实现，逐字保留算法与 fail-open 语义。
 * 只保留「过滤 + 规则装载」，不含上报/指纹反馈/时间轴映射（客户端不需要）。
 *
 * 算法要点：
 *  1. 按 #EXT-X-DISCONTINUITY 切成 run；
 *  2. 以「分片 URL 的 host + 目录 + 生效 KEY/MAP 行」为签名归组；
 *  3. 保留总时长最长的组，其余「时长 < 比例×最长组且 ≤ 绝对上限」判为广告；
 *  4. 重复插入判据：同组出现两个分片序列完全相同的 run（高置信）；
 *  5. 安全钳：过滤后总时长 < 原始×floor 则整体放弃（fail-open）。
 *
 * 任何不确定一律返回 null（原样放行）：直播流、MEDIA-SEQUENCE≠0、BYTERANGE、
 * master 列表、run 中途换 KEY/MAP、解析异常。
 */
internal object HlsAdFilter {
    private const val TAG = "Selene.AdFilter"

    /** 最近一次 fail-open 的原因（诊断用）；null = 没拒绝过。 */
    @Volatile
    var lastRejectReason: String? = null
        private set

    private fun reject(reason: String): Result? {
        lastRejectReason = reason
        return null
    }

    /** 过滤结果：playlist 为过滤后的完整播放列表文本。 */
    class Result(
        val playlist: String,
        val removedRuns: Int,
        val removedMs: Long,
        val totalMs: Long,
    )

    /** 过滤规则：内置默认 + 后端规则包合并（见 AdFilterRules）。 */
    class Rules {
        /** 广告源组相对时长上限：组时长 < 该比例 × 最长组时长 才可能是广告。 */
        var relativeMaxRatio: Double = 0.5
        /** 广告源组绝对时长上限（秒）：防多段拼片的正片段被相对规则误杀。 */
        var absoluteMaxSec: Long = 300
        /** 安全钳下限：过滤后时长必须 ≥ 原始时长 × 该值，否则整体放弃。 */
        var safetyFloor: Double = 0.8
        /** 高置信（重复插入）场景的安全钳下限。 */
        var safetyFloorHighConfidence: Double = 0.5
        /** 「重复插入」判据的相对上限。 */
        var duplicateMaxRatio: Double = 0.8
        /** 高置信 URL 模式（小写子串匹配）；只作兜底，命中且 ≤ absoluteMaxSec 才删。 */
        var urlPatterns: MutableList<String> = mutableListOf(
            "/ad/", "/ads/", "/adv/", "/advert", "/gg/", "/preroll/", "/midroll/", "ad.m3u8"
        )
        /** 站点级覆盖：host(小写) -> mode（目前仅 "off" = 该站点整体不过滤）。 */
        val siteMode: MutableMap<String, String> = LinkedHashMap()

        fun offFor(host: String?): Boolean {
            if (host == null || siteMode.isEmpty()) return false
            return "off" == siteMode[host.lowercase()]
        }

        fun patternHit(uri: String?): Boolean {
            if (uri == null || urlPatterns.isEmpty()) return false
            val u = uri.lowercase()
            return urlPatterns.any { it.isNotEmpty() && u.contains(it) }
        }
    }

    // -------------------- 解析中间结构 --------------------
    private class Run {
        val lines = ArrayList<String>()
        val segUris = ArrayList<String>()
        var keyLine: String? = null
        var mapLine: String? = null
        var sig: String = ""
        var segKey: String = ""
        var dur: Double = 0.0
        var firstUri: String? = null
        var segCount: Int = 0
    }

    /** 入口：返回过滤结果；null = 原样放行。永不抛异常。 */
    fun filter(baseUrl: String, text: String, rules: Rules): Result? = try {
        doFilter(baseUrl, text, rules)
    } catch (e: Exception) {
        Log.w(TAG, "parse fail -> passthrough: ${e.message}")
        reject("parse-exception: ${e.message}")
    }

    private fun doFilter(baseUrl: String, text: String, rules: Rules): Result? {
        val lines = text.replace("\uFEFF", "").split("\r?\n".toRegex())

        val header = ArrayList<String>()
        val runs = ArrayList<Run>()
        var cur: Run? = null
        var activeKey: String? = null
        var activeMap: String? = null
        var headerKey: String? = null
        var headerMap: String? = null
        var pendDur = -1.0
        var sawByteRange = false
        var sawEndlist = false
        var sawDiscSeqAfterSegs = false
        var sawSegs = false
        var mediaSeq = 0L
        var uriCount = 0

        for (raw in lines) {
            val ln = raw.trim()
            if (ln.isEmpty()) continue
            val isUri = !ln.startsWith("#")

            if (isUri) {
                if (cur == null) {
                    if (pendDur < 0) return reject("bare-uri-without-extinf")
                    cur = newRun(activeKey, activeMap)
                }
                sawSegs = true
                cur!!.lines.add(ln)
                cur.segUris.add(ln)
                if (cur.firstUri == null) cur.firstUri = resolve(baseUrl, ln)
                cur.segCount++
                cur.dur += pendDur
                pendDur = -1.0
                uriCount++
                continue
            }

            if (ln.startsWith("#EXTINF:")) {
                val d = parseDur(ln)
                if (d < 0) return reject("bad-extinf: $ln")
                if (cur == null) cur = newRun(activeKey, activeMap)
                sawSegs = true
                cur!!.lines.add(ln)
                pendDur = d
                continue
            }

            if (ln.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                if (cur != null && cur.segCount > 0) sawDiscSeqAfterSegs = true
                if (cur == null) header.add(ln)
                continue
            }
            if (ln.startsWith("#EXT-X-BYTERANGE")) { sawByteRange = true; continue }
            if (ln.startsWith("#EXT-X-ENDLIST")) { sawEndlist = true; continue }
            if (ln.startsWith("#EXT-X-STREAM-INF") || ln.startsWith("#EXT-X-MEDIA:")) {
                return reject("master-playlist")
            }
            if (ln.startsWith("#EXT-X-MEDIA-SEQUENCE")) {
                mediaSeq = parseSeq(ln)
                header.add(ln)
                continue
            }
            if (ln.startsWith("#EXT-X-KEY")) {
                if (cur != null && cur.segCount > 0) return reject("key-mid-run")
                activeKey = ln
                if (cur != null) cur.keyLine = ln
                if (cur == null && !sawSegs) headerKey = ln
                continue
            }
            if (ln.startsWith("#EXT-X-MAP")) {
                if (cur != null && cur.segCount > 0) return reject("map-mid-run")
                activeMap = ln
                if (cur != null) cur.mapLine = ln
                if (cur == null && !sawSegs) headerMap = ln
                continue
            }
            if (ln.startsWith("#EXT-X-DISCONTINUITY")) {
                if (cur != null && cur.segCount > 0) {
                    runs.add(cur)
                    cur = null
                }
                continue
            }

            if (cur == null) header.add(ln) else cur.lines.add(ln)
        }
        if (cur != null && cur.segCount > 0) runs.add(cur)

        // ---------- 前置校验：任何不满足一律放行 ----------
        if (sawByteRange || !sawEndlist) {
            return reject("not-vod(byterange=$sawByteRange,no-endlist=${!sawEndlist})")
        }
        if (mediaSeq != 0L || sawDiscSeqAfterSegs) {
            return reject("seq(media=$mediaSeq,discAfterSegs=$sawDiscSeqAfterSegs)")
        }
        if (uriCount == 0 || runs.size < 2) return reject("no-runs(uri=$uriCount,runs=${runs.size})")

        for (r in runs) {
            r.sig = signature(baseUrl, r)
            val kb = StringBuilder()
            for (s in r.segUris) kb.append(s).append('\n')
            r.segKey = kb.toString()
        }

        // ---------- 归组 + 广告判定 ----------
        val groups = LinkedHashMap<String, MutableList<Run>>()
        for (r in runs) groups.getOrPut(r.sig) { ArrayList() }.add(r)
        if (groups.size < 2) return reject("single-group")

        var longest: MutableList<Run>? = null
        var longestDur = 0.0
        for (g in groups.values) {
            var d = 0.0
            for (r in g) d += r.dur
            if (d > longestDur) { longestDur = d; longest = g }
        }
        if (longest == null || longestDur <= 0) return reject("no-longest")

        val removed = HashSet<Run>()
        var highConfidence = false
        for (g in groups.values) {
            if (g === longest) continue
            var d = 0.0
            for (r in g) d += r.dur
            if (d > rules.absoluteMaxSec) continue
            var ad = d < rules.relativeMaxRatio * longestDur
            var dup = false
            if (!ad && d < rules.duplicateMaxRatio * longestDur && hasSelfDuplicate(g)) {
                ad = true; dup = true
            }
            if (!ad && rules.patternHit(g[0].firstUri)) ad = true
            if (ad) {
                removed.addAll(g)
                if (dup) highConfidence = true
            }
        }
        if (removed.isEmpty()) return reject("no-ad-candidate")
        if (removed.size == runs.size) return reject("all-removed")

        var totalDur = 0.0
        var keptDur = 0.0
        for (r in runs) {
            totalDur += r.dur
            if (r !in removed) keptDur += r.dur
        }
        val floor = if (highConfidence) min(rules.safetyFloor, rules.safetyFloorHighConfidence) else rules.safetyFloor
        if (keptDur < floor * totalDur) {
            Log.d(TAG, "safety clamp: kept=${keptDur.toLong()}s total=${totalDur.toLong()}s floor=$floor -> passthrough")
            return reject("safety-clamp(kept=${keptDur.toLong()},total=${totalDur.toLong()},floor=$floor)")
        }

        // ---------- 按 run 重建 ----------
        val out = StringBuilder(text.length)
        for (h in header) out.append(h).append('\n')
        if (headerKey != null) out.append(headerKey).append('\n')
        if (headerMap != null) out.append(headerMap).append('\n')
        var lastKey = headerKey
        var lastMap = headerMap
        var prevSig: String? = null
        var removedRuns = 0
        for (r in runs) {
            if (r in removed) { removedRuns++; continue }
            if (prevSig != null && r.sig != prevSig) out.append("#EXT-X-DISCONTINUITY\n")
            if (r.keyLine != lastKey) {
                out.append(r.keyLine ?: "#EXT-X-KEY:METHOD=NONE").append('\n')
                lastKey = r.keyLine
            }
            if (r.mapLine != null) {
                if (r.mapLine != lastMap) {
                    out.append(r.mapLine).append('\n')
                    lastMap = r.mapLine
                }
            } else if (lastMap != null) {
                return reject("map-reset")
            }
            for (l in r.lines) out.append(l).append('\n')
            prevSig = r.sig
        }
        out.append("#EXT-X-ENDLIST\n")

        Log.d(TAG, "filtered $removedRuns ad run(s): ${(totalDur - keptDur).toLong()}s / ${totalDur.toLong()}s")
        return Result(out.toString(), removedRuns, Math.round((totalDur - keptDur) * 1000), Math.round(totalDur * 1000))
    }

    private fun newRun(key: String?, map: String?): Run {
        val r = Run()
        r.keyLine = key
        r.mapLine = map
        return r
    }

    /** 签名 = host | 分片目录 | KEY 行 | MAP 行。host 必须参与：正片段可能落在另一 host 上。 */
    private fun signature(baseUrl: String, r: Run): String {
        val u = r.firstUri ?: ""
        var host = ""
        var dir = u
        try {
            val abs = URI(baseUrl).resolve(u)
            host = abs.host ?: ""
            val p = abs.path ?: ""
            val cut = p.lastIndexOf('/')
            dir = if (cut >= 0) p.substring(0, cut) else p
        } catch (e: Exception) {
            val cut = u.lastIndexOf('/')
            dir = if (cut >= 0) u.substring(0, cut) else u
        }
        return host + "|" + dir + "|" + (r.keyLine ?: "-") + "|" + (r.mapLine ?: "-")
    }

    /** 组内是否存在「两个 run 的分片序列完全相同」。 */
    private fun hasSelfDuplicate(g: List<Run>): Boolean {
        if (g.size < 2) return false
        val seen = HashSet<String>()
        for (r in g) {
            if (r.segKey.isEmpty()) continue
            if (!seen.add(r.segKey)) return true
        }
        return false
    }

    private fun resolve(baseUrl: String, uri: String): String = try {
        URI(baseUrl).resolve(uri).toString()
    } catch (e: Exception) {
        uri
    }

    private fun parseDur(line: String): Double = try {
        val v = line.substring(8).split(",")[0].trim()
        val d = v.toDouble()
        if (d >= 0) d else -1.0
    } catch (e: Exception) {
        -1.0
    }

    private fun parseSeq(line: String): Long = try {
        line.substring(line.indexOf(':') + 1).trim().toLong()
    } catch (e: Exception) {
        -1L
    }
}
