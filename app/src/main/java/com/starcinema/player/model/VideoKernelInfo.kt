package com.starcinema.player.model

/** 播放内核信息（右上角显示用） */
data class VideoKernelInfo(
    /** 解码方式：硬解 / 软解 / 未知 */
    val decodeMode: String = "未知",
    /** 视频格式简称：DV P5 / DV P7 / HDR10 / HLG / SDR / 8K / 4K / 1080P 等 */
    val videoFormat: String = "",
    /** 编码简称：HEVC / AV1 / AVC / VP9 等 */
    val codec: String = "",
    /** 下载缓冲速度 KB/s */
    val bufferSpeedKBps: Int = 0,
    /** 缓存大小 MB */
    val cacheSizeMB: Int = 0,
    /** 视频帧率 */
    val fps: Float = 0f
)