package com.starcinema.player.kernel

import android.content.Context
import com.starcinema.player.kernel.impl.exo.ExoPlayerFactory
import com.starcinema.player.kernel.impl.mpv.MpvPlayerFactory
import com.starcinema.player.model.PlayerType

abstract class PlayerFactory {

    companion object {
        fun getFactory(playerType: PlayerType): PlayerFactory {
            return when (playerType) {
                PlayerType.TYPE_EXO_PLAYER -> ExoPlayerFactory()
                PlayerType.TYPE_MPV_PLAYER -> MpvPlayerFactory()
            }
        }
    }

    abstract fun createPlayer(context: Context): AbstractVideoPlayer
}