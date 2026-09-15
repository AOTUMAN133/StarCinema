package com.embytv.player.kernel.impl.mpv

import android.content.Context
import com.embytv.player.kernel.AbstractVideoPlayer
import com.embytv.player.kernel.PlayerFactory

class MpvPlayerFactory : PlayerFactory() {
    override fun createPlayer(context: Context): AbstractVideoPlayer {
        return MpvVideoPlayer(context)
    }
}