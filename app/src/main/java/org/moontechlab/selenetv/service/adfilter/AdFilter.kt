package org.moontechlab.selenetv.service.adfilter

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.moontechlab.selenetv.service.AppDataService
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 智能去广告门面：开关 + 过滤入口 + 规则包拉取（TTL 30min）。
 *
 * 移植自 late-show-app 的 AdFilter，按需求**去掉了命中上报 / 指纹反馈 / 时间轴映射**，
 * 只保留端上过滤与接收服务端下发的规则包。
 *
 * 分级降级（广告过滤永远不能影响播放）：
 *  - 用户关开关 → [filter] 直接放行；
 *  - 规则拉取失败 / 服务端未启用 → 静默，内置规则照常工作；
 *  - 未登录 / 单机模式 → 跳过拉取，仍可用内置规则过滤。
 */
object AdFilter {
    private const val TAG = "Selene.AdFilter"
    private const val PULL_TTL_MS = 30 * 60 * 1000L
    private const val TIMEOUT_MS = 12000
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullMutex = Mutex()
    private var pulling = false

    @Volatile
    private var rules: HlsAdFilter.Rules? = null
    private val rulesLock = Any()

    /** 最近一次未执行过滤的原因（诊断用）；null = 本次实际执行了过滤。 */
    @Volatile
    var lastSkipReason: String? = null
        private set

    /** 最近一次命中摘要，例如 "2runs/51s of 3061s"。 */
    @Volatile
    var lastSummary: String? = null
        private set

    /** 过滤入口：返回过滤后的播放列表字节；null = 原样放行。 */
    fun filter(uri: Uri?, body: ByteArray?): ByteArray? {
        if (uri == null || body == null || body.isEmpty()) {
            lastSkipReason = "empty-input"
            return null
        }
        if (!AppDataService.isAdFilterEnabledSync()) {
            lastSkipReason = "switch-off"
            return null
        }
        val r = rules()
        if (r.offFor(uri.host)) {
            lastSkipReason = "site-off"
            return null
        }
        val res = HlsAdFilter.filter(
            uri.toString(), String(body, StandardCharsets.UTF_8), r
        )
        if (res == null) {
            lastSkipReason = "filter:${HlsAdFilter.lastRejectReason}"
            return null
        }
        lastSkipReason = null
        lastSummary = "${res.removedRuns}runs/${res.removedMs / 1000}s of ${res.totalMs / 1000}s"
        Log.d(TAG, "hit $lastSummary url=${safeUrl(uri.toString())}")
        return res.playlist.toByteArray(StandardCharsets.UTF_8)
    }

    private fun rules(): HlsAdFilter.Rules {
        val cached = rules
        if (cached != null) return cached
        return synchronized(rulesLock) {
            if (rules == null) {
                rules = AdFilterRules.load(AppDataService.getAdFilterPackSync())
            }
            rules!!
        }
    }

    /** 规则包重装（切换开关 / 手动刷新后调用）。 */
    fun reloadRules() = synchronized(rulesLock) { rules = null }

    /** 起播时调用：TTL 内重复调用直接返回。失败一律静默。 */
    fun ensureFresh() {
        val now = System.currentTimeMillis()
        if (now - AppDataService.getAdFilterPullAtSync() < PULL_TTL_MS) return
        if (pulling) return
        val server = AppDataService.getServerUrlSync()
        if (server.isNullOrBlank()) return  // 单机模式：无服务端，用内置规则
        pulling = true
        AppDataService.setAdFilterPullAt(now)   // 防抖：失败也等下一 TTL
        scope.launch {
            try {
                pullMutex.withLock { pull(server) }
            } catch (e: Exception) {
                Log.w(TAG, "ensureFresh fail: ${e.message}")
            } finally {
                pulling = false
            }
        }
    }

    /** 设置页「立即刷新规则」：清 TTL 防抖后重新拉取（异步，完成时间不定）。 */
    fun refreshNow() {
        AppDataService.setAdFilterPullAt(0L)
        reloadRules()
        ensureFresh()
    }

    /**
     * 设置页「立即刷新规则」同步版：在 IO 线程调用，拉取完成（或失败）才返回。
     * 供设置页在协程里等待并即时刷新状态行；失败静默，返回是否拿到新包。
     */
    fun refreshNowSync(): Boolean {
        AppDataService.setAdFilterPullAt(0L)
        reloadRules()
        val server = AppDataService.getServerUrlSync()
        if (server.isNullOrBlank()) return false
        return try {
            pull(server)
            true
        } catch (e: Exception) {
            Log.w(TAG, "refreshNowSync fail: ${e.message}")
            false
        }
    }

    private fun pull(server: String) {
        val base = server.trim().trimEnd('/')
        val version = AppDataService.getAdFilterPackVersionSync()
        // SeleneTV 无 vodsync API Token（late-show-app 用 Bearer），改走 Helios
        // Cookie 鉴权的同形端点 /api/adfilter/rules（契约与 /api/v1 版一致）。
        val url = "$base/api/adfilter/rules?version=$version&channel=stable"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                AppDataService.getCookiesSync()?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "rule pull HTTP $code")
                return
            }
            val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            applyPull(body)
        } catch (e: Exception) {
            Log.w(TAG, "rule pull fail: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /** 解析四态响应：changed=true 且带 rules 才更新，否则保留本地包。 */
    private fun applyPull(body: String) {
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val changed = (root["changed"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
            if (!changed) return
            val rulesObj = root["rules"] as? JsonObject ?: return
            val ver = (root["version"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
                ?: (AppDataService.getAdFilterPackVersionSync() + 1)
            AppDataService.setAdFilterPack(rulesObj.toString(), ver)
            reloadRules()
            Log.d(TAG, "rule pack updated -> v$ver")
        } catch (e: Exception) {
            Log.w(TAG, "applyPull fail: ${e.message}")
        }
    }

    /** 设置页状态行：规则来源 + 版本。 */
    fun statusLine(): String {
        val ver = AppDataService.getAdFilterPackVersionSync()
        val src = if (ver > 0) "服务端规则 v$ver" else "内置规则（尚未拉到服务端包）"
        return "规则：$src"
    }

    private fun safeUrl(url: String): String {
        val cut = url.indexOf('?')
        return if (cut > 0) url.substring(0, cut) else url
    }
}
