package com.starcinema.player.surface

import android.content.Context
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceView

class RenderSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), InterSurfaceView {

    private var surfaceSizeListener: ((Int, Int) -> Unit)? = null

    init {
        holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                surfaceSizeListener?.invoke(this@RenderSurfaceView.width, this@RenderSurfaceView.height)
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