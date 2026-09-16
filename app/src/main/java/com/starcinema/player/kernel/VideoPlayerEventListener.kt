package com.starcinema.player.kernel

import com.starcinema.player.model.KernelTrackReport
import com.starcinema.player.model.PlaybackFailure

interface VideoPlayerEventListener {
    fun onPrepared()

    fun onError(failure: PlaybackFailure)

    fun onCompletion()

    fun onVideoSizeChange(width: Int, height: Int)

    fun onInfo(what: Int, extra: Int)

    fun onSubtitleText(subtitle: String?)

    /** 位图字幕（PGS 等内嵌图形字幕），bitmap 为 null 表示清除 */
    fun onSubtitleBitmap(bitmap: android.graphics.Bitmap?) {}

    fun onTracksChanged(report: KernelTrackReport)
}