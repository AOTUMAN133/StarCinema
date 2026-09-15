package com.starcinema.player.kernel

import com.starcinema.player.model.TrackType
import com.starcinema.player.model.VideoTrackBean

interface InterVideoTrack {
    fun supportAddTrack(type: TrackType): Boolean
    fun addTrack(track: VideoTrackBean): Boolean
    fun getTracks(type: TrackType): List<VideoTrackBean>
    fun selectTrack(track: VideoTrackBean)
    fun deselectTrack(type: TrackType)
}