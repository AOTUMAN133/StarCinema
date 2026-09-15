package com.embytv.model

import androidx.recyclerview.widget.RecyclerView

/**
 * 首页行数据模型（参考 AfuseKtV 的 VideoType 设计）
 * 每行自带 adapter，驱动 VideoTypeRecyclerAdapterDiff 渲染
 */
data class VideoType(
    /** 行标题文字（如"播放记录"、"热门"、"电影"等） */
    val typeText: String,
    /** 行内横向滚动列表的适配器 */
    val adapter: RecyclerView.Adapter<*>,
    /** 视频类型标识（如 "Movie", "Series", "Episode"） */
    val videoType: String = "",
    /** 是否可见 */
    val see: Boolean = true,
    /** 库 ID */
    val id: String = "",
    /** 过滤类型 */
    val includeItemTypes: String = "",
    /** 额外数据（Banner 详情等） */
    val detailData: Any? = null,
    /** 标题点击回调（如"查看全部"） */
    val onTitleClick: (() -> Unit)? = null
)