package com.starcinema.player.kernel.impl.exo

import android.content.Context
import com.starcinema.player.kernel.AbstractVideoPlayer
import com.starcinema.player.kernel.PlayerFactory

class ExoPlayerFactory : PlayerFactory() {
    override fun createPlayer(context: Context): AbstractVideoPlayer {
        return ExoVideoPlayer(context)
    }
}