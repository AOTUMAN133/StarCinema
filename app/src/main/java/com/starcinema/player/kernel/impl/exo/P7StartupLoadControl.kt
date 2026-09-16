package com.starcinema.player.kernel.impl.exo

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.starcinema.player.kernel.VideoLog

/** Uses the normal load-control policy, with an extra initial reserve for remapped DV Profile 7. */
internal class P7StartupLoadControl(
    private val profile7StartupBufferUs: Long
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    MIN_BUFFER_MS,
    MIN_BUFFER_MS,
    MAX_BUFFER_MS,
    MAX_BUFFER_MS,
    BUFFER_FOR_PLAYBACK_MS,
    BUFFER_FOR_PLAYBACK_MS,
    BUFFER_AFTER_REBUFFER_MS,
    BUFFER_AFTER_REBUFFER_MS,
    TARGET_BUFFER_BYTES,
    true,   // prioritizeTimeOverSizeThresholds：默认 false，seek 后更快起播（Media3 DefaultLoadControl L117）
    false,
    0,
    false
) {

    private val period = Timeline.Period()
    private var profile7Selected = false
    private var hasLoggedHold = false
    private var hasLoggedRelease = false

    override fun onPrepared(playerId: PlayerId) {
        profile7Selected = false
        hasLoggedHold = false
        hasLoggedRelease = false
        super.onPrepared(playerId)
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>
    ) {
        super.onTracksSelected(parameters, trackGroups, trackSelections)
        val selected = trackSelections.any { selection ->
            selection != null && (0 until selection.length()).any { formatIndex ->
                DolbyVisionHevcRemapExtractor.isRemappedProfile7(selection.getFormat(formatIndex))
            }
        }
        if (profile7Selected != selected) {
            profile7Selected = selected
            hasLoggedHold = false
            hasLoggedRelease = false
            VideoLog.i("EXO P7 startup prebuffer selected=$selected")
        }
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val defaultReady = super.shouldStartPlayback(parameters)
        if (!profile7Selected || parameters.rebuffering) {
            return defaultReady
        }

        val bufferedPlayoutUs = Util.getPlayoutDurationForMediaDuration(
            parameters.bufferedDurationUs,
            parameters.playbackSpeed
        )
        val profile7Ready = bufferedPlayoutUs >= profile7StartupBufferUs || isFullyBuffered(parameters)
        if (!profile7Ready && !hasLoggedHold) {
            hasLoggedHold = true
            VideoLog.i(
                "EXO P7 startup prebuffer hold: bufferedMs=${bufferedPlayoutUs / 1_000} " +
                    "targetMs=${profile7StartupBufferUs / 1_000}"
            )
        } else if (profile7Ready && !hasLoggedRelease) {
            hasLoggedRelease = true
            VideoLog.i("EXO P7 startup prebuffer ready: bufferedMs=${bufferedPlayoutUs / 1_000}")
        }
        return defaultReady && profile7Ready
    }

    private fun isFullyBuffered(parameters: LoadControl.Parameters): Boolean {
        val periodIndex = parameters.timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (periodIndex == C.INDEX_UNSET) {
            return false
        }
        val durationUs = parameters.timeline.getPeriod(periodIndex, period).durationUs
        return durationUs != C.TIME_UNSET &&
            parameters.playbackPositionUs + parameters.bufferedDurationUs >=
            durationUs - FULLY_BUFFERED_TOLERANCE_US
    }

    private companion object {
        // 参考 AfuseKtV: bufferForPlaybackMs=1000, bufferForPlaybackAfterRebufferMs=2000
        // 大幅降低缓冲阈值实现 seek 秒播（非 P7 内容）
        const val MIN_BUFFER_MS = 5_000
        const val MAX_BUFFER_MS = 30_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_000
        const val BUFFER_AFTER_REBUFFER_MS = 2_000
        const val TARGET_BUFFER_BYTES = 200 * 1024 * 1024
        const val FULLY_BUFFERED_TOLERANCE_US = 100_000L
    }
}