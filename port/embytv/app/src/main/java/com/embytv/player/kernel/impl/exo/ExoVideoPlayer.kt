package com.embytv.player.kernel.impl.exo

import android.content.Context
import android.graphics.Point
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.DefaultTrackNameProvider
import com.embytv.player.kernel.AbstractVideoPlayer
import com.embytv.player.kernel.VideoLog
import com.embytv.player.model.KernelTrackInfo
import com.embytv.player.model.KernelTrackReport
import com.embytv.player.model.PlayerConstant
import com.embytv.player.model.TrackType
import com.embytv.player.model.VideoKernelInfo
import com.embytv.player.model.VideoTrackBean

/**
 * ExoPlayer 内核实现（Media3 1.10.1）。
 * 支持 Dolby Vision P5 GL 合成器、P7 NAL 过滤、P7 启动预缓冲。
 */
class ExoVideoPlayer(private val context: Context) : AbstractVideoPlayer(), Player.Listener {

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var doviCompositor: Any? = null
    private var currentSurface: Surface? = null
    private var targetSurface: Surface? = null
    private var doviProfile: Int? = null
    private var tracksKnown = false
    private var hasRenderedFirstFrame = false
    private var released = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var compositorRetryCount = 0
    private var subtitleUrls: List<Pair<String, String>> = emptyList() // (url, mimeType)

    /** 软解模式：重建 ExoPlayer 时禁用 MediaCodec 视频渲染器，走 FFmpeg 软解 */
    private var softwareDecode = false
    private var lastPath: String = ""
    private var lastHeaders: Map<String, String> = emptyMap()

    // 内核信息（硬解/软解 + 视频格式）
    private var videoDecoderName: String? = null
    private var videoFormat: Format? = null
    private var bandwidthMeter: androidx.media3.exoplayer.upstream.BandwidthMeter? = null

    // ASS 字幕拓展（libass 渲染）：AssHandler 驱动 ASS 轨解析与渲染
    private var assHandler: io.github.peerless2012.ass.media.AssHandler? = null

    override fun setSubtitleUrls(urls: List<Pair<String, String>>) {
        // ASS 拓展已启用：保留 ASS(SRT/SSA) 全部字幕，交给 AssSubtitleParserFactory 解析。
        // 之前 EXO 无 ASS 解码器会过滤掉 ass；现在 ass-media 负责解析渲染。
        subtitleUrls = urls
    }

