package com.embytv.player.model

data class VideoTrackBean(
    val id: String,
    val name: String,
    val type: TrackType,
    val selected: Boolean = false,
    val language: String? = null,
    val mimeType: String? = null,
    val codec: String? = null,
    val trackResource: String? = null,
    val supported: Boolean = true
)