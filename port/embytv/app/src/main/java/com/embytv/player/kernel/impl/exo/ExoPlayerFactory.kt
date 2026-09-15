package com.embytv.player.kernel.impl.exo

import android.content.Context
import com.embytv.player.kernel.AbstractVideoPlayer
import com.embytv.player.kernel.PlayerFactory

class ExoPlayerFactory : PlayerFactory() {
    override fun createPlayer(context: Context): AbstractVideoPlayer {
        return ExoVideoPlayer(context)
    }
}