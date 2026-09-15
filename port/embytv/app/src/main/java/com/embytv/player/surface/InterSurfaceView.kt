package com.embytv.player.surface

import android.content.Context
import android.view.Surface

interface InterSurfaceView {
    fun getSurface(): Surface
    fun getSurfaceWidth(): Int
    fun getSurfaceHeight(): Int
    fun setSurfaceSizeListener(listener: (Int, Int) -> Unit)
    fun release()
}