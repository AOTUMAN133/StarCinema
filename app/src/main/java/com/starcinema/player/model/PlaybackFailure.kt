package com.starcinema.player.model

enum class PlaybackFailureCategory {
    /** 解码器初始化失败 */
    DECODER_INIT,
    /** 网络/IO 错误 */
    IO_ERROR,
    /** 格式不支持 */
    FORMAT_UNSUPPORTED,
    /** 解码器不支持 */
    DECODER_NOT_SUPPORTED,
    /** 解码器异常 */
    DECODER_ERROR,
    /** 远程/服务端错误 */
    REMOTE_ERROR,
    /** 未知 */
    UNKNOWN
}

data class PlaybackFailure(
    val category: PlaybackFailureCategory,
    val message: String,
    val code: Int = -1,
    val cause: Throwable? = null
) {
    /** 是否允许自动降级到另一个内核（网络错误不值得降级） */
    fun allowFallback(): Boolean = category != PlaybackFailureCategory.IO_ERROR
}