    /** ASS 字幕拓展：创建 AssSubtitleView 挂到容器，由 AssHandler(libass) 驱动渲染 */
    override fun attachAssSubtitleView(container: android.view.ViewGroup?) {
        val handler = assHandler ?: return
        if (container == null) return
        try {
            val view = io.github.peerless2012.ass.media.widget.AssSubtitleView(context, handler)
            container.removeAllViews()
            container.addView(
                view,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            container.visibility = android.view.View.VISIBLE
            VideoLog.i("EXO AssSubtitleView attached (libass)")
        } catch (e: Exception) {
            VideoLog.e("EXO attachAssSubtitleView failed: ${e.message}", e)
        }
    }
    private val compositorRetry = Runnable { applyVideoOutput() }

    override fun initPlayer() {
        // 🔴 P6: 同步 EXO 磁盘缓存配置（默认开启，容量复用设置页 player_cache_size_mb，与 MPV 缓存一致）
        try {
            val p = context.getSharedPreferences("emby_tv", android.content.Context.MODE_PRIVATE)
            val cacheMb = p.getInt("player_cache_size_mb", 256).coerceIn(64, 2048)
            ExoCache.applyPrefs(enabled = true, maxMb = cacheMb)
            VideoLog.i("EXO disk cache enabled (max=${cacheMb}MB)")
        } catch (_: Exception) {}
        // 崩溃日志持久化：写入公共目录（下载/EmbyTV/），电视文件管理器可见
        if (Thread.getDefaultUncaughtExceptionHandler()?.javaClass?.name?.contains("CrashFileDumper") != true) {
            val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val content = "=== CRASH ===\nThread: ${thread.name}\n${throwable.stackTraceToString()}\n" +
                        "Cause: ${throwable.cause?.stackTraceToString() ?: "none"}"
                    val filename = "crash_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.log"
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/EmbyTV")
                        }
                        context.contentResolver.delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(filename))
                        val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) } }
                    } else {
                        val dir = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "EmbyTV")
                        dir.mkdirs()
                        java.io.File(dir, filename).writeText(content)
                    }
                } catch (_: Exception) {}
                prevHandler?.uncaughtException(thread, throwable)
            }
        }
        released = false
        createPlayer(softwareDecode)
    }

    /** 创建/重建 ExoPlayer 实例（swDecode=true 时优先选软件解码器） */
    private fun createPlayer(swDecode: Boolean) {
        val innerFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(if (swDecode) androidx.media3.exoplayer.mediacodec.MediaCodecSelector.PREFER_SOFTWARE
                else androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT)
        // 包装 RenderersFactory：启用 SRT/ASS 等 legacy 字幕解码
        //（默认不启用，K-19 等 P7 mkv 含 SRT 字幕轨会抛 IllegalStateException）
        val baseRenderersFactory = androidx.media3.exoplayer.RenderersFactory { handler, videoListener, audioListener, textOutput, metadataOutput ->
            innerFactory.createRenderers(handler, videoListener, audioListener, textOutput, metadataOutput).also {
                for (r in it) {
                    if (r is androidx.media3.exoplayer.text.TextRenderer) {
                        try {
                            r.experimentalSetLegacyDecodingEnabled(true)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        // ASS 字幕拓展：AssRenderersFactory 把 AssRenderer（libass 渲染）加进 renderer 列表，
        // 使 EXO 能渲染 ASS/SSA 轨（参考 AfuseKtV "ass拓展" / Jellyfin）。
        val assHandler = io.github.peerless2012.ass.media.AssHandler(
            io.github.peerless2012.ass.media.type.AssRenderType.OVERLAY_OPEN_GL
        )
        this.assHandler = assHandler
        // render 延迟创建：反射注册 renderCallback，render 就绪时应用已保存的 ASS 字号缩放（全局持久化 + PlayResY 归一化）
        try {
            val setCb = assHandler.javaClass.getMethod("setRenderCallback", kotlin.jvm.functions.Function1::class.java)
            val cb = object : kotlin.jvm.functions.Function1<Any?, kotlin.Unit> {
                override fun invoke(render: Any?): kotlin.Unit {
                    if (render == null) return kotlin.Unit
                    try {
                        val norm = normalizedScale(assFontScale)
                        render.javaClass.getMethod("setFontScale", Float::class.javaPrimitiveType)
                            .invoke(render, norm)
                        VideoLog.i("EXO ASS renderCallback setFontScale(norm=$norm)")
                    } catch (_: Exception) {}
                    return kotlin.Unit
                }
            }
            setCb.invoke(assHandler, cb)
        } catch (_: Exception) {}
        val renderersFactory = io.github.peerless2012.ass.media.factory.AssRenderersFactory(assHandler, baseRenderersFactory)

        val selector = DefaultTrackSelector(context).buildUponParameters()
            .setTunnelingEnabled(false)
            .setPreferredAudioLanguage("zh")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        trackSelector = DefaultTrackSelector(context, selector)

        // 初始带宽估计 15Mbps：TV 局域网/千兆网络常见值，避免默认 1Mbps 首帧选低清档（DefaultBandwidthMeter L84）
        val bwMeter = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(15_000_000)
            .build()
        bandwidthMeter = bwMeter
        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(P7StartupLoadControl(P7_STARTUP_BUFFER_US))
            .setBandwidthMeter(bwMeter)
            .setAnalyticsCollector(DefaultAnalyticsCollector(androidx.media3.common.util.Clock.DEFAULT))
            .build()

        player.playbackParameters = PlaybackParameters(1f)
        player.playWhenReady = false
        player.addListener(this)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                videoDecoderName = decoderName
            }
        })
        // ASS：把 AssHandler 挂到 player（监听 tracks/尺寸变化，驱动 libass 渲染）
        try {
            assHandler.init(player)
        } catch (e: Exception) {
            VideoLog.e("EXO assHandler.init failed: ${e.message}", e)
        }
        exoPlayer = player
        // 重建后复用已有 surface（onTracksChanged → applyVideoOutput 时挂上）
        currentSurface = null
        val pending = targetSurface
        if (pending != null && pending.isValid) {
            // 保持 targetSurface，等 tracks 就绪后 applyVideoOutput 挂载
        }
        VideoLog.i("EXO player init complete (ass support: enabled, swDecode=$swDecode)")
    }

    /** 解码模式切换：0/1=硬解(MediaCodec) 2=软解(FFmpeg)。重建播放器真实生效 */
    override fun setHwdecMode(index: Int) {
        val wantSw = index == 2
        if (wantSw == softwareDecode || exoPlayer == null) return
        softwareDecode = wantSw
        rebuildPlayer()
        VideoLog.i("EXO hwdec switch -> ${if (wantSw) "SW(FFmpeg)" else "HW(MediaCodec)"}")
    }

    private fun rebuildPlayer() {
        val old = exoPlayer ?: return
        val positionMs = old.currentPosition
        val playing = old.playWhenReady
        val mediaItem = old.currentMediaItem
        val surface = targetSurface ?: currentSurface
        mainHandler.removeCallbacks(compositorRetry)
        compositorRetryCount = 0
        hasRenderedFirstFrame = false
        videoDecoderName = null
        tracksKnown = false
        doviProfile = null
        old.removeListener(this)
        old.release()
        exoPlayer = null
        createPlayer(softwareDecode)
        // 用同一 MediaItem（保留外挂字幕配置）重建媒体源
        try {
            val item = mediaItem
            val newPlayer = exoPlayer
            if (item != null && newPlayer != null) {
                val ms = ExoMediaSourceHelper.getMediaSource(context, lastPath, lastHeaders, item, true, assHandler)
                newPlayer.setMediaSource(ms)
                newPlayer.prepare()
                newPlayer.playWhenReady = playing
                if (positionMs > 0) newPlayer.seekTo(positionMs)
            }
        } catch (e: Exception) {
            VideoLog.e("EXO hwdec rebuild failed: ${e.message}", e)
        }
    }

    override fun setOptions() = Unit

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        lastPath = path
        lastHeaders = headers ?: emptyMap()
        if (path.isEmpty()) {
            mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_URL_EMPTY, 0)
            return
        }
        tracksKnown = false
        doviProfile = null
        val contentUri = Uri.parse(path)

        // 设置请求头到共享 HTTP 工厂（DefaultMediaSourceFactory 会使用它）
        headers?.let { ExoMediaSourceHelper.setRequestHeaders(it) }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(contentUri)
            .setMediaId(path)
        // 添加外挂字幕（DefaultMediaSourceFactory 自动处理 SubtitleConfiguration）
        val subConfigs = mutableListOf<MediaItem.SubtitleConfiguration>()
        for ((subIndex, subPair) in subtitleUrls.withIndex()) {
            val subUrl = subPair.first
            val mimeType = subPair.second
            try {
                subConfigs.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                        .setId(subIndex.toString())
                        .setMimeType(mimeType)
                        .setLanguage("und")
                        .setLabel("字幕 ${subIndex + 1}")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT) // 默认选中，播放即显示
                        .build()
                )
            } catch (_: Exception) {}
        }
        subtitleUrls = emptyList() // 用完清空
        if (subConfigs.isNotEmpty()) {
            mediaItemBuilder.setSubtitleConfigurations(subConfigs)
        }
        val mediaItem = mediaItemBuilder.build()

        // 统一走 ExoMediaSourceHelper.getMediaSource()：
        // 1) 注入 Dolby Vision remap extractors（P5→hvc1、P7 RPU/EL 剥离）
        // 2) 手动合并外挂字幕（SingleSampleMediaSource → MergingMediaSource）
        val mediaSource = ExoMediaSourceHelper.getMediaSource(
            context = context,
            path = path,
            headers = null,  // headers 已通过 setRequestHeaders 设置
            mediaItem = mediaItem,
            enableDolbyVisionCompatibility = true,
            assHandler = assHandler
        )
        exoPlayer?.setMediaSource(mediaSource)
    }

    override fun setSurface(surface: Surface) {
        VideoLog.i("EXO setSurface valid=${surface.isValid} same=${currentSurface === surface} tracksKnown=$tracksKnown released=$released")
        if (released || exoPlayer == null || !surface.isValid || currentSurface === surface) return
        targetSurface = surface
        if (tracksKnown) {
            applyVideoOutput()
        }
    }

    override fun clearSurface() {
        VideoLog.i("EXO clearSurface")
        mainHandler.removeCallbacks(compositorRetry)
        compositorRetryCount = 0
        hasRenderedFirstFrame = false
        val surface = currentSurface
        currentSurface = null
        targetSurface = null
        if (!released && exoPlayer != null && surface != null) {
            exoPlayer?.clearVideoSurface(surface)
        }
        releaseDoviCompositor()
    }

    override fun hasValidSurface(): Boolean {
        val s = currentSurface ?: targetSurface
        return s != null && s.isValid && !released
    }

    override fun prepareAsync() {
        exoPlayer?.prepare()
    }

    override fun start() {
        exoPlayer?.playWhenReady = true
    }

    override fun pause() {
        exoPlayer?.playWhenReady = false
    }

    override fun stop() {
        if (!released) exoPlayer?.stop()
    }

    override fun reset() {
        mainHandler.removeCallbacks(compositorRetry)
        compositorRetryCount = 0
        tracksKnown = false
        doviProfile = null
        hasRenderedFirstFrame = false
        if (!released) {
            exoPlayer?.stop()
            clearSurface()
        }
    }

    override fun release() {
        if (released) return
        clearSurface()
        released = true
        exoPlayer?.removeListener(this)
        exoPlayer?.release()
        exoPlayer = null
        try {
            assHandler?.release()
        } catch (_: Exception) {}
        assHandler = null
    }

    override fun seekTo(timeMs: Long) {
        exoPlayer?.seekTo(timeMs)
    }

    override fun setSpeed(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        exoPlayer?.volume = (leftVolume + rightVolume) / 2f
    }

    override fun setLooping(isLooping: Boolean) {
        exoPlayer?.repeatMode = if (isLooping) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    override fun setSubtitleOffset(offsetMs: Long) = Unit

    // ASS 字幕字号缩放：把设置页的 30-120px 映射到 libass font_scale。
    // AssHandler.getRender()/setRenderCallback 是 Kotlin internal，跨模块编译不可见，
    // 用反射调用（AssRender.setFontScale 本身是 public）。
    // 归一化：不同影片 ASS 脚本的 PlayResY 不同导致同一百分比实际大小不一，
    // 按 PlayResY 归一化到 720p 基准，使所有影片字幕物理大小一致。
    private var assFontScale: Float = 1.0f

    override fun setSubtitleSize(size: Int) {
        val base = (size / 60f).coerceIn(0.5f, 2.0f) // 用户百分比 30->0.5, 60->1.0, 120->2.0
        assFontScale = base
        try {
            val norm = normalizedScale(base)
            applyAssFontScale(norm)
            VideoLog.i("EXO ASS setFontScale(base=$base norm=$norm) size=$size")
        } catch (e: Exception) {
            VideoLog.e("EXO ASS setFontScale failed: ${e.message}", e)
        }
    }

    // 读取 ASS 轨 PlayResY，归一化到 720p 基准（PlayResY 越小字幕越大，需缩小）
    private fun normalizedScale(base: Float): Float {
        val handler = assHandler ?: return base
        return try {
            val track = handler.javaClass.getMethod("getTrack").invoke(handler)
            android.util.Log.e("EXO-ASS", "normalizedScale: track=$track")
            if (track == null) return base
            val hM = try { track.javaClass.getMethod("getHeight") } catch (e2: Exception) {
                // getHeight 可能不存在，尝试字段访问 nativeAssTrack 高度
                android.util.Log.e("EXO-ASS", "no getHeight method: ${e2.message}")
                null
            }
            if (hM == null) return base
            val height = hM.invoke(track) as? Number
            android.util.Log.e("EXO-ASS", "playResHeight=$height")
            val h = height?.toInt() ?: 0
            if (h <= 0) base else base * (720f / h)  // PlayResY=1080 → 0.667x, 576 → 1.25x
        } catch (e: Exception) {
            android.util.Log.e("EXO-ASS", "normalizedScale exception: ${e.javaClass.simpleName}: ${e.message}")
            base
        }
    }

    private fun applyAssFontScale(scale: Float) {
        val handler = assHandler ?: run { android.util.Log.e("EXO-ASS", "applyAssFontScale: assHandler null"); return }
        val render = try {
            handler.javaClass.getMethod("getRender").invoke(handler)
        } catch (e: Exception) {
            android.util.Log.e("EXO-ASS", "getRender failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (render != null) {
            try {
                render.javaClass.getMethod("setFontScale", Float::class.javaPrimitiveType)
                    .invoke(render, scale)
                android.util.Log.e("EXO-ASS", "setFontScale($scale) OK")
            } catch (e: Exception) {
                android.util.Log.e("EXO-ASS", "setFontScale invoke failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    override fun setSubtitlePosition(pos: Int) = Unit

    override fun setAspectRatio(ratio: String) {
        // EXO：fill 用 SCALE_TO_FIT_WITH_CROP，其他保持默认（FIT）
        val mode = if (ratio == "fill") {
            androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else {
            androidx.media3.common.C.VIDEO_SCALING_MODE_DEFAULT
        }
        exoPlayer?.videoScalingMode = mode
    }

    override fun setZoom(scale: Float) {
        // EXO 不支持动态缩放，通过 setVideoScalingMode 的 crop 模式近似实现
        // 完整缩放需要 SurfaceView 的 setScaleX/Y，但会影响 Surface 生命周期
        VideoLog.i("EXO zoom not supported natively, using aspect ratio crop")
    }

    override fun setAudioPassthrough(enabled: Boolean) {
        // EXO 通过设置音频会话属性启用直通
        // 实际直通需要设备支持和 AudioTrack 的 ENCODING_IEC61937
        val mode = if (enabled) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        VideoLog.i("EXO audio passthrough mode=$mode (requires rebuild)")
    }

    override fun setFrameRateMatch(enabled: Boolean) {
        // EXO 通过 MediaFormat 设置帧率
        VideoLog.i("EXO frame rate match=$enabled (requires surface refresh rate config)")
    }

    override fun isPlaying(): Boolean {
        val state = exoPlayer?.playbackState
        return exoPlayer?.playWhenReady == true &&
            (state == Player.STATE_BUFFERING || state == Player.STATE_READY)
    }

    override fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L

    override fun getDuration(): Long = exoPlayer?.duration ?: 0L

    override fun getSpeed(): Float = exoPlayer?.playbackParameters?.speed ?: 1f

    override fun getVideoSize(): Point {
        val size = exoPlayer?.videoSize
        return Point(size?.width ?: 0, size?.height ?: 0)
    }

    override fun getBufferedPercentage(): Int = exoPlayer?.bufferedPercentage ?: 0

    override fun getTcpSpeed(): Long {
        // BandwidthMeter.getBitrateEstimate() 返回 bps，转 bytes/s
        // PlayerViewModel 再除 1024 得到 KB/s 用于显示
        val bw = bandwidthMeter?.bitrateEstimate ?: 0L
        return bw / 8  // bps → bytes/s
    }

    override fun getTracks(type: TrackType): List<VideoTrackBean> {
        val exoTrackType = exoTrackType(type) ?: return emptyList()
        return exoPlayer?.currentTracks?.groups?.flatMapIndexed { groupIndex, group ->
            if (group.type != exoTrackType) return@flatMapIndexed emptyList()
            (0 until group.length).map { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                VideoTrackBean(
                    id = "$groupIndex-$trackIndex",
                    name = DefaultTrackNameProvider(context.resources).getTrackName(format),
                    type = type,
                    selected = group.isTrackSelected(trackIndex),
                    language = format.language,
                    mimeType = format.sampleMimeType,
                    codec = format.codecs,
                    supported = group.getTrackSupport(trackIndex) == C.FORMAT_HANDLED
                )
            }
        } ?: emptyList()
    }

    override fun selectTrack(track: VideoTrackBean) {
        val trackType = exoTrackType(track.type) ?: return
        val ids = track.id?.split("-") ?: return
        val groupIndex = ids.getOrNull(0)?.toIntOrNull() ?: return
        val trackIndex = ids.getOrNull(1)?.toIntOrNull() ?: return
        val selector = trackSelector ?: return
        val override = exoPlayer?.currentTracks?.groups?.getOrNull(groupIndex)?.let {
            TrackSelectionOverride(it.mediaTrackGroup, trackIndex)
        } ?: return
        selector.setParameters(
            selector.buildUponParameters()
                .clearOverridesOfType(trackType)
                .addOverride(override)
                .setTrackTypeDisabled(trackType, false)
                .build()
        )
    }

    override fun deselectTrack(type: TrackType) {
        val trackType = exoTrackType(type) ?: return
        trackSelector?.setParameters(
            trackSelector!!.buildUponParameters()
                .setTrackTypeDisabled(trackType, true)
                .build()
        )
    }

    // ==================== Player.Listener ====================

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        VideoLog.i("EXO onVideoSizeChanged: ${videoSize.width}x${videoSize.height} rot=${videoSize.unappliedRotationDegrees}")
        mPlayerEventListener.onVideoSizeChange(videoSize.width, videoSize.height)
        if (videoSize.unappliedRotationDegrees > 0) {
            mPlayerEventListener.onInfo(
                PlayerConstant.MEDIA_INFO_VIDEO_ROTATION_CHANGED,
                videoSize.unappliedRotationDegrees
            )
        }
    }

    override fun onRenderedFirstFrame() {
        hasRenderedFirstFrame = true
        VideoLog.i("EXO onRenderedFirstFrame")
        mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START, 0)
    }

    override fun onPlaybackStateChanged(state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> {
                mPlayerEventListener.onInfo(
                    PlayerConstant.MEDIA_INFO_BUFFERING_START,
                    getBufferedPercentage()
                )
            }
            Player.STATE_READY -> {
                mPlayerEventListener.onPrepared()
                mPlayerEventListener.onInfo(
                    PlayerConstant.MEDIA_INFO_BUFFERING_END,
                    getBufferedPercentage()
                )
            }
            Player.STATE_ENDED -> mPlayerEventListener.onCompletion()
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        // 采集视频轨道格式（供内核信息显示：HDR/DV/编码）
        tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { group ->
            if (group.length > 0) {
                videoFormat = group.getTrackFormat(0)
            }
        }
        val wasTracksKnown = tracksKnown
        tracksKnown = true
        val newDoviProfile = detectDoviProfile(tracks)
        if (!wasTracksKnown || newDoviProfile != doviProfile) {
            doviProfile = newDoviProfile
            VideoLog.i("EXO Dolby Vision profile=$newDoviProfile")
            applyVideoOutput()
        }
        // 自动选中字幕轨道：只选外挂 SRT（application/x-subrip），
        // ASS 轨交给 AssHandler(libass) 走 AssSubtitleView 渲染，不走普通 TextRenderer
        //（避免样式丢失 + 双重渲染）。每条 onTracksChanged 都检查（MergingMediaSource
        // 可能在第二次回调才出现字幕轨）
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val hasSubSelected = textGroups.any { group -> (0 until group.length).any { group.isTrackSelected(it) } }
        if (textGroups.isNotEmpty() && !hasSubSelected && exoPlayer != null) {
            // 自动选中第一个可用的字幕轨（先 ASS，再其他）
            try {
                // 优先选 ASS（text/x-ssa，由 AssHandler libass 渲染，样式完整）
                val assGroup = textGroups.firstOrNull { g ->
                    (0 until g.length).any { g.getTrackFormat(it).sampleMimeType == "text/x-ssa" }
                }
                val group = assGroup ?: textGroups.first()
                val override = TrackSelectionOverride(group.mediaTrackGroup, 0)
                exoPlayer!!.trackSelectionParameters = exoPlayer!!.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(override)
                    .build()
                VideoLog.i("EXO auto-select subtitle: ${group.getTrackFormat(0).sampleMimeType}")
            } catch (_: Exception) {}
        }
        val report = KernelTrackReport(
            tracks.groups.flatMapIndexed { groupIndex, group ->
                (0 until group.length).map { trackIndex ->
                    val format = group.getTrackFormat(trackIndex)
                    KernelTrackInfo(
                        trackType = group.type,
                        id = "$groupIndex-$trackIndex",
                        label = format.label,
                        language = format.language,
                        sampleMimeType = format.sampleMimeType,
                        codecs = format.codecs,
                        selected = group.isTrackSelected(trackIndex),
                        support = group.getTrackSupport(trackIndex)
                    )
                }
            }
        )
        mPlayerEventListener.onTracksChanged(report)
    }

    private var exoErrorRetryCount = 0

    override fun onPlayerError(error: PlaybackException) {
        VideoLog.e("EXO PlaybackException code=${error.errorCode} name=${error.errorCodeName}: ${error.message}", error)
        // 打印异常堆栈以便定位（如 remap extractor 的 FAILED_RUNTIME_CHECK 根因）
        val stack = error.cause?.let { "${it.javaClass.simpleName}: ${it.message}\n" + it.stackTraceToString().take(500) } ?: error.stackTraceToString().take(500)
        VideoLog.e("EXO PlaybackException stack:\n$stack")
        // 音频解码器错误（如 EAC3 不被设备支持）→ 清除音轨覆盖，回退到默认
        val msg = error.message?.lowercase() ?: ""
        if (msg.contains("audiorenderer") || msg.contains("audio") && msg.contains("unsupported")) {
            VideoLog.e("EXO audio decoder error, reverting to default audio track")
            trackSelector?.setParameters(
                trackSelector!!.buildUponParameters()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .build()
            )
        }
        // 自动重试：连续错误 ≤2 次时重新 prepare（网络闪断/临时解码失败），多次失败才上报
        if (exoErrorRetryCount < 2 && exoPlayer != null) {
            exoErrorRetryCount++
            VideoLog.i("EXO auto-retry #$exoErrorRetryCount after error: ${error.errorCodeName}")
            try {
                exoPlayer!!.prepare()
            } catch (e: Exception) {
                VideoLog.e("EXO retry prepare failed: ${e.message}")
                mPlayerEventListener.onError(ExoPlaybackFailureClassifier.classify(error))
            }
            return
        }
        exoErrorRetryCount = 0
        mPlayerEventListener.onError(ExoPlaybackFailureClassifier.classify(error))
    }

    override fun onCues(cueGroup: CueGroup) {
        // 位图字幕（PGS/内嵌图形字幕）：ExoPlayer 输出 bitmap cue，交给 UI 渲染
        val bitmapCue = cueGroup.cues.firstOrNull { it.bitmap != null }
        if (bitmapCue != null) {
            VideoLog.i("EXO onCues bitmap cue size=${bitmapCue.bitmap?.width}x${bitmapCue.bitmap?.height}")
            mPlayerEventListener.onSubtitleBitmap(bitmapCue.bitmap)
            return
        }
        val text = cueGroup.cues.joinToString("\n") { it.text?.toString().orEmpty() }
        VideoLog.i("EXO onCues text=${text.take(60)}")
        mPlayerEventListener.onSubtitleText(text.trim().ifEmpty { null })
    }

    // ==================== 杜比视界处理 ====================

    private fun detectDoviProfile(tracks: Tracks): Int? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                if (DolbyVisionHevcRemapExtractor.isRemappedProfile7(format)) return 7
                val mime = format.sampleMimeType.orEmpty().lowercase()
                val codecs = format.codecs.orEmpty().lowercase()
                if (mime.contains("dolby-vision") || codecs.contains("dvhe") ||
                    codecs.contains("dvh1") || codecs.contains("dovi")
                ) {
                    return DoviProfile.fromCodecs(codecs)
                        .takeIf { it != DoviProfile.UNKNOWN }?.code
                    // 若枚举未识别，回退到正则
                        ?: Regex("(?:dvhe|dvh1|dovi)\\.(\\d+)")
                            .find(codecs)?.groupValues?.get(1)?.toIntOrNull()
                }
                val remapped = Regex("^hvc1\\.0*(5|7|8)\\.0*\\d{1,2}$").find(codecs)
                if (remapped != null) return remapped.groupValues[1].toInt()
            }
        }
        return null
    }

    private fun applyVideoOutput() {
        val target = targetSurface
        if (!tracksKnown || target == null || released || exoPlayer == null || !target.isValid) return

        // P5: use DoviGlCompositor (disabled - PQ BT.2020 LUT also wrong)
        // P7/P8: use SurfaceView directly (color conversion is correct via SurfaceFlinger)
        val needDoviGl = false
        var output: Surface? = target

        if (needDoviGl) {
            // DoviGlCompositor approach - disabled until we figure out the correct color pipeline
            releaseDoviCompositor()
        }

        if (output !== currentSurface) {
            if (currentSurface != null) {
                exoPlayer?.clearVideoSurface(currentSurface)
            }
            exoPlayer?.setVideoSurface(output)
            currentSurface = output
            VideoLog.i("EXO video surface -> $output (doviProfile=$doviProfile)")
        }
        if (!needDoviGl) {
            releaseDoviCompositor()
        }
    }

    private fun releaseDoviCompositor() {
        val compositor = doviCompositor
        doviCompositor = null
        compositor?.let {
            when (it) {
                is DoviGlCompositor -> it.release()
                is RawYuvCompositor -> it.release()
            }
        }
    }

    private fun exoTrackType(type: TrackType): Int? {
        return when (type) {
            TrackType.AUDIO -> C.TRACK_TYPE_AUDIO
            TrackType.SUBTITLE -> C.TRACK_TYPE_TEXT
            else -> null
        }
    }

    override fun getKernelInfo(): VideoKernelInfo {
        // 解码方式：根据解码器名判断（MediaCodec 厂商解码器=硬解，c2.android/omx.google/FFmpeg=软解）
        val decodeMode = videoDecoderName?.let { name ->
            val lower = name.lowercase()
            when {
                lower.contains("ffmpeg") -> "软解"
                lower.startsWith("c2.android.") || lower.startsWith("omx.google.") -> "软解"
                lower.startsWith("c2.") || lower.startsWith("omx.") -> "硬解"
                else -> "硬解"
            }
        } ?: "未知"

        // 视频格式简称：杜比视界 > HDR10/HLG > SDR
        val fmt = videoFormat
        val videoFormatLabel = when {
            doviProfile != null -> "DV P$doviProfile"
            fmt?.colorInfo != null -> {
                val transfer = fmt!!.colorInfo!!.colorTransfer
                when (transfer) {
                    C.COLOR_TRANSFER_ST2084 -> "HDR10"
                    C.COLOR_TRANSFER_HLG -> "HLG"
                    else -> "SDR"
                }
            }
            else -> ""
        }

        // 编码简称
        val codecLabel = fmt?.codecs ?: ""

        return VideoKernelInfo(
            decodeMode = decodeMode,
            videoFormat = videoFormatLabel,
            codec = codecLabel
        )
    }

    companion object {
        private const val P7_STARTUP_BUFFER_US = 2_000_000L
        private const val MAX_DOVI_COMPOSITOR_RETRIES = 5
    }
}