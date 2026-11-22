package org.moontechlab.selenetv.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 18+ 源站标记的本地注册表。
 *
 * 数据来源：服务端 /api/search/resources（每个源站带 adult 标记），
 * 拉取后同步缓存到本地 SharedPreferences（CSV），供无网/离线时仍可过滤。
 *
 * 使用方（统一走 [shouldFilterOut]）：
 *  - SearchService：本地直连搜索跳过 18+ 源；服务端聚合搜索丢弃 18+ 源结果
 *  - HomeTab：继续观看 / 我的收藏 过滤 18+ 源条目
 *
 * 过滤条件 = 「显示 18+ 内容」开关关闭 且 源站 key 命中 18+ 集合。
 * 开关开启时本类零开销直通（仅一次 Set 查找短路）。
 */
object AdultSourceFilter {
    private const val TAG = "AdultSourceFilter"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var adultKeys: Set<String> = AppDataService.getAdultSourceKeysSync()

    @Volatile
    private var lastSyncAt: Long = 0L

    private val minSyncIntervalMs = 10 * 60 * 1000L  // 防抖：10 分钟内不重复拉取

    /**
     * 从服务端同步 18+ 源站标记到本地（异步，失败静默）。
     * 触发时机：应用启动 / 每次发起搜索 / 进入首页。
     */
    fun refreshAsync() {
        scope.launch {
            try {
                refreshSync()
            } catch (e: Exception) {
                Log.w(TAG, "同步 18+ 源站标记失败: ${e.message}")
            }
        }
    }

    /**
     * 同步版：在 IO 协程里调用，成功返回 true。
     * 仅服务端模式生效；本地模式无 18+ 概念，跳过。
     */
    suspend fun refreshSync(): Boolean {
        if (AppDataService.isLocalModeSync() || !AppDataService.isLoggedInSync()) return false
        val now = System.currentTimeMillis()
        if (lastSyncAt != 0L && now - lastSyncAt < minSyncIntervalMs) return true
        return withContext(Dispatchers.IO) {
            try {
                val resources = ApiService.getSearchResources()
                val keys = resources.filter { it.adult }.map { it.key }.toSet()
                adultKeys = keys
                AppDataService.setAdultSourceKeysSync(keys)
                lastSyncAt = System.currentTimeMillis()
                Log.d(TAG, "18+ 源站标记已同步: ${keys.size} 个源")
                true
            } catch (e: Exception) {
                Log.w(TAG, "拉取源站标记失败（沿用本地缓存 ${adultKeys.size} 个）: ${e.message}")
                false
            }
        }
    }

    /** 该源站是否为 18+ 源（依据本地缓存的标记集合）。 */
    fun isAdultSource(sourceKey: String): Boolean = adultKeys.contains(sourceKey)

    /**
     * 统一过滤判定：true = 该源站内容应在当前设置下被隐藏。
     * 开关开启时短路返回 false，调用方零改动成本。
     */
    fun shouldFilterOut(sourceKey: String?): Boolean {
        if (AppDataService.isAdultContentEnabledSync()) return false
        if (sourceKey.isNullOrEmpty()) return false
        return adultKeys.contains(sourceKey)
    }
}
