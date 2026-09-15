package com.embytv.player.kernel

import android.content.Context
import com.embytv.player.kernel.impl.exo.ExoPlayerFactory
import com.embytv.player.kernel.impl.mpv.MpvPlayerFactory
import com.embytv.player.model.PlayerType

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