package com.embytv.player.kernel.impl.exo

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.embytv.player.kernel.VideoLog
import java.io.EOFException
import java.util.Locale
import kotlin.math.max

/**
 * 杜比视界 Profile 枚举，覆盖 DS One 识别的全部 14 种 profile。
 * 对应 codec 串格式：dvhe/dvh1.XX.* 或 dvhe/dvh1.XX 开头
 */
enum class DoviProfile(val code: Int) {
    P2(2), P10(10), P22(22), P30(30), P42(42), P50(50),
    P61(61), P76(76), P81(81), P82(82), P83(83), P84(84), P85(85), P92(92),
    P5(5), P7(7), P8(8),
    UNKNOWN(-1);

    companion object {
        fun fromCodecs(codecs: String?): DoviProfile {
            val c = codecs.orEmpty().lowercase(Locale.US)
            for (p in entries) {
                if (p == UNKNOWN) continue
                val codeStr = p.code.toString().padStart(2, '0')
                // 匹配 dvhe.05.xx、dvh1.05.xx、dvhe.05 等
                if (c.contains("dvhe.$codeStr.") || c.contains("dvh1.$codeStr.") ||
                    (c == "dvhe.$codeStr") || (c == "dvh1.$codeStr")
                ) return p
            }
            return UNKNOWN
        }

        /** 需要保留原始 DV codec 标记的 profile（让系统处理色空间） */
        fun needsNativeDvCodec(profile: DoviProfile): Boolean = when (profile) {
            P5, P10, P22, P30, P42, P50, P61, P76, P92 -> true
            else -> false
        }

        /** 可以 remap 为 H265 的 profile（兼容 HDR10 处理） */
        fun canRemapToHevc(profile: DoviProfile): Boolean = when (profile) {
            P7, P8, P81, P82, P83, P84, P85 -> true
            P5, P10, P22, P30, P42, P50, P61, P76, P92 -> false
            else -> true
        }
    }
}

/**
 * Wraps progressive extractors and maps Dolby Vision video tracks to HEVC.
 *
 * Profile 5 and Profile 8 keep the former format-only compatibility path. Profile 7 additionally
 * stages every access unit, removes RPU and enhancement-layer NAL units, then submits the remaining
 * base-layer HEVC sample to the downstream SampleQueue.
 */
