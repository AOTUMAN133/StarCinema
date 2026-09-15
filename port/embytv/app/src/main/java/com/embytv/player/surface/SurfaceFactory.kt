package com.embytv.player.surface

import android.content.Context
import android.view.Surface

class SurfaceFactory {
    companion object {
        /**
         * Creates the appropriate surface view. For MPV's Dolby Vision OSD surface,
         * a separate SurfaceView is needed — see MpvOsdSurfaceView.
         */
        fun createSurfaceView(context: Context): RenderSurfaceView {
            return RenderSurfaceView(context)
        }

        fun createTextureView(context: Context): RenderTextureView {
            return RenderTextureView(context)
        }
    }
}