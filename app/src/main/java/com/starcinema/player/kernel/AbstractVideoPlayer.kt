package com.starcinema.player.kernel

import android.graphics.Point
import android.view.Surface
import com.starcinema.player.model.TrackType
import com.starcinema.player.model.VideoKernelInfo
import com.starcinema.player.model.VideoTrackBean

/**
 * 播放器内核抽象基类。EXO 与 MPV 均实现此接口，由 KernelCoordinator 负责切换。
 */
abstract class AbstractVideoPlayer : InterVideoTrack {

    protected lateinit var mPlayerEventListener: VideoPlayerEventListener

    fun setPlayerEventListener(listener: VideoPlayerEventListener) {
        mPlayerEventListener = listener
    }

    abstract fun initPlayer()

    abstract fun setOptions()

    abstract fun setDataSource(path: String, headers: Map<String, String>? = null)

    open fun setDurationHint(durationMs: Long) {}

    abstract fun setSurface(surface: Surface)

    /** 当前是否有有效的渲染 surface（后台恢复判断用） */
    open fun hasValidSurface(): Boolean = false

    open fun setSurfaceSize(width: Int, height: Int) {}

    open fun setOsdSurface(surface: Surface?) {}

    open fun setOsdSurfaceSize(width: Int, height: Int) {}

    open fun clearSurface() {}

    abstract fun prepareAsync()

    abstract fun start()

    abstract fun pause()

    abstract fun stop()

    abstract fun reset()

    abstract fun release()

    abstract fun seekTo(timeMs: Long)

    abstract fun setSpeed(speed: Float)

    abstract fun setVolume(leftVolume: Float, rightVolume: Float)

    abstract fun setLooping(isLooping: Boolean)

    abstract fun setSubtitleOffset(offsetMs: Long)

    abstract fun setAspectRatio(ratio: String)

    abstract fun isPlaying(): Boolean

    abstract fun getCurrentPosition(): Long

    abstract fun getDuration(): Long

    abstract fun getSpeed(): Float

    abstract fun getVideoSize(): Point

    abstract fun getBufferedPercentage(): Int

    abstract fun getTcpSpeed(): Long

    /** 播放内核信息（硬解/软解 + 视频格式简称），右上角显示用 */
    open fun getKernelInfo(): VideoKernelInfo = VideoKernelInfo()

    /** 设置着色器配置（MPV 专用） */
    open fun setShaderConfig(profile: Int) {}

    /** 运行时字幕大小（MPV 用 setPropertyString sub-font-size，EXO 空） */
    open fun setSubtitleSize(size: Int) {}
    /** 运行时字幕位置（MPV 用 setPropertyString sub-pos，EXO 空） */
    open fun setSubtitlePosition(pos: Int) {}

    /** 捏合缩放比例 (1.0=原始, >1.0=放大, <1.0=缩小) */
    open fun setZoom(scale: Float) {}

    /** 音频直通模式（passthrough raw bitstream to HDMI/SPDIF） */
    open fun setAudioPassthrough(enabled: Boolean) {}

    /** 帧率匹配（切换显示刷新率匹配视频帧率） */
    open fun setFrameRateMatch(enabled: Boolean) {}

    /** 设置外挂字幕 URL 列表（每个元素 = (url, mimeType)） */
    open fun setSubtitleUrls(urls: List<Pair<String, String>>) {}

    /** ASS 字幕拓展：把 AssSubtitleView 挂到指定容器（仅 EXO 内核实现） */
    open fun attachAssSubtitleView(container: android.view.ViewGroup?) {}

    /** 播放器解码模式切换（MPV: HW/HW+/SW 循环, EXO 软硬解） */
    open fun setHwdecMode(index: Int) {}

    override fun supportAddTrack(type: TrackType): Boolean = false

    override fun addTrack(track: VideoTrackBean): Boolean = false
}