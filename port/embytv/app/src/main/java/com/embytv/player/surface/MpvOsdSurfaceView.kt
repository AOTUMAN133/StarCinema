package com.embytv.player.surface

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceView

/**
 * MPV 独立 OSD 渲染 Surface。fongmi mpv 的 Dolby Vision 直通输出
 * （vo=mediacodec_embed）要求 android-osd-wid 指向一个与视频 Surface
 * 不同的真实 Surface，否则 OSD 的 EGL 配置会污染视频 Surface 导致
 * MediaCodec configure 失败。
 */
class MpvOsdSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), InterSurfaceView {

    private var surfaceSizeListener: ((Int, Int) -> Unit)? = null

    init {
        holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                surfaceSizeListener?.invoke(this@MpvOsdSurfaceView.width, this@MpvOsdSurfaceView.height)
            }

            override fun surfaceChanged(
                holder: android.view.SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                surfaceSizeListener?.invoke(width, height)
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
        })
    }

    override fun getSurface(): Surface = holder.surface
    override fun getSurfaceWidth(): Int = width
    override fun getSurfaceHeight(): Int = height

    override fun setSurfaceSizeListener(listener: (Int, Int) -> Unit) {
        surfaceSizeListener = listener
    }

    override fun release() {}
}