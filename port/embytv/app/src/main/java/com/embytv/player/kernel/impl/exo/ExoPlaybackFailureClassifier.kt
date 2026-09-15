package com.embytv.player.kernel.impl.exo

import androidx.media3.common.PlaybackException
import com.embytv.player.model.PlaybackFailure
import com.embytv.player.model.PlaybackFailureCategory

object ExoPlaybackFailureClassifier {
    fun classify(error: PlaybackException): PlaybackFailure {
        val category: PlaybackFailureCategory = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
            PlaybackException.ERROR_CODE_DISCONNECTED -> PlaybackFailureCategory.REMOTE_ERROR

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                PlaybackFailureCategory.REMOTE_ERROR

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                PlaybackFailureCategory.FORMAT_UNSUPPORTED

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                PlaybackFailureCategory.DECODER_INIT

            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                PlaybackFailureCategory.DECODER_ERROR

            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ->
                PlaybackFailureCategory.DECODER_ERROR

            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
                PlaybackFailureCategory.UNKNOWN

            else -> {
                if (error.cause?.let { c ->
                        c.javaClass.name.contains("MediaCodec", ignoreCase = true) ||
                            c.javaClass.name.contains("Decoder", ignoreCase = true)
                    } == true
                ) {
                    PlaybackFailureCategory.DECODER_ERROR
                } else {
                    PlaybackFailureCategory.UNKNOWN
                }
            }
        }

        return PlaybackFailure(
            category = category,
            message = "${error.errorCodeName}: ${error.message ?: ""}",
            code = error.errorCode,
            cause = error
        )
    }
}