package org.moontechlab.selenetv.service.adfilter

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 去广告规则装载：内置默认 + 后端规则包（[AdFilter] 拉取并持久化）合并。
 *
 * 移植自 late-show-app 的同名实现。设计原则（逐字保留）：
 *  - 规则只是「算法参数 + 高置信模式 + 站点例外」，不是通用 DSL；
 *  - 坏包（JSON 非法 / 字段越界）整体丢弃回退内置，绝不部分采纳；
 *  - 所有数值在装载时钳制到安全区间，规则包不能把算法调成必然误删；
 *  - 未知字段一律忽略，向前兼容。
 *
 * 后端规则包字段见 vodsync/app/adfilter.py；指纹字段（fingerprints）本客户端不实现，忽略。
 */
internal object AdFilterRules {
    private const val TAG = "Selene.AdFilter"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** @param packJson 后端规则包原文（rules 对象的 JSON），空/null = 仅内置规则。 */
    fun load(packJson: String?): HlsAdFilter.Rules {
        val r = HlsAdFilter.Rules()
        if (packJson.isNullOrBlank()) return r
        try {
            val p = json.parseToJsonElement(packJson).jsonObject

            p["thresholds"]?.let { tEl ->
                val t = tEl as? JsonObject ?: return@let
                r.relativeMaxRatio = clamp(dbl(t, "relative_max_ratio", r.relativeMaxRatio), 0.05, 0.95)
                r.absoluteMaxSec = clampL(long_(t, "absolute_max_sec", r.absoluteMaxSec), 10, 1800)
                r.safetyFloor = clamp(dbl(t, "safety_floor", r.safetyFloor), 0.5, 1.0)
                r.safetyFloorHighConfidence = clamp(
                    dbl(t, "safety_floor_high_confidence", r.safetyFloorHighConfidence), 0.4, 1.0)
                r.duplicateMaxRatio = clamp(
                    dbl(t, "duplicate_max_ratio", r.duplicateMaxRatio), 0.3, 0.9)
            }

            (p["url_patterns"] as? JsonArray)?.let { arr ->
                val list = ArrayList<String>()
                for (e in arr) {
                    val s = (e as? JsonPrimitive)?.content?.lowercase()?.trim()
                    if (!s.isNullOrEmpty() && list.size < 64) list.add(s)
                }
                if (list.isNotEmpty()) r.urlPatterns = list   // 空数组视为无效，保留内置
            }

            (p["site_overrides"] as? JsonArray)?.let { arr ->
                for (e in arr) {
                    val o = e as? JsonObject ?: continue
                    val host = str(o, "host")
                    val mode = str(o, "mode")
                    if (host.isNotEmpty() && mode.isNotEmpty() && r.siteMode.size < 256) {
                        r.siteMode[host.lowercase()] = mode.lowercase()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad rule pack -> builtin: ${e.message}")
            return HlsAdFilter.Rules()
        }
        return r
    }

    private fun str(o: JsonObject, k: String): String =
        (o[k] as? JsonPrimitive)?.content?.trim() ?: ""

    private fun dbl(o: JsonObject, k: String, def: Double): Double =
        ((o[k] as? JsonPrimitive)?.doubleOrNull ?: def)

    private fun long_(o: JsonObject, k: String, def: Long): Long =
        ((o[k] as? JsonPrimitive)?.longOrNull ?: def)

    private fun clamp(v: Double, lo: Double, hi: Double): Double = minOf(hi, maxOf(lo, v))
    private fun clampL(v: Long, lo: Long, hi: Long): Long = minOf(hi, maxOf(lo, v))
}
