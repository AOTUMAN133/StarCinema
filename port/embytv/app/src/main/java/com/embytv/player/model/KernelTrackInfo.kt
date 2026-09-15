package com.embytv.player.model

data class KernelTrackInfo(
    val trackType: Int,
    val id: String,
    val label: String? = null,
    val language: String? = null,
    val sampleMimeType: String? = null,
    val codecs: String? = null,
    val selected: Boolean = false,
    val support: Int = 0
)