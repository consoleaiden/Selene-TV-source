package org.moontechlab.selenetv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [18+ Tab] 服务端 18+ 频道相关数据模型。
 *
 * 契约对应 vodsync selenetv 路由：
 *   GET /api/adult/permission            -> AdultPermission
 *   GET /api/adult/sources               -> List<AdultSource>
 *   GET /api/adult/categories?source=    -> List<AdultCategory>
 *   GET /api/adult/videos?source=&type_id=&page=&size= -> AdultVideoPage
 */
@Serializable
data class AdultPermission(
    val allowed: Boolean
)

/**
 * 18+ 源站（形状与 SearchResource 对齐：key/api/name/detail）。
 */
@Serializable
data class AdultSource(
    val key: String,
    val name: String,
    val detail: String
)

/**
 * 单源分类（type_id 即筛选 key，服务端为 snake_case）。
 */
@Serializable
data class AdultCategory(
    @SerialName("type_id") val typeId: Int,
    @SerialName("type_name") val typeName: String
)

/**
 * 视频网格瘦行（不含 content/play_url，播放信息走 /api/detail 按需获取）。
 */
@Serializable
data class AdultVideo(
    @SerialName("vod_id") val vodId: Long,
    @SerialName("type_id") val typeId: Int,
    @SerialName("type_name") val typeName: String,
    val name: String,
    val pic: String,
    val remarks: String,
    @SerialName("vod_year") val vodYear: String
) {
    /** 转成通用卡片模型：source+vodId 走既有详情页（History 入口）。 */
    fun toVideoInfo(sourceKey: String, sourceName: String): VideoInfo = VideoInfo(
        id = vodId.toString(),
        source = sourceKey,
        title = name,
        sourceName = sourceName,
        year = vodYear,
        cover = pic,
        index = 1,
        totalEpisodes = 0,
        playTime = 0,
        totalTime = 0,
        saveTime = 0,
        searchTitle = name
    )
}

/**
 * 分页响应。
 */
@Serializable
data class AdultVideoPage(
    val list: List<AdultVideo>,
    val total: Int,
    val page: Int,
    val size: Int
)
