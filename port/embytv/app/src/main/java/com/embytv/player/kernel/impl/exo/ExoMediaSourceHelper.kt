package com.embytv.player.kernel.impl.exo

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import java.util.Locale

object ExoMediaSourceHelper {

    private var mUserAgent: String = "EmbyTV/0.1.0"

    /**
     * Dolby Vision P5 compatibility: map video/dolby-vision tracks to video/hevc
     * so devices without a video/dolby-vision decoder can hardware-decode single-layer P5.
     * Profile 7 additionally strips RPU and EL NAL units (handled in the Extractor).
     */
    val dolbyVisionRemapExtractorsFactory: ExtractorsFactory =
        object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> =
                DefaultExtractorsFactory()
                    .createExtractors()
                    .map { DolbyVisionHevcRemapExtractor(it) }
                    .toTypedArray()
        }

    private val mHttpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(mUserAgent)
        .setAllowCrossProtocolRedirects(true)
        // 局域网 Emby：连接失败快速暴露，别等默认 8s（转圈 8 秒类故障体感）
        .setConnectTimeoutMs(3_000)
        .setReadTimeoutMs(8_000)

    /** 设置 HTTP 请求头（共享给所有 ExoPlayer 实例） */
    fun setRequestHeaders(headers: Map<String, String>) {
        headers.entries.find { it.key == "User-Agent" }?.let {
            mHttpDataSourceFactory.setUserAgent(it.value)
        }
        mHttpDataSourceFactory.setDefaultRequestProperties(headers)
    }

    /** 创建共享 HTTP 数据源工厂 */
    fun createDataSourceFactory(context: Context): DefaultDataSource.Factory {
        return DefaultDataSource.Factory(context.applicationContext, mHttpDataSourceFactory)
    }

    /**
     * 创建（可选缓存）数据源工厂。
     * 缓存启用时返回 CacheDataSource 包装（磁盘缓存，重复观看/切集不重新拉流），否则原样返回。
     */
    fun createCachingDataSourceFactory(context: Context): androidx.media3.datasource.DataSource.Factory {
        val base = DefaultDataSource.Factory(context.applicationContext, mHttpDataSourceFactory)
        if (!ExoCache.isEnabled()) return base
        return try {
            androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(ExoCache.get(context))
                .setUpstreamDataSourceFactory(base)
        } catch (_: Exception) {
            base // 缓存初始化失败时回退，保证播放不中断
        }
    }

    fun getMediaSource(
        context: Context,
        path: String,
        headers: Map<String, String>?,
        mediaItem: MediaItem,
        enableDolbyVisionCompatibility: Boolean,
        assHandler: io.github.peerless2012.ass.media.AssHandler? = null
    ): MediaSource {
        val contentUri = Uri.parse(path)

        if ("rtmp" == contentUri.scheme) {
            return ProgressiveMediaSource.Factory(
                androidx.media3.datasource.rtmp.RtmpDataSource.Factory()
            ).createMediaSource(mediaItem)
        }

        headers?.let { setRequestHeaders(it) }

        // 🔴 P6: 主链路使用（可选）磁盘缓存数据源工厂（ExoCache 启用时包 CacheDataSource）
        val dataSourceFactory = createCachingDataSourceFactory(context)

        val baseExtractorsFactory = if (enableDolbyVisionCompatibility) {
            dolbyVisionRemapExtractorsFactory
        } else {
            DefaultExtractorsFactory()
        }

        // ASS 字幕拓展：走 DefaultMediaSourceFactory（自动处理 SubtitleConfiguration，含 ASS/SRT），
        // 用 AssSubtitleParserFactory 解析 ASS/SSA 轨，AssMatroskaExtractor 提取 MKV 内嵌 ASS。
        if (assHandler != null) {
            val assParser = io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory(assHandler)
            val assExtractors = baseExtractorsFactory.withAssMkvSupport(assParser, assHandler)
            return DefaultMediaSourceFactory(dataSourceFactory, assExtractors)
                .setSubtitleParserFactory(assParser)
                .createMediaSource(mediaItem)
        }

        // 先用 when 分支创建主视频源
        val mainSource = when (inferContentType(path)) {
            C.CONTENT_TYPE_DASH -> androidx.media3.exoplayer.dash.DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            C.CONTENT_TYPE_SS -> SsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            C.CONTENT_TYPE_HLS -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(
                dataSourceFactory,
                baseExtractorsFactory
            ).createMediaSource(mediaItem)
        }

        // 手动合并字幕：直接调用 MediaSource.Factory 会忽略 mediaItem 里
        // 的 SubtitleConfiguration，必须手动转成 SingleSampleMediaSource 合并
        val subtitleConfigs = mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
        if (subtitleConfigs.isEmpty()) {
            return mainSource
        }
        val sources = mutableListOf<MediaSource>(mainSource)
        for (config in subtitleConfigs) {
            try {
                val subSource = androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(config, C.TIME_UNSET)
                sources.add(subSource)
            } catch (_: Exception) {}
        }
        return androidx.media3.exoplayer.source.MergingMediaSource(*sources.toTypedArray())
    }

    private fun inferContentType(url: String): Int {
        val name = url.lowercase(Locale.getDefault())
        return when {
            name.contains(".mpd") -> C.CONTENT_TYPE_DASH
            name.contains(".m3u8") -> C.CONTENT_TYPE_HLS
            name.matches(".*\\.ism(l)?(/manifest(\\(.+\\))?)?".toRegex()) -> C.CONTENT_TYPE_SS
            else -> C.CONTENT_TYPE_OTHER
        }
    }
}