internal class DolbyVisionHevcRemapExtractor(
    private val delegate: Extractor
) : Extractor {

    private val trackOutputs = mutableListOf<RemappingTrackOutput>()

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        trackOutputs.clear()
        delegate.init(RemappingExtractorOutput(output))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
        delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        trackOutputs.forEach { it.resetBuffers() }
        delegate.seek(position, timeUs)
    }

    override fun release() {
        trackOutputs.clear()
        delegate.release()
    }

    private inner class RemappingExtractorOutput(
        private val wrapped: ExtractorOutput
    ) : ExtractorOutput {

        override fun track(id: Int, type: Int): TrackOutput {
            return RemappingTrackOutput(wrapped.track(id, type)).also(trackOutputs::add)
        }

        override fun endTracks() {
            wrapped.endTracks()
        }

        override fun seekMap(seekMap: SeekMap) {
            wrapped.seekMap(seekMap)
        }
    }

    private inner class RemappingTrackOutput(
        private val wrapped: TrackOutput
    ) : TrackOutput {

        private val staging = ReusableBuffer(INITIAL_BUFFER_CAPACITY)
        private val filtered = ReusableBuffer(INITIAL_BUFFER_CAPACITY)
        private val outputArray = ParsableByteArray()

        private var filterProfile7 = false
        private var nalLengthFieldLength = DEFAULT_NAL_LENGTH_FIELD_LENGTH
        private var hasLoggedFilterResult = false

        override fun durationUs(durationUs: Long) {
            wrapped.durationUs(durationUs)
        }

        override fun format(format: Format) {
            resetBuffers()
            filterProfile7 = isDolbyVisionProfile7(format)
            nalLengthFieldLength = readNalLengthFieldLength(format)
            hasLoggedFilterResult = false

            val remappedFormat = if (filterProfile7) {
                format.buildUpon()
                    .setSampleMimeType(MimeTypes.VIDEO_H265)
                    .setCodecs(null)
                    .setId(markRemappedProfile7Id(format.id))
                    .build()
            } else {
                remapDolbyVision(format)
            }
            wrapped.format(remappedFormat)
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            if (!filterProfile7 || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
                return wrapped.sampleData(input, length, allowEndOfInput, sampleDataPart)
            }

            staging.ensureCapacity(staging.size + length)
            val bytesRead = input.read(staging.data, staging.size, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) {
                    return C.RESULT_END_OF_INPUT
                }
                throw EOFException("Unexpected end of input while staging P7 sample")
            }
            if (bytesRead > 0) {
                staging.size += bytesRead
            }
            return bytesRead
        }

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: Int
        ) {
            if (!filterProfile7 || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
                wrapped.sampleData(data, length, sampleDataPart)
                return
            }

            // Seek/resume path: MatroskaExtractor may pass length larger than
            // bytesLeft() for the final chunk. Clamp instead of throwing —
            // a thrown IllegalArgumentException surfaces as
            // ERROR_CODE_FAILED_RUNTIME_CHECK on P7 seek-resume.
            val available = data.bytesLeft()
            val toCopy = if (length <= available) length else available
            staging.append(data.data, data.position, toCopy)
            data.skipBytes(toCopy)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            if (!filterProfile7) {
                wrapped.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                return
            }

            val canFilter = cryptoData == null &&
                (flags and C.BUFFER_FLAG_ENCRYPTED) == 0 &&
                offset == 0 &&
                size == staging.size

            if (!canFilter) {
                flushUnmodifiedSample(timeUs, flags, size, offset, cryptoData)
                return
            }

            filtered.clear()
            var parsed = filterLengthPrefixed(staging.data, staging.size, filtered)
            if (!parsed) {
                filtered.clear()
                parsed = filterAnnexB(staging.data, staging.size, filtered)
            }

            if (!parsed || filtered.size == 0) {
                flushUnmodifiedSample(timeUs, flags, size, offset, cryptoData)
                return
            }

            if (!hasLoggedFilterResult) {
                hasLoggedFilterResult = true
                VideoLog.i(
                    "EXO P7 BL filter active: sampleBytes=$size -> ${filtered.size}, " +
                        "dropNalTypes=62/63 and nonZeroLayerId"
                )
            }

            outputArray.reset(filtered.data, filtered.size)
            wrapped.sampleData(
                outputArray,
                filtered.size,
                TrackOutput.SAMPLE_DATA_PART_MAIN
            )
            wrapped.sampleMetadata(timeUs, flags, filtered.size, 0, null)
            resetBuffers()
        }

        fun resetBuffers() {
            staging.clear()
            filtered.clear()
            outputArray.reset(0)
        }

        private fun flushUnmodifiedSample(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            if (staging.size > 0) {
                outputArray.reset(staging.data, staging.size)
                wrapped.sampleData(
                    outputArray,
                    staging.size,
                    TrackOutput.SAMPLE_DATA_PART_MAIN
                )
            }
            wrapped.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            resetBuffers()
        }

        private fun filterLengthPrefixed(
            source: ByteArray,
            sourceSize: Int,
            output: ReusableBuffer
        ): Boolean {
            output.clear()
            if (filterLengthPrefixed(source, sourceSize, nalLengthFieldLength, output)) {
                return true
            }
            for (lengthFieldSize in 4 downTo 1) {
                if (lengthFieldSize == nalLengthFieldLength) continue
                output.clear()
                if (filterLengthPrefixed(source, sourceSize, lengthFieldSize, output)) {
                    return true
                }
            }
            return false
        }

        private fun filterLengthPrefixed(
            source: ByteArray,
            sourceSize: Int,
            lengthFieldSize: Int,
            output: ReusableBuffer
        ): Boolean {
            var position = 0
            var nalCount = 0

            while (position < sourceSize) {
                if (sourceSize - position < lengthFieldSize) {
                    return false
                }

                var nalSize = 0
                for (index in 0 until lengthFieldSize) {
                    nalSize = (nalSize shl 8) or (source[position + index].toInt() and 0xFF)
                }
                position += lengthFieldSize

                if (nalSize < HEVC_NAL_HEADER_SIZE || nalSize > sourceSize - position ||
                    !isPlausibleHevcHeader(source, position)
                ) {
                    return false
                }

                if (!shouldDropNal(source, position)) {
                    output.appendLength(lengthFieldSize, nalSize)
                    output.append(source, position, nalSize)
                }

                position += nalSize
                nalCount++
            }

            return position == sourceSize && nalCount > 0
        }

        private fun filterAnnexB(
            source: ByteArray,
            sourceSize: Int,
            output: ReusableBuffer
        ): Boolean {
            var start = findStartCode(source, 0, sourceSize)
            if (start < 0) {
                return false
            }

            if (start > 0) {
                output.append(source, 0, start)
            }

            var nalCount = 0
            while (start >= 0) {
                val prefixSize = startCodeSizeAt(source, start, sourceSize)
                val nalStart = start + prefixSize
                val nextStart = findStartCode(source, nalStart, sourceSize)
                val nalEnd = if (nextStart >= 0) nextStart else sourceSize

                if (nalEnd - nalStart < HEVC_NAL_HEADER_SIZE ||
                    !isPlausibleHevcHeader(source, nalStart)
                ) {
                    return false
                }

                if (!shouldDropNal(source, nalStart)) {
                    output.append(source, start, prefixSize)
                    output.append(source, nalStart, nalEnd - nalStart)
                }

                nalCount++
                start = nextStart
            }

            return nalCount > 0
        }

        private fun shouldDropNal(source: ByteArray, headerOffset: Int): Boolean {
            val first = source[headerOffset].toInt() and 0xFF
            val second = source[headerOffset + 1].toInt() and 0xFF
            val nalUnitType = (first and 0x7E) ushr 1
            val layerId = ((first and 0x01) shl 5) or ((second and 0xF8) ushr 3)

            return nalUnitType == DOLBY_VISION_RPU_NAL_TYPE ||
                nalUnitType == DOLBY_VISION_EL_NAL_TYPE ||
                layerId > 0
        }

        private fun isPlausibleHevcHeader(source: ByteArray, headerOffset: Int): Boolean {
            val first = source[headerOffset].toInt() and 0xFF
            val second = source[headerOffset + 1].toInt() and 0xFF
            return (first and 0x80) == 0 && (second and 0x07) != 0
        }

        private fun findStartCode(source: ByteArray, from: Int, end: Int): Int {
            var index = from.coerceAtLeast(0)
            while (index + 2 < end) {
                if (source[index] == 0.toByte() && source[index + 1] == 0.toByte()) {
                    if (index + 3 < end &&
                        source[index + 2] == 0.toByte() && source[index + 3] == 1.toByte()
                    ) {
                        return index
                    }
                    if (source[index + 2] == 1.toByte()) {
                        return index
                    }
                }
                index++
            }
            return -1
        }

        private fun startCodeSizeAt(source: ByteArray, offset: Int, end: Int): Int {
            return if (offset + 3 < end &&
                source[offset] == 0.toByte() && source[offset + 1] == 0.toByte() &&
                source[offset + 2] == 0.toByte() && source[offset + 3] == 1.toByte()
            ) {
                4
            } else {
                3
            }
        }

        private fun readNalLengthFieldLength(format: Format): Int {
            val initializationData = format.initializationData.firstOrNull() ?: return 4
            if (initializationData.size < 22) {
                return 4
            }
            return ((initializationData[21].toInt() and 0x03) + 1).coerceIn(1, 4)
        }
    }

    private class ReusableBuffer(initialCapacity: Int) {
        var data = ByteArray(max(1, initialCapacity))
        var size = 0

        fun ensureCapacity(required: Int) {
            if (required <= data.size) {
                return
            }
            var newCapacity = data.size
            while (newCapacity < required) {
                newCapacity = newCapacity shl 1
            }
            data = data.copyOf(newCapacity)
        }

        fun append(source: ByteArray, offset: Int, length: Int) {
            if (length <= 0) {
                return
            }
            ensureCapacity(size + length)
            System.arraycopy(source, offset, data, size, length)
            size += length
        }

        fun appendLength(lengthFieldSize: Int, value: Int) {
            ensureCapacity(size + lengthFieldSize)
            for (index in lengthFieldSize - 1 downTo 0) {
                data[size++] = (value ushr (index * 8)).toByte()
            }
        }

        fun clear() {
            size = 0
        }
    }

    companion object {
        fun remapDolbyVision(format: Format): Format {
            val mime = format.sampleMimeType
            if (mime == null || !mime.startsWith(MimeTypes.VIDEO_DOLBY_VISION)) {
                return format
            }
            val profile = DoviProfile.fromCodecs(format.codecs)
            // 需要保留原生 DV codec 标记的 profile（系统处理色空间）
            if (DoviProfile.needsNativeDvCodec(profile)) {
                return format
            }
            // P7 由 isDolbyVisionProfile7 独立处理（剥离 RPU/EL）→ remap 为 H265
            // P8/P81~P85：兼容 HDR10，直接 remap
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(remapDolbyVisionCodecs(format.codecs))
                .build()
        }

        fun isRemappedProfile7(format: Format): Boolean =
            format.id?.contains(P7_FORMAT_ID_MARKER) == true

        private fun markRemappedProfile7Id(id: String?): String {
            if (id?.contains(P7_FORMAT_ID_MARKER) == true) {
                return id
            }
            return if (id.isNullOrEmpty()) {
                P7_FORMAT_ID_MARKER
            } else {
                "$id$P7_FORMAT_ID_MARKER"
            }
        }

        private fun isDolbyVisionProfile7(format: Format): Boolean {
            if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) {
                return false
            }
            return DoviProfile.fromCodecs(format.codecs) == DoviProfile.P7
        }

        private fun remapDolbyVisionCodecs(codecs: String?): String? {
            if (codecs.isNullOrBlank()) {
                return null
            }
            val value = codecs.trim()
            if (!value.startsWith("dvhe") && !value.startsWith("dvh1")) {
                return codecs
            }
            val parts = value.split(".")
            val profile = parts.getOrNull(1)?.toIntOrNull()
            val level = parts.getOrNull(2)?.toIntOrNull()
            return if (profile != null && level != null) {
                "hvc1.$profile.$level"
            } else {
                "hvc1"
            }
        }

        private const val INITIAL_BUFFER_CAPACITY = 256 * 1024
        private const val DEFAULT_NAL_LENGTH_FIELD_LENGTH = 4
        private const val HEVC_NAL_HEADER_SIZE = 2
        private const val DOLBY_VISION_RPU_NAL_TYPE = 62
        private const val DOLBY_VISION_EL_NAL_TYPE = 63
        private const val P7_FORMAT_ID_MARKER = ".dandan.p7-hevc"
    }
}