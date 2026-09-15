package com.starcinema.player.kernel.impl.mpv

import android.content.Context
import com.starcinema.player.kernel.AbstractVideoPlayer
import com.starcinema.player.kernel.PlayerFactory

class MpvPlayerFactory : PlayerFactory() {
    override fun createPlayer(context: Context): AbstractVideoPlayer {
        return MpvVideoPlayer(context)
    }
}