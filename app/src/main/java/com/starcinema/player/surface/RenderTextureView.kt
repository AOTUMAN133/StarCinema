package com.starcinema.player.surface

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

class RenderTextureView(
    context: Context
) : TextureView(context), InterSurfaceView {

    private var surface: Surface? = null
    private var surfaceSizeListener: ((Int, Int) -> Unit)? = null

    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(tex: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(tex)
                surfaceSizeListener?.invoke(width, height)
            }

            override fun onSurfaceTextureSizeChanged(tex: SurfaceTexture, width: Int, height: Int) {
                surfaceSizeListener?.invoke(width, height)
            }

            override fun onSurfaceTextureDestroyed(tex: SurfaceTexture): Boolean {
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(tex: SurfaceTexture) {}
        }
    }

    override fun getSurface(): Surface = surface ?: throw IllegalStateException("Surface not ready")
    override fun getSurfaceWidth(): Int = width
    override fun getSurfaceHeight(): Int = height

    override fun setSurfaceSizeListener(listener: (Int, Int) -> Unit) {
        surfaceSizeListener = listener
    }

    override fun release() {
        surface?.release()
        surface = null
    }
}