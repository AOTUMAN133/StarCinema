package com.embytv.player.model

import android.util.Log

enum class TrackType {
    VIDEO,
    AUDIO,
    SUBTITLE;

    companion object {
        fun fromMedia3Type(type: Int): TrackType? = when (type) {
            androidx.media3.common.C.TRACK_TYPE_VIDEO -> VIDEO
            androidx.media3.common.C.TRACK_TYPE_AUDIO -> AUDIO
            androidx.media3.common.C.TRACK_TYPE_TEXT -> SUBTITLE
            else -> null
        }
    }
}