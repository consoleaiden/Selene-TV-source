package org.moontechlab.selenetv.model

/**
 * [18+ Tab] 服务端 18+ 频道相关数据模型。
 *
 * 契约对应 vodsync selenetv 路由：
 *   GET /api/adult/permission            -> AdultPermission
 *   GET /api/adult/sources               -> List<AdultSource>
 *   GET /api/adult/categories?source=    -> List<AdultCategory>
 *   GET /api/adult/videos?source=&type_id=&page=&size= -> AdultVideoPage
 */
data class AdultPermission(
    val allowed: Boolean
)

/**
 * 18+ 源站（形状与 SearchResource 对齐：key/api/name/detail）。
 */
data class AdultSource(
    val key: String,
    val name: String,
    val detail: String
)

/**
 * 单源分类（type_id 即筛选 key）。
 */
data class AdultCategory(
    val typeId: Int,
    val typeName: String
)

/**
 * 视频网格瘦行（不含 content/play_url，播放信息走 /api/detail 按需获取）。
 */
data class AdultVideo(
    val vodId: Long,
    val typeId: Int,
    val typeName: String,
    val name: String,
    val pic: String,
    val remarks: String,
    val vodYear: String
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
data class AdultVideoPage(
    val list: List<AdultVideo>,
    val total: Int,
    val page: Int,
    val size: Int
)
