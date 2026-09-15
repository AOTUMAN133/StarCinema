package com.embytv.danmu

/** 弹幕单条 */
data class DanmuItem(
    /** 出现时间 ms */
    val timeMs: Long,
    /** 文本 */
    val text: String,
    /** 颜色 ARGB */
    val color: Int = 0xFFFFFFFF.toInt(),
    /** 1=滚动 4=底部 5=顶部 */
    val type: Int = 1
)

/** 弹弹Play 匹配结果 */
data class DanmuMatchResult(
    val episodeId: String,
    val animeTitle: String,
    val episodeTitle: String
)

/** 番剧搜索结果 */
data class DanmuAnime(
    val animeId: String,
    val title: String,
    val episodes: Int = 0
)

/** 番剧剧集 */
data class DanmuEpisode(
    val episodeId: String,
    val title: String
)

/** 弹幕匹配+下载状态 */
sealed class DanmuLoadState {
    object Idle : DanmuLoadState()
    object Loading : DanmuLoadState()
    data class Ready(val count: Int, val source: String) : DanmuLoadState()
    data class Failed(val message: String) : DanmuLoadState()
}

/** 弹幕开关状态 */
enum class DanmuToggle { ON, OFF }