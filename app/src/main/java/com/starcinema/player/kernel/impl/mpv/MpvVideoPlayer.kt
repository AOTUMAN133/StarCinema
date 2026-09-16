package com.starcinema.player.kernel.impl.mpv

import android.content.Context
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import com.starcinema.player.kernel.AbstractVideoPlayer
import com.starcinema.player.kernel.ScreenHdrDetector
import com.starcinema.player.kernel.VideoLog
import com.starcinema.player.model.KernelTrackInfo
import com.starcinema.player.model.KernelTrackReport
import com.starcinema.player.model.PlayerConstant
import com.starcinema.player.model.TrackType
import com.starcinema.player.model.VideoKernelInfo
import com.starcinema.player.model.VideoTrackBean
import java.io.File

class MpvVideoPlayer(private val context: Context) : AbstractVideoPlayer(),
    MPVLib.EventObserver, MPVLib.LogObserver {

    private var sourcePath: String = ""
    private var sourceHeaders: Map<String, String> = emptyMap()
    private var surface: Surface? = null
    private var osdSurface: Surface? = null
    private var fileLoaded = false
    private var isPrepared = false
    private var initialized = false
    private var paused = false
    private var eofReached = false
    private var loading = false
    private var released = false
    /** 实际生效的 vo（setOptions 选定，setSurface 恢复时用它，避免低端设备被硬编码 gpu-next 覆盖） */
    private var activeVo = "gpu-next"

    private var durationMs = 0L
    private var currentPositionMs = 0L
    private var bufferedPercentage = 0
    private var videoSize = Point()

    /** 运行时用 property 设置 mpv 选项（init 后生效，区别于 option） */
    private fun setProp(name: String, value: String) { MPVLib.setPropertyString(name, value) }

    private var hwdecActive = ""
    private var videoFormatName = ""
    private var videoCodecName = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun initPlayer() {
        try {
            released = false
            // 官方 MPVActivity 顺序：先 addObserver，再 create/initialize
            MPVLib.addObserver(this)
            MPVLib.addLogObserver(this)
            MPVLib.create(context.applicationContext)
            // 按官方 BaseMPVView.initialize() 顺序初始化
            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", context.filesDir.path)
            MPVLib.setOptionString("gpu-shader-cache-dir", context.cacheDir.path)
            MPVLib.setOptionString("icc-cache-dir", context.cacheDir.path)
            setOptions()
            MPVLib.init()
            // post-init 选项（init 之后设置）
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "once")
            initialized = true
            observeProperties()
            VideoLog.i("MPVLib initialized")
        } catch (e: Exception) {
            VideoLog.e("MPVLib init failed: ${e.message}", e)
        }
    }

    private fun setOpt(name: String, value: String) { MPVLib.setOptionString(name, value) }

    override fun setOptions() {
        // 用户可在设置页手动选择解码/渲染配置：auto(自适应) / high(高端) / low(低端)
        // auto 默认按 DeviceTier 自动判定
        var renderConfig = "auto"
        try {
            val p = context.getSharedPreferences("emby_tv", android.content.Context.MODE_PRIVATE)
            renderConfig = p.getString("player_render_config", "auto") ?: "auto"
        } catch (_: Exception) {}
        val isHighEnd = when (renderConfig) {
            "high" -> true
            "low" -> false
            else -> com.starcinema.player.kernel.DeviceTier.isHighEnd(context)  // auto
        }
        VideoLog.i("MPV device tier: ${com.starcinema.player.kernel.DeviceTier.describe(context)}, renderConfig=$renderConfig → highEnd=$isHighEnd")

        setOpt("config", "no"); setOpt("terminal", "no")
        // 屏幕 HDR 能力检测：SDR 屏播 HDR/DV 必须 tone-map（帧进 GPU 管线），HDR 屏保留直通
        val screenHdr = ScreenHdrDetector.isHdr(context)
        VideoLog.i("MPV screen HDR: ${ScreenHdrDetector.describe(context)} (isHdr=$screenHdr)")
        // 设备自适应：高端用 gpu-next（画质好，含杜比重塑），低端盒子用 gpu（更稳，省 shader 开销）
        // 依据 mpv-android 官方默认(vo=gpu) + gpu-next 撕裂/掉帧 issue (#1289/#1227)
        activeVo = if (isHighEnd) "gpu-next" else "gpu"
        setOpt("vo", activeVo)
        // 硬解按设备能力自适应（保持 v1.3.17 用户验证过的配置，勿改回退链）：
        // 高端 surface 直通（保真），低端回退链防黑屏。技巧记录：fongmi 0.1.12 改单值 mediacodec-copy 会
        // 触发 "Both surface and native_window are NULL" 黑屏（Bug #670 无 fix）。
        setOpt("hwdec", if (isHighEnd) "mediacodec" else "mediacodec,mediacodec-copy")
        setOpt("profile", "fast")
        // 直通 Surface 模式：P5 杜比色空间由 Android 图形管线自动处理
        // 帧不经过 mpv GPU 管线，所以 dolby-vision=yes 和 tone-mapping 对视频无效
        // 但 OSD/字幕 overlay 仍由 gpu-next 渲染
        setOpt("gpu-context", "android"); setOpt("opengl-es", "yes")
        // 硬解白名单：含 hevc —— allow_profile_mismatch 放行杜比 profile
        setOpt("hwdec-codecs", "h264,hevc,av1,mpeg4,mpeg2video,vp8,vp9")
        setOpt("vd-lavc-check-hw-profile", "no")
        // gpu-next + OpenGL ES 上 AV1 胶片颗粒 shader 亮度翻转 (mpv #14651)，强制 CPU 应用规避（mpvKt 同款）
        setOpt("vd-lavc-film-grain", "cpu")
        // SDR 屏：启用 HDR→SDR tone-mapping 全套（权威配置，mpv 手册 target-* 系列）
        // 依据：profile=fast 自带 hdr-compute-peak=no（builtin.conf）；SDR 屏 target-peak=203 固定
        // ⚠️ tone-mapping 算法按 vo 分级：bt.2446a 仅 gpu-next 支持，vo=gpu 只支持 mobius/reinhard/clip
        if (!screenHdr) {
            setOpt("vf", "format:dolbyvision=yes")   // DV P5/P8 RPU 元数据保留（libplacebo reshape 需要）
            setOpt("tone-mapping", if (activeVo == "gpu-next") "bt.2446a" else "mobius")
            setOpt("target-peak", "203")             // SDR 屏固定 203 nits
            setOpt("target-prim", "bt.709")          // SDR 输出原色
            setOpt("target-colorspace-hint", "no")   // ★ 关键：别给 swapchain 打 HDR 元数据
        }
        setOpt("ao", "audiotrack,opensles"); setOpt("audio-set-media-role", "yes")
        setOpt("demuxer-max-bytes", "64M"); setOpt("demuxer-max-back-bytes", "64M")
        // 流缓冲加大：mp4(libavformat) 小 seek 频繁，默认 128KB 低效缓存（mpv 手册明确点名 mp4 场景）
        setOpt("stream-buffer-size", "4M")
        // 读取用户配置的缓存大小（来自设置页 Slider），默认 256MB
        try {
            val cachePrefs = context.getSharedPreferences("emby_tv", android.content.Context.MODE_PRIVATE)
            val cacheMb = cachePrefs.getInt("player_cache_size_mb", 256).coerceIn(64, 2048)
            setOpt("demuxer-max-bytes", "${cacheMb}M")
            setOpt("demuxer-max-back-bytes", "${cacheMb}M")
            setOpt("cache", "yes")
            setOpt("cache-secs", "300")
            VideoLog.i("MPV cache set to ${cacheMb}MB")
        } catch (_: Exception) {}
        // 开启缓存暂停：网络不足时自动暂停等待缓冲，而非卡顿
        // cache-pause-wait=0.5：中断后恢复起播更快（默认 1s）
        // 注意：HEVC 硬解+切换字幕卡死是 mpv #1127（open），cache-pause 无法根治，仅缓解
        setOpt("cache-pause", "yes"); setOpt("cache-pause-wait", "0.5")
        // 增大音频缓冲防止硬解+杜比重塑导致 audio underrun
        setOpt("audio-buffer", "2")
        setOpt("sub-auto", "all"); setOpt("audio-file-auto", "no"); setOpt("sub-ass", "yes")
        // 字幕加速：不加载 MKV 内嵌字体（libass 扫描内嵌字体非常慢），用系统默认字体
        setOpt("embeddedfonts", "yes"); setOpt("sub-fonts-load-timeout", "1000"); setOpt("sub-create-files", "no")
        // 简化 ASS 字幕渲染加速加载（libass 排版引擎初始化较慢）
        setOpt("sub-ass-simplify", "yes")
        setOpt("slang", "zh,chi,eng"); setOpt("alang", "chi,eng,zh,jap")
        // 应用字幕设置（字号/颜色/描边/位置，来自 PreferencesHelper）
        try {
            val prefs = context.getSharedPreferences("emby_tv", android.content.Context.MODE_PRIVATE)
            val subSize = prefs.getInt("subtitle_font_size", 55)
            val subColor = prefs.getInt("subtitle_color", -1)
            val subBorder = prefs.getInt("subtitle_border_size", 3)
            val subPos = prefs.getInt("subtitle_position", 100)
            setOpt("sub-font-size", "$subSize")
            if (subColor != -1) setOpt("sub-color", "#%06X".format(subColor and 0xFFFFFF))
            setOpt("sub-border-size", "$subBorder")
            setOpt("sub-pos", "$subPos")
        } catch (e: Exception) { }
        setOpt("vd-lavc-threads", "0"); setOpt("osd-level", "1")
        setOpt("tls-verify", "no")
        // 网络超时：默认 60s 卡死连接会拖死起播，降到 20s 让失败快速暴露
        setOpt("network-timeout", "20"); setOpt("interpolation", "no")
        // 提升日志级别，杜比 profile / RPU 信息可见
        setOpt("msg-level", "all=info,ffmpeg=info,cplayer=info,vo=info")
        // 音频直通（用户可在设置页播放控制开关）：杜比/DTS 源码透传到功放/回音壁
        // 必须在 init 时设置才生效（audio-spdif 是 option 不是运行时 property）
        try {
            val p = context.getSharedPreferences("emby_tv", android.content.Context.MODE_PRIVATE)
            val passthrough = p.getBoolean("player_audio_passthrough", false)
            if (passthrough) {
                setOpt("audio-spdif", "ac3,dts,dts-hd,eac3,truehd")
                setOpt("audio-exclusive", "yes")
                setOpt("audio-buffer", "0.2")
                VideoLog.i("MPV audio passthrough: ON (spdif ac3,dts,dts-hd,eac3,truehd + exclusive)")
            } else {
                setOpt("audio-spdif", "no")
                setOpt("audio-exclusive", "no")
                VideoLog.i("MPV audio passthrough: OFF")
            }
        } catch (_: Exception) {}
    }

    /** 着色器配置：0=无, 1=Anime4K_S, 2=Anime4K_M, 3=Anime4K_L */
    private var shaderProfile: Int = 0

    override fun setShaderConfig(profile: Int) {
        if (shaderProfile == profile) return
        shaderProfile = profile
        applyShader()
    }

    private val shaderNames = mapOf(
        1 to "Anime4K_Restore_CNN_Soft_S.glsl",
        2 to "Anime4K_Restore_CNN_Soft_M.glsl",
        3 to "Anime4K_Restore_CNN_Soft_L.glsl",
    )

    private fun applyShader() {
        if (shaderProfile == 0) {
            setOpt("glsl-shaders", "")
            return
        }
        val name = shaderNames[shaderProfile] ?: return
        try {
            // 从 assets 复制到缓存目录（mpv 需要文件路径）
            val cacheFile = File(context.cacheDir, "shaders/$name")
            if (!cacheFile.exists()) {
                cacheFile.parentFile?.mkdirs()
                context.assets.open("shaders/$name").use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            setOpt("glsl-shaders", cacheFile.absolutePath)
            VideoLog.i("MPV shader: $name")
        } catch (e: Exception) {
            VideoLog.e("MPV shader load failed: $name - ${e.message}")
        }
    }

    private fun observeProperties() {
        // 官方 mpv-android 全用 MPV_FORMAT_NONE：属性变化时回调 eventProperty(name)（无值），手动读取
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("video-params", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("hwdec-active", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("video-format", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("video-codec", MPVLib.MpvFormat.MPV_FORMAT_NONE)
    }

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        sourcePath = path; sourceHeaders = headers?.toMap().orEmpty()
        fileLoaded = false; isPrepared = false; eofReached = false
        if (path.isEmpty()) mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_URL_EMPTY, 0)
    }

    override fun setSurface(surface: Surface) {
        this.surface = surface
        if (surface.isValid && !released && initialized) {
            // 官方 BaseMPVView.surfaceCreated 做法（与 AfuseKt 完全一致）：
            // 1) attachSurface 挂载新 surface
            // 2) setOptionString("force-window", "yes")
            // 3) setPropertyString("vo", voInUse) 恢复视频输出
            MPVLib.attachSurface(surface)
            setOpt("force-window", "yes")
            setProp("vo", activeVo)
            VideoLog.i("MPVLib attachSurface + vo=restored($activeVo) ok")
            if (!fileLoaded) {
                maybeLoad()
            }
        }
    }

    override fun setSurfaceSize(width: Int, height: Int) {
        if (width > 0 && height > 0 && initialized) MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun setOsdSurface(surface: Surface?) { this.osdSurface = surface }
    override fun hasValidSurface(): Boolean = surface != null && surface!!.isValid && !released && initialized
    override fun clearSurface() {
        surface = null
        if (!released && initialized) {
            // 官方 BaseMPVView.surfaceDestroyed 做法（与 AfuseKt 完全一致）：
            // 1) setPropertyString("vo", "null") —— 释放视频输出（同步等待 VO 释放）
            // 2) setPropertyString("force-window", "no")
            // 3) detachSurface —— 此时 vo 已释放，不会触发 GPU 重配
            VideoLog.i("MPVLib detachSurface: vo=null + force-window=no + detach")
            setProp("vo", "null")
            setProp("force-window", "no")
            MPVLib.detachSurface()
        }
    }
    override fun prepareAsync() { maybeLoad() }

    private var subtitleUrls: List<Pair<String, String>> = emptyList() // (url, mimeType)

    /** 设置外挂字幕 URL 列表（MPV 用 sub-add 命令追加，支持 SRT/ASS） */
    override fun setSubtitleUrls(urls: List<Pair<String, String>>) {
        subtitleUrls = urls
        // 已开始播放时直接追加
        if (initialized && fileLoaded && subtitleUrls.isNotEmpty()) {
            addRemoteSubtitles()
        }
    }

    private fun addRemoteSubtitles() {
        if (subtitleUrls.isEmpty() || !initialized) return
        try {
            // 用标题作为 sub-add 的 http-header 需要 set 命令；直接 sub-add 即可
            // MPV sub-add 支持 http URL，标题参数用于显示名
            // 只有第一条带 select 标志（默认显示第一条外挂字幕），其余仅添加供手动切换
            subtitleUrls.forEachIndexed { idx, (url, mime) ->
                val title = if (mime.contains("ass")) "字幕${idx + 1} (ASS)" else "字幕${idx + 1}"
                if (idx == 0) {
                    MPVLib.command(arrayOf("sub-add", url, "select", title))
                } else {
                    MPVLib.command(arrayOf("sub-add", url, "auto", title))
                }
                VideoLog.i("MPVLib sub-add: $url ($mime) select=${idx == 0}")
            }
        } catch (e: Exception) {
            VideoLog.e("MPVLib sub-add failed: ${e.message}", e)
        }
    }

    private fun maybeLoad() {
        if (surface == null || sourcePath.isEmpty() || fileLoaded || released || !initialized || loading) return
        loading = true
        mainHandler.post {
            try {
                val s = surface; if (s != null && s.isValid) MPVLib.attachSurface(s)
                sourceHeaders.forEach { (k, v) -> if (k != "User-Agent") MPVLib.command(arrayOf("set", "http-header-fields", "$k: $v")) }
                MPVLib.command(arrayOf("loadfile", sourcePath, "replace"))
                fileLoaded = true; VideoLog.i("MPVLib loadfile: $sourcePath")
            } catch (e: Exception) {
                VideoLog.e("MPVLib loadfile failed: ${e.message}", e)
            } finally {
                loading = false
            }
        }
    }

    override fun start() {
        paused = false
        MPVLib.setPropertyBoolean("pause", false)
    }
    override fun pause() { paused = true; MPVLib.setPropertyBoolean("pause", true) }
    override fun stop() { if (initialized) mainHandler.post { MPVLib.command(arrayOf("stop")) } }
    override fun reset() {
        stop(); fileLoaded = false; isPrepared = false; eofReached = false; currentPositionMs = 0; durationMs = 0
        // 🔴 必须复位 paused：切集时若用户停在暂停态，新集 onPrepared→play() 前 paused 保持 true，
        //    isPlaying() 返回 false → togglePlayPause 永远走 play() → 播放/暂停键卡住无法暂停
        paused = false
    }

    override fun release() {
        if (released) return; released = true; mainHandler.removeCallbacksAndMessages(null)
        if (initialized) {
            MPVLib.removeObserver(this); MPVLib.removeLogObserver(this)
            setOpt("vo", "null"); MPVLib.detachSurface(); MPVLib.command(arrayOf("stop")); MPVLib.destroy()
        }
        initialized = false; surface = null; osdSurface = null; VideoLog.i("MPVLib released")
    }

    override fun seekTo(timeMs: Long) {
        if (initialized && fileLoaded) {
            currentPositionMs = timeMs; val target = (timeMs / 1000.0).toString()
            // 精确 seek：setPropertyDouble("time-pos") 语义精确到秒，不锁关键帧
            // （AfuseKtV MPVView.setTimePos 同款；seek absolute+keyframes 会落在目标前
            //   最近关键帧，网络流关键帧间隔 2~10s，按一下可能视觉原地不动）
            mainHandler.post { if (initialized && fileLoaded && !released) MPVLib.setPropertyDouble("time-pos", target.toDouble()) }
        }
    }

    override fun setSpeed(speed: Float) { MPVLib.setPropertyDouble("speed", speed.toDouble()) }
    override fun setVolume(l: Float, r: Float) { MPVLib.setPropertyDouble("volume", ((l + r) * 50.0).coerceIn(0.0, 100.0)) }
    override fun setLooping(v: Boolean) { MPVLib.setPropertyString("loop-file", if (v) "inf" else "no") }
    override fun setSubtitleOffset(ms: Long) { MPVLib.setPropertyDouble("sub-delay", ms / 1000.0) }
    override fun setAspectRatio(ratio: String) {
        // 还原 pan&scan（填充模式用 panscan 裁剪放大实现）
        MPVLib.setPropertyDouble("panscan", 0.0)
        if (ratio.isEmpty()) {
            MPVLib.setPropertyString("video-aspect-override", "-1")
        } else if (ratio == "fill") {
            // video-aspect-override 不支持 fill 值，用 panscan=1 填满屏幕（裁剪溢出）
            MPVLib.setPropertyString("video-aspect-override", "-1")
            MPVLib.setPropertyDouble("panscan", 1.0)
        } else {
            MPVLib.setPropertyString("video-aspect-override", ratio)
        }
    }

    override fun setZoom(scale: Float) {
        // MPV video-zoom: 0=原始, 每+1=2x放大, 每-1=0.5x缩小
        // 转换为对数刻度: log2(scale)
        val zoomValue = kotlin.math.log2(scale.toDouble())
        if (initialized && fileLoaded) {
            MPVLib.setPropertyDouble("video-zoom", zoomValue)
        }
    }

    override fun setAudioPassthrough(enabled: Boolean) {
        if (enabled) {
            setOpt("audio-spdif", "ac3,dts,dts-hd,eac3,truehd")
            setOpt("audio-exclusive", "yes")
            setOpt("audio-buffer", "0.2")
        } else {
            setOpt("audio-spdif", "no")
            setOpt("audio-exclusive", "no")
            setOpt("audio-buffer", "2")
        }
        VideoLog.i("MPV audio passthrough=$enabled")
    }

    override fun setFrameRateMatch(enabled: Boolean) {
        if (enabled) {
            setOpt("video-sync", "display-resample")
            setOpt("interpolation", "yes")
            setOpt("video-sync-max-video-change", "5")
        } else {
            setOpt("video-sync", "audio")
            setOpt("interpolation", "no")
        }
        VideoLog.i("MPV frame rate match=$enabled")
    }

    /** 解码模式切换：0=HW(mediacodec-copy) 1=HW+(mediacodec) 2=SW(no)，运行时应用 */
    override fun setHwdecMode(index: Int) {
        // hwdec 是 profile 级选项（须在着色器后设置），运行时用 property 切换
        val value = when (index) {
            1 -> "mediacodec"      // HW+
            2 -> "no"              // SW
            else -> "mediacodec-copy" // HW（默认可叠加 OSD/着色器）
        }
        try {
            MPVLib.setPropertyString("hwdec", value)
            hwdecActive = value
            VideoLog.i("MPV hwdec switched to $value (index=$index)")
        } catch (e: Exception) {
            VideoLog.e("MPV setHwdecMode failed: ${e.message}", e)
        }
    }

    /** 运行时字幕大小（实时生效） */
    override fun setSubtitleSize(size: Int) {
        if (size <= 0) return
        try {
            MPVLib.setPropertyString("sub-font-size", size.toString())
            VideoLog.i("MPV subtitle size -> $size")
        } catch (_: Exception) {}
    }

    /** 运行时字幕位置（实时生效） */
    override fun setSubtitlePosition(pos: Int) {
        if (pos < 0 || pos > 100) return
        try {
            MPVLib.setPropertyString("sub-pos", pos.toString())
            VideoLog.i("MPV subtitle pos -> $pos")
        } catch (_: Exception) {}
    }
    override fun isPlaying(): Boolean {
        // 🔴 用本地 paused 字段判断，不读 mpv pause 属性：
        //    mpv 开了 cache-pause=yes，网络缓冲时会自动把 pause 属性置 true，
        //    但视频实际在播（PLAYBACK_RESTART 已触发）→ 读属性得到 false 播放状态，
        //    导致 togglePlayPause 判断错误（按暂停实际调 play()，永远停不下来）。
        //    paused 仅在 start()/pause() 显式维护，语义 = 用户意图，不被缓冲干扰。
        if (!fileLoaded || eofReached || released) return false
        return !paused
    }
    override fun getCurrentPosition(): Long {
        if (currentPositionMs > 0) return currentPositionMs
        // fallback: 直接读 mpv time-pos 属性
        return try {
            val v = MPVLib.getPropertyDouble("time-pos")
            if (v != null && v >= 0) { currentPositionMs = (v * 1000).toLong(); currentPositionMs } else 0L
        } catch (_: Exception) { 0L }
    }
    override fun getDuration(): Long {
        if (durationMs > 0) return durationMs
        return try {
            val v = MPVLib.getPropertyDouble("duration")
            if (v != null && v > 0) { durationMs = (v * 1000).toLong(); durationMs } else 0L
        } catch (_: Exception) { 0L }
    }
    override fun getSpeed(): Float = MPVLib.getPropertyDouble("speed")?.toFloat() ?: 1f
    override fun getVideoSize(): Point = Point(videoSize)
    override fun getBufferedPercentage(): Int = bufferedPercentage
    override fun getTcpSpeed(): Long {
        // cache-speed 属性单位是 bytes/s（mpv 手册），直接返回，不要 *1024
        return MPVLib.getPropertyDouble("cache-speed")?.toLong() ?: 0L
    }
    override fun getTracks(type: TrackType): List<VideoTrackBean> = emptyList()

    override fun selectTrack(track: VideoTrackBean) {
        val id = track.id?.toIntOrNull() ?: return; if (!initialized) return
        val cmd = when (track.type) { TrackType.AUDIO -> "aid"; TrackType.SUBTITLE -> "sid"; TrackType.VIDEO -> "vid"; else -> return }
        VideoLog.i("MPVLib selectTrack: $cmd=$id")
        MPVLib.command(arrayOf("set", cmd, id.toString()))
    }

    override fun deselectTrack(type: TrackType) {
        if (!initialized) return
        val cmd = when (type) { TrackType.SUBTITLE -> "sid"; TrackType.AUDIO -> "aid"; else -> return }
        VideoLog.i("MPVLib deselectTrack: $cmd=no")
        MPVLib.setPropertyString(cmd, "no")
    }

    override fun getKernelInfo(): VideoKernelInfo {
        // hwdec-current 在软解时返回 "no"（非空），必须排除才能正确显示"软解"
        val isHw = hwdecActive.isNotBlank() && hwdecActive != "no"
        val decodeMode = if (isHw) "硬解" else "软解"
        val fmt = when { videoFormatName.contains("dv") || videoFormatName.contains("dovi") -> "DV"; videoFormatName.isNotBlank() -> videoFormatName.uppercase(); else -> "" }
        return VideoKernelInfo(decodeMode, fmt, videoCodecName)
    }

    override fun eventProperty(name: String) {
            // NONE 格式回调：手动读取所有属性值
            when (name) {
                "time-pos" -> { val v = MPVLib.getPropertyDouble("time-pos"); if (v != null && v >= 0) currentPositionMs = (v * 1000).toLong() }
                "duration" -> { val v = MPVLib.getPropertyDouble("duration"); if (v != null && v > 0) durationMs = (v * 1000).toLong() }
                "demuxer-cache-time" -> {
                    val end = MPVLib.getPropertyDouble("demuxer-cache-time/seekable-end") ?: return
                    if (end > 0) { val dur = durationMs / 1000.0; if (dur > 0) bufferedPercentage = ((end / dur) * 100).toInt().coerceIn(0, 100) }
                }
                "video-params" -> {
                    val w = MPVLib.getPropertyInt("video-params/w") ?: 0; val h = MPVLib.getPropertyInt("video-params/h") ?: 0
                    if (w > 0 && h > 0) { videoSize = Point(w, h); mPlayerEventListener.onVideoSizeChange(w, h) }
                }
                "track-list" -> {
                    val count = MPVLib.getPropertyInt("track-list/count") ?: 0
                    VideoLog.i("MPV track-list: count=$count")
                    val tracks = (0 until count).mapNotNull { i ->
                        val type = MPVLib.getPropertyString("track-list/$i/type") ?: return@mapNotNull null
                        val id = MPVLib.getPropertyInt("track-list/$i/id")?.toString() ?: return@mapNotNull null
                        val lang = MPVLib.getPropertyString("track-list/$i/lang")
                        val title = MPVLib.getPropertyString("track-list/$i/title") ?: lang ?: id
                        val selected = MPVLib.getPropertyBoolean("track-list/$i/selected") ?: false
                        val trackType = when (type) {
                            "video" -> TrackType.VIDEO
                            "audio" -> TrackType.AUDIO
                            "sub" -> TrackType.SUBTITLE
                            else -> { VideoLog.e("MPV unknown track type: $type"); null }
                        } ?: return@mapNotNull null
                        VideoLog.i("MPV track[$i] type=$type→${trackType.name}(ord=${trackType.ordinal}) id=$id lang=$lang selected=$selected")
                        val typeValue = when (trackType) {
                            TrackType.VIDEO -> androidx.media3.common.C.TRACK_TYPE_VIDEO
                            TrackType.AUDIO -> androidx.media3.common.C.TRACK_TYPE_AUDIO
                            TrackType.SUBTITLE -> androidx.media3.common.C.TRACK_TYPE_TEXT
                        }
                        val info = KernelTrackInfo(typeValue, id, title, lang, selected = selected, support = androidx.media3.common.C.FORMAT_HANDLED)
                        VideoLog.i("KernelTrackInfo type=${info.trackType} id=${info.id} label=${info.label}")
                        info
                    }
                    mPlayerEventListener.onTracksChanged(KernelTrackReport(tracks))
                }
                "paused-for-cache" -> {
                    if (MPVLib.getPropertyBoolean("paused-for-cache") == true)
                        mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_BUFFERING_START, 0)
                    else
                        mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_BUFFERING_END, 0)
                }
                "hwdec-current" -> { val v = MPVLib.getPropertyString("hwdec-current"); if (!v.isNullOrBlank()) hwdecActive = v }
                "hwdec-active" -> { val v = MPVLib.getPropertyString("hwdec-active"); if (!v.isNullOrBlank()) hwdecActive = v }
                "video-format" -> videoFormatName = MPVLib.getPropertyString("video-format") ?: ""
                "video-codec" -> videoCodecName = MPVLib.getPropertyString("video-codec") ?: ""
            }
        }
    override fun eventProperty(name: String, value: Long) {}
    override fun eventProperty(name: String, value: Boolean) {}
    override fun eventProperty(name: String, value: String) {}
    override fun eventProperty(name: String, value: Double) {}
    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                // 🔴 新文件开始：复位 eofReached，否则旧文件延迟到达的 END_FILE 事件
                //    会把 eofReached 置 true → isPlaying() 恒 false → 暂停键卡死（切集后无法暂停）
                VideoLog.i("MPVLib START_FILE")
                eofReached = false
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                isPrepared = true
                // 🔴 FILE_LOADED 再次复位 eofReached：旧文件 END_FILE 可能在 START_FILE 之后才到达，
                //    覆盖上面的复位 → 新集 eofReached 恒 true → isPlaying() false → 暂停键卡死
                eofReached = false
                VideoLog.i("MPVLib file loaded")
                addRemoteSubtitles()
                mPlayerEventListener.onPrepared()
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> { eofReached = true; VideoLog.i("MPVLib END_FILE"); mPlayerEventListener.onCompletion() }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> { VideoLog.i("MPVLib PLAYBACK_RESTART"); mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START, 0) }
        }
    }

    // ==================== MPVLib LogObserver ====================
    override fun logMessage(prefix: String, level: Int, text: String) {
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) VideoLog.e("MPVLib[$prefix]: $text")
    }
}