package com.embytv.view

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.embytv.BuildConfig
import com.embytv.R
import com.embytv.api.EmbyClient
import com.embytv.api.EmbyMediaSource
import com.embytv.api.EmbyPerson
import com.embytv.app.PreferencesHelper
import com.embytv.danmu.DanmuApiClient
import com.embytv.danmu.DanmuItem
import com.embytv.danmu.DanmuSearchPanel
import com.embytv.danmu.DanmakuManager
import com.embytv.player.PlayerViewModel
import com.embytv.player.model.KernelTrackInfo
import com.embytv.player.model.PlayerType
import com.embytv.player.model.TrackType
import com.embytv.player.surface.MpvOsdSurfaceView
import com.embytv.player.surface.RenderSurfaceView
import com.google.android.material.slider.Slider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var viewModel: PlayerViewModel
    private val mainHandler = Handler(Looper.getMainLooper())

    // ====== 控制栏自动隐藏（AfuseKtV b1/aVar 模式） ======
    private var controlsVisible = false
    private val autoHideRunnable = Runnable { hideControls() }

    // ====== 方向键加速 seek（AfuseKtV W1/Q1/speedUpRunnable 模式） ======
    private var isSeeking = false           // Q1
    private var seekSpeed = 1               // W1
    private val maxSeekSpeed = 16           // X1
    private val speedUpRunnable = object : Runnable {
        override fun run() {
            if (isSeeking) {
                seekSpeed = (seekSpeed * 2).coerceAtMost(maxSeekSpeed)
                mainHandler.postDelayed(this, 3000L)
            }
        }
    }
    private var isFastSpeed = false         // R1（长按 OK 2x 倍速）

    // ====== 横滑手势 ======
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureSeekAccum = 0f
    private var isHorizGesture = false
    private var isVolGesture = false
    private var isBrightnessGesture = false
    private var dragPreviewPosMs: Long? = null

    // ====== 双击 ======
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    // ====== 长按加速 ======
    private val longPressRunnable = Runnable {
        if (System.currentTimeMillis() - touchDownTime >= 400 && viewModel.state.value.isPlaying) {
            isLongPressing = true
            originalSpeed = viewModel.state.value.currentSpeed
            viewModel.setSpeed(2.0f)
            showGesture("2×")
        }
    }
    private var isLongPressing = false
    private var touchDownTime = 0L
    private var originalSpeed = 1.0f

    // ====== 倍速循环 ======
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 2

    // ====== 多版本 ======
    private var mediaSources = listOf<EmbyMediaSource>()
    private var playSessionId = ""

    // ====== Emby 服务器（类级：切集后 refreshMediaInfo 需要） ======
    private var embyBaseUrl = ""
    private var embyApiKey = ""

    // ====== 弹幕 ======
    private lateinit var danmakuManager: DanmakuManager
    private val danmuApi = DanmuApiClient()
    private var danmuLoaded = false
    private var danmuSearchPanel: DanmuSearchPanel? = null
    private var lastDanmuSeekMs = 0L

    // ====== 定时关机 ======
    private var sleepTimerRunnable: Runnable? = null

    // ====== 选集列表 ======
    private var episodeAdapter: RecyclerView.Adapter<*>? = null

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            hideSystemUI()
            setContentView(R.layout.activity_player)

            // 版本标签
            findViewById<TextView>(R.id.versionTag)?.text = "v${BuildConfig.VERSION_NAME}"

            val itemId = intent.getStringExtra("itemId") ?: ""
            val title = intent.getStringExtra("title") ?: ""
            val embyBaseUrl = intent.getStringExtra("embyBaseUrl") ?: ""
            val embyApiKey = intent.getStringExtra("embyApiKey") ?: ""
            this.embyBaseUrl = embyBaseUrl
            this.embyApiKey = embyApiKey
            val resumePos = intent.getLongExtra("position", 0L)
            val fallbackSource = intent.getStringExtra("source") ?: ""
            val queueJson = intent.getStringExtra("queue") ?: ""

            val skipPrev = findViewById<ImageView>(R.id.skip_previous)
            val skipNext = findViewById<ImageView>(R.id.skip_next)
            val otherSelectEp = findViewById<View>(R.id.other_select_ep)
            val episodeList = findViewById<RecyclerView>(R.id.other_episode_list)

            viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]

            // ====== 初始化弹幕 ======
            val danmuView = findViewById<master.flame.danmaku.ui.widget.DanmakuView>(R.id.danmakuView)
            danmakuManager = DanmakuManager(danmuView)
            danmakuManager.init()
            val danmuStatus = findViewById<ImageView>(R.id.other_danmu_status)
            val danmuPrefs = PreferencesHelper(this)
            val danmuPrefOn = danmuPrefs.prefsBoolean("danmu_enabled", false)
            if (danmuPrefOn) {
                danmakuManager.setEnabled(true)
                danmuStatus.setImageResource(R.drawable.dan_open)
            } else {
                danmakuManager.setEnabled(false)
                danmuStatus.setImageResource(R.drawable.dan_close)
            }
            findViewById<View>(R.id.other_danmu_btn).setOnClickListener {
                val enabling = !danmakuManager.isEnabled()
                danmakuManager.setEnabled(enabling)
                danmuStatus.setImageResource(if (enabling) R.drawable.dan_open else R.drawable.dan_close)
                danmuPrefs.setPrefsBoolean("danmu_enabled", enabling)
                if (enabling && !danmuLoaded) loadDanmuForCurrentItem()
            }
            // 长按弹幕按钮 → 手动搜索（自动匹配失败时的补救入口）
            findViewById<View>(R.id.other_danmu_btn).setOnLongClickListener {
                showDanmuSearchDialog()
                true
            }
            if (danmuPrefOn) {
                lifecycleScope.launch {
                    delay(1500)
                    if (danmuPrefOn && !danmuLoaded) loadDanmuForCurrentItem()
                }
            }

            // ====== 初始化 Surface ======
            val renderSurface = findViewById<RenderSurfaceView>(R.id.renderSurface)
            val mpvOsdSurface = findViewById<MpvOsdSurfaceView>(R.id.mpvOsdSurface)
            renderSurface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    viewModel.setSurface(holder.surface)
                    viewModel.play()
                }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {
                    viewModel.setSurfaceSize(w, h)
                }
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    viewModel.clearSurface()
                }
            })
            mpvOsdSurface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                    viewModel.setOsdSurface(holder.surface)
                }
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                    viewModel.setOsdSurface(null)
                }
            })

            // ====== 加载播放 ======
            val streamUrl = if (itemId.isNotEmpty() && embyBaseUrl.isNotEmpty()) {
                "${embyBaseUrl}/emby/Videos/$itemId/stream?static=true&api_key=$embyApiKey"
            } else fallbackSource

            val prefs = PreferencesHelper(this)
            val server = prefs.activeEmbyServer()

            lifecycleScope.launch {
                var finalUrl = streamUrl
                if (itemId.isNotEmpty() && embyBaseUrl.isNotEmpty()) {
                    val userId = server?.userId ?: ""
                    val info = EmbyClient().getPlaybackInfo(embyBaseUrl, embyApiKey, userId, itemId)
                    info.onSuccess { pbi ->
                        mediaSources = pbi.mediaSources
                        playSessionId = pbi.playSessionId
                        val ms = pbi.mediaSources.firstOrNull()
                        if (ms != null) {
                            val deviceId = "embytv_${android.os.Build.MODEL.replace(" ", "_")}"
                            finalUrl = "$embyBaseUrl/emby/Videos/$itemId/stream.${ms.container.lowercase()}?DeviceId=$deviceId&MediaSourceId=${ms.id}&PlaySessionId=${pbi.playSessionId}&Static=true&api_key=$embyApiKey"
                        }
                        if (pbi.mediaSources.size > 1) {
                            findViewById<TextView>(R.id.other_version_btn)?.visibility = View.VISIBLE
                        }
                    }.onFailure { e ->
                        android.util.Log.w("PlayerActivity", "PlaybackInfo failed, use fallback: ${e.message}")
                    }
                }
                val headers = if (embyApiKey.isNotEmpty()) mapOf("X-Emby-Token" to embyApiKey) else emptyMap()
                viewModel.setEmbyContext(embyBaseUrl, embyApiKey, itemId)
                // 加载外挂字幕（从 PlaybackInfo 的 MediaStreams 中提取文本字幕流 → Stream.srt URL）
                val subtitleUrls = mutableListOf<Pair<String, String>>()
                if (itemId.isNotEmpty() && embyBaseUrl.isNotEmpty()) {
                    val ms = mediaSources.firstOrNull()
                    if (ms != null) {
                        val streams = ms.mediaStreams ?: emptyList()
                        streams.forEach { stream ->
                            val type = stream["Type"] as? String ?: ""
                            if (type != "Subtitle") return@forEach
                            val isText = stream["IsTextSubtitleStream"] as? Boolean ?: false
                            if (!isText) return@forEach
                            val index = (stream["Index"] as? Number)?.toInt() ?: return@forEach
                            val isExternal = stream["IsExternal"] as? Boolean ?: false
                            val lang = stream["Language"] as? String ?: ""
                            val title = stream["DisplayTitle"] as? String ?: "字幕${index + 1}"
                            val codec = (stream["Codec"] as? String ?: "").lowercase()
                            val isAss = codec == "ass" || codec == "ssa" || title.contains(".ass", ignoreCase = true)
                            val base = if (embyBaseUrl.endsWith("/")) embyBaseUrl else "$embyBaseUrl/"
                            // ASS 拓展已启用：ASS/SSA 用原始 Stream.ass 交给 libass 渲染（保留样式特效）。
                            // 注意 mime 必须用 text/x-ssa：AssSubtitleParserFactory.create() 只认
                            // sampleMimeType=="text/x-ssa"（application/x-ass 不会被 ass 解析器处理）。
                            // 其余文本字幕统一转 Stream.srt（ExoPlayer 原生支持）。
                            val fmt = if (isAss) "ass" else "srt"
                            val mime = if (isAss) "text/x-ssa" else "application/x-subrip"
                            val subUrl = "${base}emby/Videos/$itemId/${ms.id}/Subtitles/$index/Stream.$fmt?api_key=$embyApiKey"
                            subtitleUrls.add(subUrl to mime)
                            android.util.Log.i("Subtitle", "外挂字幕: $title ($lang) idx=$index isExternal=$isExternal fmt=$fmt url=$subUrl")
                        }
                    }
                }
                viewModel.setMedia(finalUrl, title, headers, subtitleUrls.ifEmpty { null })
                if (queueJson.isNotBlank()) {
                    try {
                        val type = object : com.google.gson.reflect.TypeToken<MutableList<com.google.gson.JsonObject>>() {}.type
                        val list = com.google.gson.Gson().fromJson<List<com.google.gson.JsonObject>>(queueJson, type)
                        val pairList = list.mapIndexedNotNull { idx, obj ->
                            val u = obj.get("url")?.asString ?: return@mapIndexedNotNull null
                            val t = obj.get("title")?.asString ?: ""
                            u to t
                        }
                        val startIdx = pairList.indexOfFirst { it.first == finalUrl || it.first.contains(itemId) }.coerceAtLeast(0)
                        viewModel.setPlayQueue(pairList, startIdx)
                        if (pairList.size > 1) {
                            skipPrev?.visibility = View.VISIBLE
                            skipNext?.visibility = View.VISIBLE
                            otherSelectEp?.visibility = View.VISIBLE
                            setupEpisodeList(episodeList, pairList)
                        }
                    } catch (_: Exception) {}
                }
                // 始终设置选集列表的 PivotLayoutManager，防止单集时打开面板触发 DpadRecyclerView 崩溃
                val epListRv = findViewById<RecyclerView>(R.id.other_episode_list)
                if (epListRv != null && epListRv.layoutManager == null) {
                    val props = RecyclerView.LayoutManager.Properties().apply { orientation = RecyclerView.HORIZONTAL }
                    epListRv.layoutManager = com.rubensousa.dpadrecyclerview.layoutmanager.PivotLayoutManager(props)
                }
                viewModel.setAudioPassthrough(prefs.prefsBoolean("player_audio_passthrough", false))
                viewModel.setFrameRateMatch(prefs.prefsBoolean("player_frame_rate_match", false))
                // 默认播放器：读设置（EXO/MPV），尊重用户选择
                val defaultPlayer =
                    if (prefs.embyPreferredPlayer.equals("mpv", ignoreCase = true)) PlayerType.TYPE_MPV_PLAYER
                    else PlayerType.TYPE_EXO_PLAYER
                viewModel.initPlayer(this@PlayerActivity, defaultPlayer)
                // 应用全局 ASS 字幕字号（持久化：用户调一次，以后所有播放自动生效）
                viewModel.setSubtitleSize(prefs.subtitleFontSize)
                // PGS 位图字幕同样应用全局缩放
                applyPgsSubtitleScale(prefs.subtitleFontSize)
                // ASS 字幕拓展：EXO 内核用 libass 渲染 ASS/SSA 字幕（叠加层）
                val assContainer = findViewById<android.view.ViewGroup>(R.id.assSubtitleContainer)
                if (assContainer != null) viewModel.attachAssSubtitleView(assContainer)
                if (resumePos > 0) {
                    delay(300)
                    viewModel.seekTo(resumePos)
                }
            }

            // ====== 状态订阅 ======
            val videoName = findViewById<TextView>(R.id.videoName)!!
            val playPauseBtn = findViewById<ImageView>(R.id.play_pause)!!
            val seekBar = findViewById<SeekBar>(R.id.seekBar)!!
            val progressNow = findViewById<TextView>(R.id.progress_now)!!
            val progressAll = findViewById<TextView>(R.id.progress_all)!!
            val speedText = findViewById<TextView>(R.id.speed_text)!!
            val loadingContainer = findViewById<View>(R.id.loading)!!
            val kernelInfoText = findViewById<TextView>(R.id.kernelInfoText)!!
            val gestureTextView = findViewById<TextView>(R.id.gestureTextView)!!
            val subtitleText = findViewById<TextView>(R.id.subtitleText)!!

            videoName.text = title
            val decodeModes = listOf("HW", "HW+", "SW")

            lifecycleScope.launch {
                viewModel.state.collect { s ->
                    // 切集后标题跟随更新（切集时 state.mediaTitle 变化，videoName 不再卡旧标题）
                    if (s.mediaTitle.isNotBlank()) videoName.text = s.mediaTitle
                    playPauseBtn.setImageResource(if (s.isPlaying) R.drawable.icon_pause_btn else R.drawable.icon_play_btn)
                    loadingContainer.visibility = if (s.isLoading || s.isBuffering) View.VISIBLE else View.GONE
                    speedText.text = "X${"%.1f".format(s.currentSpeed)}"

                    // ===== 视频宽高比自适应（AspectRatioFrameLayout，官方 PlayerView 同款）=====
                    // 根因：SurfaceView 的最终显示比例由 view 矩形决定，videoScalingMode/dimensionRatio 均无效。
                    // 解法：外层 AspectRatioFrameLayout 自我重测为视频比例 → surface view 矩形=视频比例 → 不变形。
                    // - EXO：setAspectRatio(视频比例)，2.4:1 自动上下黑边
                    // - MPV：setAspectRatio(0) 恢复全屏，MPV 自己 letterbox
                    val vf = findViewById<androidx.media3.ui.AspectRatioFrameLayout>(R.id.videoFrame)
                    val exoActive = s.currentPlayerType == PlayerType.TYPE_EXO_PLAYER
                    val targetAspect = if (exoActive && s.videoWidth > 0 && s.videoHeight > 0) {
                        s.videoWidth.toFloat() / s.videoHeight
                    } else {
                        0f // MPV 或尺寸未知 → 全屏
                    }
                    // AspectRatioFrameLayout 无 getter，直接 set（幂等，开销极小）
                    vf.setAspectRatio(targetAspect)
                    android.util.Log.i(
                        "SynoPlayer",
                        "videoFrame aspect: player=${s.currentPlayerType} ${s.videoWidth}x${s.videoHeight} -> $targetAspect"
                    )

                    mpvOsdSurface.visibility = if (s.currentPlayerType == PlayerType.TYPE_MPV_PLAYER) View.VISIBLE else View.GONE
                    findViewById<View>(R.id.assSubtitleContainer)?.visibility =
                        if (s.currentPlayerType == PlayerType.TYPE_EXO_PLAYER) View.VISIBLE else View.GONE

                    val info = s.kernelInfo
                    val parts = mutableListOf<String>()
                    if (info.decodeMode.isNotEmpty() && info.decodeMode != "未知") parts.add(info.decodeMode)
                    if (info.videoFormat.isNotEmpty()) parts.add(info.videoFormat)
                    if (info.codec.isNotEmpty()) parts.add(info.codec)
                    // 只更新文本内容，显隐由 showControls/hideControls 控制（与控制栏同层级，一起出现一起隐藏）
                    kernelInfoText.text = parts.joinToString(" ")

                    // 右上角显示缓冲速度 MB/s（随控制栏显隐, 避免进度条隐藏后残留）
                    val versionTag = findViewById<TextView>(R.id.versionTag)
                    if (info.bufferSpeedKBps > 0 && controlsVisible) {
                        versionTag?.text = "%.1f MB/s".format(info.bufferSpeedKBps / 1024f)
                        versionTag?.visibility = View.VISIBLE
                    } else {
                        versionTag?.visibility = View.GONE
                    }

                    if (!s.subtitleText.isNullOrBlank()) {
                        subtitleText.text = s.subtitleText
                        subtitleText.visibility = View.VISIBLE
                    } else {
                        subtitleText.visibility = View.GONE
                    }
                    // PGS 位图字幕（EXO bitmap cue）：显示在 assSubtitleContainer 底部
                    // 归一化：所有 PGS 位图统一显示为屏幕高度的固定百分比（字形高度一致），
                    // 不依赖位图原始尺寸 → 不同影片字幕视觉大小一致
                    val bitmapSub = findViewById<ImageView>(R.id.bitmapSubtitle)
                    if (s.subtitleBitmap != null) {
                        bitmapSub?.setImageBitmap(s.subtitleBitmap)
                        bitmapSub?.visibility = View.VISIBLE
                        val bmp = s.subtitleBitmap
                        if (bmp != null && !bmp.isRecycled && bmp.height > 0) {
                            val screenH = resources.displayMetrics.heightPixels
                            val targetH = (screenH * 0.10f).toInt().coerceAtLeast(20) // 字幕高约屏幕 10%
                            val lp = bitmapSub.layoutParams
                            lp.height = targetH
                            // 宽度按位图宽高比自动缩放（fitCenter + wrap_content 会保持比例）
                            bitmapSub.layoutParams = lp
                        }
                    } else {
                        bitmapSub?.setImageDrawable(null)
                        bitmapSub?.visibility = View.GONE
                    }

                    findViewById<ImageView>(R.id.other_repeat_status)?.setImageResource(
                        if (s.isLooping) R.drawable.exo_icon_repeat_all else R.drawable.exo_icon_repeat_off
                    )
                    // 字幕按钮状态显示（选中轨/无字幕）
                    val subs = s.availableTracks.filter { TrackType.fromMedia3Type(it.trackType) == TrackType.SUBTITLE }
                    val subSelected = subs.any { it.selected }
                    findViewById<TextView>(R.id.other_subtitle_btn)?.text = if (subSelected) "字幕 ✓" else "字幕"
                    // 音轨按钮状态
                    val audios = s.availableTracks.filter { TrackType.fromMedia3Type(it.trackType) == TrackType.AUDIO }
                    val audioSelected = audios.any { it.selected }
                    findViewById<TextView>(R.id.other_audio_btn)?.text = if (audioSelected) "音轨 ✓" else "音轨"

                    // 解码模式按钮文本
                    findViewById<TextView>(R.id.other_hwdec_text)?.text = "解码 ${if (info.decodeMode.isNotEmpty() && info.decodeMode != "未知") info.decodeMode else "HW"}"
                    // 内核按钮文本
                    findViewById<TextView>(R.id.other_kernel_btn)?.text =
                        if (s.currentPlayerType == PlayerType.TYPE_MPV_PLAYER) "MPV" else "EXO"
                }
            }

            lifecycleScope.launch {
                viewModel.positionMs.collect { pos ->
                    val p = viewModel.state.value
                    val displayPos = dragPreviewPosMs ?: pos
                    if (p.durationMs > 0) {
                        seekBar.progress = (displayPos / 1000).toInt()
                        seekBar.max = (p.durationMs / 1000).toInt()
                        progressNow.text = formatTime(displayPos)
                        progressAll.text = formatTime(p.durationMs)
                    }
                    // 🔴 弹幕时间轴：不再每 500ms seek（DanmakuFlameMaster seekTo 会清空当前屏
                    //    弹幕再跳转，周期调用 = 弹幕刚滚动就被清掉重绘 → "只动一下就被刷新"）。
                    //    改为：加载时一次性对齐（见 loadDanmu 成功分支）+ 用户主动跳转时同步
                    //    （方向键快进/双击 ±10s/拖进度条/横滑松手，各自 seek 后调 syncDanmu）。
                    //    连续播放时弹幕用自己的时钟自然滚动，与播放位置天然同步。
                }
            }

            // ====== 底部栏按钮事件 ======
            playPauseBtn.setOnClickListener { viewModel.togglePlayPause(); showControls() }
            speedText.setOnClickListener { cycleSpeed(); showControls() }
            // 🔴 切集后必须把焦点移回播放/暂停按钮：showControls() 在 controlsVisible=true 时会
            //    直接 return（焦点不移动），导致焦点仍停留在 skipNext/skipPrev 上。
            //    用户随后按 OK 想暂停 → dispatchKeyEvent 的 CENTER 分支看到焦点是可点击按钮
            //    → performClick() 又触发 playNext/playPrevious → 暂停键永远无效（"卡在播放"）。
            skipPrev?.setOnClickListener {
                viewModel.playPrevious(); showControls()
                refreshEpisodeHighlight()
                findViewById<View>(R.id.play_pause)?.post { findViewById<View>(R.id.play_pause)?.requestFocus() }
            }
            skipNext?.setOnClickListener {
                viewModel.playNext(); showControls()
                refreshEpisodeHighlight()
                findViewById<View>(R.id.play_pause)?.post { findViewById<View>(R.id.play_pause)?.requestFocus() }
            }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        viewModel.seekTo(progress.toLong() * 1000)
                        if (::danmakuManager.isInitialized) danmakuManager.alignTo(progress.toLong() * 1000)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { showControls() }
            })

            // ====== 上滑面板按钮事件 ======
            findViewById<TextView>(R.id.other_subtitle_btn).setOnClickListener { showSubtitleSelection() }
            findViewById<TextView>(R.id.other_audio_btn).setOnClickListener { showAudioSelection() }
            findViewById<TextView>(R.id.other_speed_btn).setOnClickListener { cycleSpeed() }
            findViewById<TextView>(R.id.other_video_scale_btn).setOnClickListener { cycleAspect() }
            findViewById<View>(R.id.other_repeat_btn).setOnClickListener {
                viewModel.toggleLoop()
                showGesture(if (viewModel.state.value.isLooping) "单集循环开" else "单集循环关")
            }
            findViewById<View>(R.id.other_ffmpeg_btn).setOnClickListener { cycleHwdec() }
            findViewById<TextView>(R.id.other_kernel_btn).setOnClickListener { switchKernel() }

            // 星光影院：顶部返回按钮
            findViewById<android.view.View>(R.id.back_btn)?.setOnClickListener { finish() }
            findViewById<TextView>(R.id.other_set_btn).setOnClickListener { toggleSettingsPanel() }

            // 星光影院：字幕大小 OSD 直达（A+ 按钮 → 滑块面板，实时生效+持久化）
            val subSizeBtn = findViewById<TextView>(R.id.other_subsize_btn)
            val subSizePanel = findViewById<android.view.View>(R.id.subtitleSizePanel)
            val subSizeSeek = findViewById<android.widget.SeekBar>(R.id.subtitleSizeSeekBar)
            val subSizeVal = findViewById<TextView>(R.id.subSizeValue)
            val subSizeMinus = findViewById<TextView>(R.id.subSizeMinus)
            val subSizePlus = findViewById<TextView>(R.id.subSizePlus)

            fun applySubtitleSize(size: Int) {
                prefs.subtitleFontSize = size
                viewModel.setSubtitleSize(size)
                applyPgsSubtitleScale(size)
                subSizeVal.text = "${((size - 30) * 100 / 90)}%"
            }
            fun refreshSubSizePanel() {
                subSizeSeek.progress = prefs.subtitleFontSize - 30
                subSizeVal.text = "${((prefs.subtitleFontSize - 30) * 100 / 90)}%"
            }
            subSizeBtn.setOnClickListener {
                val show = subSizePanel.visibility != android.view.View.VISIBLE
                subSizePanel.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                if (show) {
                    refreshSubSizePanel()
                    subSizeSeek.requestFocus()
                }
            }
            subSizeSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) applySubtitleSize(progress + 30)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
            subSizeMinus.setOnClickListener {
                applySubtitleSize((prefs.subtitleFontSize - 5).coerceAtLeast(30))
                refreshSubSizePanel()
            }
            subSizePlus.setOnClickListener {
                applySubtitleSize((prefs.subtitleFontSize + 5).coerceAtMost(120))
                refreshSubSizePanel()
            }
            // 面板内按返回/下键关闭
            subSizePanel.setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    subSizePanel.visibility = android.view.View.GONE
                    subSizeBtn.requestFocus()
                    true
                } else false
            }
            findViewById<View>(R.id.other_timer_btn).setOnClickListener { showSleepTimerDialog() }
            findViewById<TextView>(R.id.other_version_btn).setOnClickListener { showVersionSelection() }

            kernelInfoText.setOnLongClickListener {
                showDecodeInfo(); true
            }

            findViewById<TextView>(R.id.other_config_tips)?.text = "解码按钮切换硬解/软解，内核按钮切换 EXO/MPV"
            findViewById<TextView>(R.id.other_extend_tip)?.text = "按 BACK 收起面板"

            setupGestures(gestureTextView)
            loadVideoInfo(itemId, title, embyBaseUrl, embyApiKey)

            showControls()
        } catch (e: Exception) {
            android.util.Log.e("PlayerActivity", "onCreate failed", e)
            Toast.makeText(this, "播放器初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ==================== 遥控器按键处理 ====================
    // AfuseKtV 模式但用 dispatchKeyEvent 拦截 UP/DOWN 防止 view 层级焦点导航失败导致退出
    // LEFT/RIGHT 仅在进度条聚焦时拦截，其余交由 view 层级处理焦点导航（保证底部按钮可聚焦）

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 🐞 诊断：每个按键都打日志，定位"切集后暂停/播放卡死"时焦点到底在哪
        try {
            val f = currentFocus
            android.util.Log.i("PlayerKey",
                "key=${event.keyCode} act=${event.action} rep=${event.repeatCount} " +
                "focus=${f?.javaClass?.simpleName ?: "null"}#${f?.id ?: -1} " +
                "shown=${f?.isShown ?: false} clickable=${f?.isClickable ?: false} " +
                "ctrl=${controlsVisible} panel=${findViewById<View>(R.id.panel_overlay)?.visibility} " +
                "bottom=${findViewById<View>(R.id.bottom_control)?.visibility}")
        } catch (_: Exception) {}
        // 🔴 弹幕搜索面板打开时：BACK/MENU 先关面板，不穿透到播放器
        if (danmuSearchPanel?.isVisible == true) {
            if (event.action == KeyEvent.ACTION_DOWN &&
                (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU)) {
                danmuSearchPanel?.close()
                return true
            }
            // 面板打开时 UP/DOWN/LEFT/RIGHT 交给面板内部焦点系统
            if (event.action == KeyEvent.ACTION_DOWN) {
                return super.dispatchKeyEvent(event)
            }
            return super.dispatchKeyEvent(event)
        }
        // ACTION_UP：仅复位 seek 加速状态 + 弹幕按钮长按/短按判定
        if (event.action == KeyEvent.ACTION_UP) {
            // 🔴 弹幕按钮: DOWN 分支记录了 tag 时间戳, 但 UP 在这里被 return 吞掉,
            //    578 行的 UP 分支永远不会执行 → 遥控器按 OK 弹幕按钮无响应.
            //    这里补上长按(手动搜索)/短按(切换开关)判定.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                val danmuBtn = findViewById<View>(R.id.other_danmu_btn)
                if (danmuBtn?.hasFocus() == true) {
                    val downTime = danmuBtn.tag as? Long ?: 0L
                    danmuBtn.tag = null
                    if (downTime > 0) {
                        if (System.currentTimeMillis() - downTime > 500) {
                            showDanmuSearchDialog()
                        } else {
                            danmuBtn.performClick()
                        }
                        return true
                    }
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (isSeeking) {
                    isSeeking = false
                    mainHandler.removeCallbacks(speedUpRunnable)
                    seekSpeed = 1
                    viewModel.seekTo(viewModel.currentPositionMs)
                }
                if (isFastSpeed) {
                    isFastSpeed = false
                    viewModel.setSpeed(1.0f)
                }
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        val keyCode = event.keyCode
        val panel = findViewById<View>(R.id.panel_overlay)
        val panelVisible = panel?.visibility == View.VISIBLE
        val setBox = findViewById<View>(R.id.play_view_for_set)
        val setBoxVisible = setBox?.visibility == View.VISIBLE
        val trackPanel = findViewById<View>(R.id.track_selection_view)
        val trackVisible = trackPanel?.visibility == View.VISIBLE
        val bottomBox = findViewById<View>(R.id.bottomBox)

        when (keyCode) {
            // ===== BACK：分层关闭，绝不误退出 =====
            KeyEvent.KEYCODE_BACK -> {
                if (setBoxVisible) { setBox.visibility = View.GONE; return true }
                if (trackVisible) { trackPanel.visibility = View.GONE; showControls(); return true }
                if (panelVisible) { hideOtherPanel(); return true }
                if (controlsVisible) { hideControls(); return true }
                // 全关 → 退出（交给系统）
                return super.dispatchKeyEvent(event)
            }

            // ===== DPAD：控制栏隐藏时唤起，其余交给系统焦点导航（Jellyfin 模式） =====
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // 轨道面板/设置面板打开时 → 不拦截 UP/DOWN，全部穿透给系统焦点导航
                if (trackVisible || setBoxVisible) {
                    return super.dispatchKeyEvent(event)
                }
                // 全关状态 → 唤起控制栏（第一下只是唤起）
                if (!controlsVisible && !panelVisible && !setBoxVisible && !trackVisible) {
                    showControls()
                    return true
                }
                // 面板开：UP 聚焦选集序列 / DOWN 焦点下移
                if (panelVisible) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        val epList = findViewById<RecyclerView>(R.id.other_episode_list)
                        if (epList?.isShown == true) {
                            val sub = findViewById<View>(R.id.other_subtitle_btn)
                            val audio = findViewById<View>(R.id.other_audio_btn)
                            val dan = findViewById<View>(R.id.other_danmu_btn)
                            val inFirstRow = sub?.isFocused == true || audio?.isFocused == true || dan?.isFocused == true
                            if (!inFirstRow) {
                                epList.post { epList.requestFocus() }
                                return true
                            }
                            return super.dispatchKeyEvent(event)
                        } else {
                            // 选集隐藏 → 回第一行第一个按钮
                            findViewById<View>(R.id.other_subtitle_btn)?.post { findViewById<View>(R.id.other_subtitle_btn)?.requestFocus() }
                            return true
                        }
                    } else {
                        // DOWN 在面板内 → 穿透给系统，焦点下移到第二行按钮
                        return super.dispatchKeyEvent(event)
                    }
                }
                // 控制栏可见、无面板：UP → 开上滑面板（两级展开）
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    showOtherPanel()
                    return true
                }
                // DOWN：穿透给系统（View 层级焦点下移 bottomBox → play_pause → …）
                return super.dispatchKeyEvent(event)
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // 全关状态 → 唤起控制栏 + 立即 seek（按住自动加速）
                if (!controlsVisible && !panelVisible && !setBoxVisible && !trackVisible) {
                    showControls()
                }
                // 进度条聚焦，或控制栏刚唤起（焦点尚未落位）→ seek
                if (bottomBox?.isFocused == true || !controlsVisible) {
                    if (!isSeeking) {
                        isSeeking = true
                        seekSpeed = 1
                        mainHandler.postDelayed(speedUpRunnable, 3000L)
                    }
                    val seekBar = findViewById<SeekBar>(R.id.seekBar)
                    if (seekBar != null) {
                        val stepMs = PreferencesHelper(this@PlayerActivity).playerSeekSeconds * 1000
                        val delta = seekSpeed * stepMs / 1000
                        seekBar.progress = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                            (seekBar.progress + delta).coerceAtMost(seekBar.max)
                        else (seekBar.progress - delta).coerceAtLeast(0)
                        viewModel.seekTo(seekBar.progress * 1000L)
                        if (::danmakuManager.isInitialized) danmakuManager.alignTo(seekBar.progress * 1000L)
                    }
                    showControls()
                    return true
                }
                // 其余：穿透给系统（横向焦点导航）
                return super.dispatchKeyEvent(event)
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // 焦点在弹幕按钮：长按 → 手动搜索弹幕（区分点击/长按，因上方分支会吞掉长按事件）
                val danmuBtn = findViewById<View>(R.id.other_danmu_btn)
                if (danmuBtn?.hasFocus() == true && !danmuBtn.isShown) {
                    // 面板关闭时弹幕按钮不可见，不拦截
                } else if (danmuBtn?.hasFocus() == true) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        danmuBtn.tag = System.currentTimeMillis()
                        return true
                    }
                    if (event.action == KeyEvent.ACTION_UP) {
                        val downTime = danmuBtn.tag as? Long ?: 0L
                        val isLong = System.currentTimeMillis() - downTime > 500
                        if (isLong) {
                            showDanmuSearchDialog()
                        } else {
                            danmuBtn.performClick()
                        }
                        danmuBtn.tag = null
                        return true
                    }
                    return true
                }
                // 焦点在可点击按钮上 → 直接触发点击，不依赖系统事件分发
                // （修复第二行 FFmpeg/设置/定时关机/版本 按 OK 退回详情页）
                // 🔴 isShown 守卫：选集后面板已 GONE，焦点可能仍停在隐藏的选集项上，
                //    若此时 performClick → onClick 里 playAtQueueIndex(同index) 被短路 →
                //    事件被吞、播放器看起来"卡死"。只有真正可见的焦点项才执行点击。
                val f = currentFocus
                if (f != null && f.isShown && (f.isClickable || f is androidx.recyclerview.widget.RecyclerView)) {
                    f.performClick()
                    return true
                }
                // 无焦点 / 焦点在不可点击元素 → 播放/暂停；长按 2 次 → x2 倍速
                if (event.repeatCount == 0) {
                    viewModel.togglePlayPause()
                } else if (event.repeatCount == 2) {
                    isFastSpeed = true
                    viewModel.setSpeed(2.0f)
                    showGesture("X2 倍速")
                }
                showControls()
                return true
            }

            // ===== 媒体键 =====
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount == 0) viewModel.togglePlayPause()
                showControls()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                viewModel.playNext(); return true
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                viewModel.playPrevious(); return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    // ==================== 控制栏显示/隐藏（AfuseKtV b1 模式） ====================

    private fun showControls() {
        if (controlsVisible) return
        controlsVisible = true
        mainHandler.removeCallbacks(autoHideRunnable)

        findViewById<View>(R.id.bottom_control)?.visibility = View.VISIBLE
        findViewById<View>(R.id.name_box)?.visibility = View.VISIBLE
        findViewById<View>(R.id.timeTextView)?.visibility = View.VISIBLE
        findViewById<View>(R.id.speedTextView)?.visibility = View.VISIBLE
        // 上键展开提示：仅控制栏可见、上滑面板未开时显示
        findViewById<View>(R.id.swipe_up_hint)?.let {
            if (findViewById<View>(R.id.panel_overlay)?.visibility != View.VISIBLE) it.visibility = View.VISIBLE
        }

        // 右上角解码信息：与控制栏同层级，有内容才显示
        findViewById<TextView>(R.id.kernelInfoText)?.let {
            if (it.text.isNotBlank()) it.visibility = View.VISIBLE
        }

        // 非 1x 倍速时显示 speedText
        val currentSpeed = viewModel.state.value.currentSpeed
        if (currentSpeed != 1.0f) {
            findViewById<TextView>(R.id.speed_text)?.let {
                it.visibility = View.VISIBLE
                it.text = "X${"%.1f".format(currentSpeed)}"
            }
        }

        // 焦点到进度条行
        findViewById<View>(R.id.bottomBox)?.post { findViewById<View>(R.id.bottomBox)?.requestFocus() }

        // 5s 自动隐藏（进度条层级连同控制栏一起隐藏，保持观影沉浸）
        mainHandler.postDelayed(autoHideRunnable, 5000L)
    }

    private fun hideControls() {
        if (!controlsVisible) return
        controlsVisible = false
        mainHandler.removeCallbacks(autoHideRunnable)

        // 控制栏连同进度条层级一起隐藏（观影沉浸：进度条不常驻）
        findViewById<View>(R.id.bottom_control)?.visibility = View.GONE
        findViewById<View>(R.id.name_box)?.visibility = View.GONE
        findViewById<View>(R.id.timeTextView)?.visibility = View.GONE
        findViewById<View>(R.id.speedTextView)?.visibility = View.GONE
        findViewById<View>(R.id.swipe_up_hint)?.visibility = View.GONE
        // 右上角解码信息 + 网速随控制栏一起隐藏（不留残留）
        findViewById<View>(R.id.kernelInfoText)?.visibility = View.GONE
        findViewById<View>(R.id.versionTag)?.visibility = View.GONE
    }

    private fun showOtherPanel() {
        val panel = findViewById<View>(R.id.panel_overlay) ?: return
        if (panel.visibility == View.VISIBLE) return
        panel.visibility = View.VISIBLE
        mainHandler.removeCallbacks(autoHideRunnable)
        findViewById<View>(R.id.bottom_control)?.visibility = View.GONE
        findViewById<View>(R.id.kernelInfoText)?.visibility = View.GONE
        findViewById<View>(R.id.swipe_up_hint)?.visibility = View.GONE
        // 关闭设置面板和轨道面板
        findViewById<View>(R.id.play_view_for_set)?.visibility = View.GONE
        findViewById<View>(R.id.track_selection_view)?.visibility = View.GONE
        // 焦点到第一个按钮
        findViewById<View>(R.id.other_subtitle_btn)?.post { findViewById<View>(R.id.other_subtitle_btn)?.requestFocus() }
    }

    private fun hideOtherPanel() {
        val panel = findViewById<View>(R.id.panel_overlay)
        if (panel?.visibility != View.VISIBLE) return
        panel.visibility = View.GONE
        // 面板打开时 controlsVisible 仍为 true，不 reset 则 showControls 空转
        controlsVisible = false
        showControls()
        // 🔴 强制把焦点还给播放/暂停按钮：面板 GONE 后焦点仍留在已隐藏的选集项上，
        //    导致后续 OK 键被 dispatchKeyEvent 的 isClickable→performClick 吞掉（播放器"卡死"）。
        findViewById<View>(R.id.play_pause)?.post {
            val cur = currentFocus
            // 焦点还在面板内（isPanelChild 会沿 parent 链识别，含 GONE 面板）→ 移回播放钮
            if (cur == null || isPanelChild(cur) || !cur.isShown) {
                findViewById<View>(R.id.play_pause)?.requestFocus()
            }
        }
    }

    private fun isPanelChild(view: View?): Boolean {
        if (view == null) return false
        val panel = findViewById<View>(R.id.panel_overlay) ?: return false
        var v: View? = view
        while (v != null && v !== panel) {
            val p = v.parent
            if (p !is View) return false
            v = p
        }
        return v === panel
    }

    // ==================== 设置面板 ====================

    private fun toggleSettingsPanel() {
        val panel = findViewById<View>(R.id.play_view_for_set) ?: return
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        findViewById<View>(R.id.track_selection_view)?.visibility = View.GONE
        val prefs = PreferencesHelper(this)

        val subPos = findViewById<Slider>(R.id.sub_pos_value)
        val subPosText = findViewById<TextView>(R.id.sub_pos_value_text)
        val posPref = Math.round(((prefs.subtitlePosition - 50) / 50f) * 100) / 100f
        subPos?.value = posPref.coerceIn(0f, 1f)
        subPosText?.text = "${prefs.subtitlePosition}%"
        subPos?.addOnChangeListener { _, value, _ ->
            val pos = (50 + (value * 50).toInt()).coerceIn(50, 100)
            viewModel.setSubtitlePosition(pos)
            prefs.subtitlePosition = pos
            subPosText?.text = "${pos}%"
        }

        val subScale = findViewById<Slider>(R.id.sub_scale_value)
        val subScaleText = findViewById<TextView>(R.id.sub_scale_value_text)
        // stepSize=1 时值必须为整数，round 防止 Material Slider 校验崩溃；valueFrom=1 所以下限取 1
        val scaleVal = Math.round(((prefs.subtitleFontSize - 30) / 90f * 100f).coerceIn(1f, 100f)).toFloat()
        subScale?.value = scaleVal
        subScaleText?.text = pctText(prefs.subtitleFontSize)
        subScale?.addOnChangeListener { _, value, _ ->
            val size = (30 + (value / 100f * 90f).toInt()).coerceIn(30, 120)
            viewModel.setSubtitleSize(size)
            prefs.subtitleFontSize = size
            subScaleText?.text = pctText(size)
            // PGS 位图字幕（无字号概念）用 View 缩放同步调整大小
            applyPgsSubtitleScale(size)
        }

        val subDelay = findViewById<Slider>(R.id.sub_delay_value)
        val subDelayText = findViewById<TextView>(R.id.sub_delay_value_text)
        subDelay?.addOnChangeListener { _, value, _ ->
            val ms = (value.toLong() * 1000)
            viewModel.setSubtitleOffset(ms)
            subDelayText?.text = "${value.toInt()}s"
        }

        val introStart = findViewById<Slider>(R.id.intro_start_time)
        val introStartText = findViewById<TextView>(R.id.intro_start_time_value)
        introStart?.value = prefs.playerIntroStartSeconds.toFloat().coerceIn(0f, 300f)
        introStartText?.text = "${prefs.playerIntroStartSeconds}s"
        introStart?.addOnChangeListener { _, value, _ ->
            val sec = value.toInt().coerceIn(0, 300)
            prefs.playerIntroStartSeconds = sec
            introStartText?.text = "${sec}s"
        }

        val introEnd = findViewById<Slider>(R.id.intro_end_time)
        val introEndText = findViewById<TextView>(R.id.intro_end_time_value)
        introEnd?.value = prefs.playerSkipIntroSeconds.toFloat().coerceIn(0f, 300f)
        introEndText?.text = "${prefs.playerSkipIntroSeconds}s"
        introEnd?.addOnChangeListener { _, value, _ ->
            val sec = value.toInt().coerceIn(0, 300)
            prefs.playerSkipIntroSeconds = sec
            viewModel.skipIntroSeconds = sec
            introEndText?.text = "${sec}s"
        }

        val endSkip = findViewById<Slider>(R.id.end_time)
        val endSkipText = findViewById<TextView>(R.id.end_time_value)
        endSkip?.value = prefs.playerEndSkipSeconds.toFloat().coerceIn(0f, 300f)
        endSkipText?.text = "${prefs.playerEndSkipSeconds}s"
        endSkip?.addOnChangeListener { _, value, _ ->
            val sec = value.toInt().coerceIn(0, 300)
            prefs.playerEndSkipSeconds = sec
            endSkipText?.text = "${sec}s"
        }

        val danmuSize = findViewById<Slider>(R.id.danmu_size)
        val danmuSizeDefault = prefs.prefsFloat("danmu_size", 100f)
        danmuSize?.value = danmuSizeDefault
        danmuSize?.addOnChangeListener { _, value, _ ->
            prefs.setPrefsFloat("danmu_size", value)
            danmakuManager.setTextScale(value / 100f)
        }

        val danmuSpeed = findViewById<Slider>(R.id.danmu_speed)
        danmuSpeed?.value = prefs.prefsFloat("danmu_speed", 2f)
        danmuSpeed?.addOnChangeListener { _, value, _ ->
            prefs.setPrefsFloat("danmu_speed", value)
            danmakuManager.setScrollSpeedFactor(value / 2f)
        }

        val danmuLines = findViewById<Slider>(R.id.danmu_lines)
        danmuLines?.value = prefs.prefsFloat("danmu_lines", 4f)
        danmuLines?.addOnChangeListener { _, value, _ ->
            prefs.setPrefsFloat("danmu_lines", value)
            danmakuManager.setMaxLines(value.toInt())
        }

        val danmuAlpha = findViewById<Slider>(R.id.dan_alpha)
        danmuAlpha?.value = prefs.prefsFloat("dan_alpha", 1f)
        danmuAlpha?.addOnChangeListener { _, value, _ ->
            prefs.setPrefsFloat("dan_alpha", value)
            danmakuManager.setAlpha(value)
        }
    }

    // ==================== 倍速/画面/内核 ====================

    private var aspectIndex = 0

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        viewModel.setSpeed(speeds[speedIndex])
        showGesture("倍速 X${"%.1f".format(speeds[speedIndex])}")
    }

    private fun cycleAspect() {
        val isMpv = viewModel.state.value.currentPlayerType == PlayerType.TYPE_MPV_PLAYER
        // EXO 不支持 16:9/4:3（均映射到 DEFAULT），只有 原始↔填充 真实有效
        // MPV 支持全部 4 种（video-aspect-override 实时生效）
        val list = if (isMpv) listOf("" to "原始", "16:9" to "16:9", "4:3" to "4:3", "fill" to "填充")
            else listOf("" to "原始", "fill" to "填充")
        aspectIndex = (aspectIndex + 1) % list.size
        val (ratio, label) = list[aspectIndex]
        viewModel.setAspectRatio(ratio)
        findViewById<TextView>(R.id.other_video_scale_btn)?.text = "画面 $label"
        showGesture("画面 $label")
    }

    private fun switchKernel() {
        val next = if (viewModel.state.value.currentPlayerType == PlayerType.TYPE_EXO_PLAYER)
            PlayerType.TYPE_MPV_PLAYER else PlayerType.TYPE_EXO_PLAYER
        try {
            viewModel.switchPlayer(this, next)
            showGesture("内核 ${next.displayName}")
        } catch (e: LinkageError) {
            // 记录完整堆栈到崩溃日志，便于定位 MPV 初始化失败的底层原因
            try {
                com.embytv.player.kernel.VideoLog.e("switchKernel LinkageError: ${e.message}", e)
            } catch (_: Exception) {}
            Toast.makeText(this, "MPV 内核不可用：${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "切换内核失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cycleHwdec() {
        // EXO 只有硬解/软解两态；MPV 才有 HW/HW+/SW 三态
        val isMpv = viewModel.state.value.currentPlayerType == PlayerType.TYPE_MPV_PLAYER
        val cur = viewModel.state.value.decodeModeIndex
        val next = if (isMpv) (cur + 1) % 3 else if (cur == 0) 2 else 0
        viewModel.setHwdecMode(next)
        val name = when (next) { 1 -> "HW+"; 2 -> "SW"; else -> "HW" }
        showGesture("解码 $name")
    }

    // ==================== 定时关机 ====================

    private fun showSleepTimerDialog() {
        val options = arrayOf("关闭定时", "30 分钟", "60 分钟", "90 分钟")
        android.app.AlertDialog.Builder(this)
            .setTitle("定时关机")
            .setItems(options) { _, which ->
                setSleepTimer(when (which) {
                    0 -> 0
                    1 -> 30
                    2 -> 60
                    else -> 90
                })
            }
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepTimerRunnable = null
        val status = findViewById<ImageView>(R.id.other_timer_status)
        if (minutes <= 0) {
            status?.visibility = View.GONE
            Toast.makeText(this, "已关闭定时关机", Toast.LENGTH_SHORT).show()
            return
        }
        status?.visibility = View.VISIBLE
        status?.setImageResource(R.drawable.ic_clock_black_24dp)
        Toast.makeText(this, "定时关机：$minutes 分钟后", Toast.LENGTH_SHORT).show()
        val runnable = Runnable {
            Toast.makeText(this@PlayerActivity, "定时关机", Toast.LENGTH_SHORT).show()
            finish()
        }
        sleepTimerRunnable = runnable
        mainHandler.postDelayed(runnable, minutes * 60_000L)
    }

    // ==================== 选集列表 ====================

    private fun setupEpisodeList(list: RecyclerView?, queue: List<Pair<String, String>>) {
        if (list == null) return
        // DpadRecyclerView 只接受 PivotLayoutManager（setLayoutManager 是 final 且强校验类型）
        if (list.layoutManager !is com.rubensousa.dpadrecyclerview.layoutmanager.PivotLayoutManager) {
            val props = RecyclerView.LayoutManager.Properties().apply { orientation = RecyclerView.HORIZONTAL }
            list.layoutManager = com.rubensousa.dpadrecyclerview.layoutmanager.PivotLayoutManager(props)
        }
        val adapter = EpisodeAdapter(queue) { idx ->
            val panel = findViewById<View>(R.id.panel_overlay)
            if (panel?.visibility == View.VISIBLE) hideOtherPanel()
            viewModel.playAtQueueIndex(idx)
            refreshEpisodeHighlight()
            // 🔴 选集后焦点回播放/暂停按钮：防止 OK 键被选集项/面板残留焦点吞掉
            findViewById<View>(R.id.play_pause)?.post { findViewById<View>(R.id.play_pause)?.requestFocus() }
        }
        adapter.currentIndex = viewModel.playQueueIndex
        episodeAdapter = adapter
        list.adapter = adapter
    }

    private fun refreshEpisodeHighlight() {
        (episodeAdapter as? EpisodeAdapter)?.let {
            it.currentIndex = viewModel.playQueueIndex
            it.notifyDataSetChanged()
        }
        // 🔴 切集后同步刷新上滑面板媒体信息（标题/简介/海报），否则一直显示进播放界面那一集
        refreshMediaInfo()
    }

    /** 切集后重新加载媒体信息（other_video_title/overview/海报跟随当前播放的集） */
    private fun refreshMediaInfo() {
        val queue = viewModel.playQueue
        val idx = viewModel.playQueueIndex
        if (idx < 0 || idx >= queue.size) return
        val (url, title) = queue[idx]
        val itemId = Regex("""Videos/([^/?]+)/stream""").find(url)?.groupValues?.get(1) ?: return
        if (itemId.isBlank()) return
        loadVideoInfo(itemId, title, embyBaseUrl, embyApiKey)
    }

    private inner class EpisodeAdapter(
        private val items: List<Pair<String, String>>,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<EpisodeHolder>() {
        var currentIndex: Int = 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_player_episode, parent, false)
            return EpisodeHolder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val (_, t) = items[position]
            val cur = position == currentIndex
            holder.text.text = "${if (cur) "▶ " else ""}${position + 1} $t"
            holder.text.setTextColor(if (cur) 0xFF34C759.toInt() else 0xFFFFFFFF.toInt())
            holder.itemView.setOnClickListener { onClick(position) }
        }
    }

    private class EpisodeHolder(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.episodeText)
    }

    // ==================== 演员列表 ====================

    private fun setupActorList(list: RecyclerView?, people: List<EmbyPerson>) {
        if (list == null || people.isEmpty()) return
        list.visibility = View.VISIBLE
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        val names = people.map { "${it.name}${if (it.role != null) " · ${it.role}" else ""}" }
        list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(p: ViewGroup, vt: Int): RecyclerView.ViewHolder {
                val tv = TextView(p.context).apply {
                    setTextColor(0xFFB3FFFFFF.toInt())
                    textSize = 12f
                    setPadding(12, 4, 12, 4)
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            override fun getItemCount() = names.size
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                (h.itemView as TextView).text = names[pos]
            }
        }
    }

    // ==================== 视频详情加载 ====================

    private fun loadVideoInfo(itemId: String, title: String, baseUrl: String, apiKey: String) {
        val videoName = findViewById<TextView>(R.id.videoName)
        videoName?.text = title
        if (itemId.isBlank() || baseUrl.isBlank()) return
        lifecycleScope.launch {
            try {
                val server = PreferencesHelper(this@PlayerActivity).activeEmbyServer()
                val userId = server?.userId ?: return@launch
                val result = EmbyClient().getItemDetail(baseUrl, apiKey, userId, itemId)
                result.onSuccess { item ->
                    val epLabel = buildString {
                        item.parentIndexNumber?.let { append("S${it.toString().padStart(2, '0')}") }
                        item.indexNumber?.let { append("E${it.toString().padStart(2, '0')}") }
                    }
                    val display = when {
                        item.type == "Episode" && !item.seriesName.isNullOrBlank() ->
                            "${item.seriesName} $epLabel · ${item.name}"
                        else -> item.name
                    }
                    findViewById<TextView>(R.id.other_video_title)?.text = display
                    findViewById<TextView>(R.id.other_video_overview)?.text = item.overview ?: ""
                    val poster = EmbyClient().getImageUrl(baseUrl, itemId, item.primaryImageTag, apiKey, 400, "Primary")
                    if (poster != null) {
                        EmbyImageLoader.load(findViewById(R.id.other_video_poster_img), poster)
                    }
                    findViewById<View>(R.id.video_info_box)?.visibility = View.VISIBLE
                    item.people?.filter { it.type == "Actor" }?.take(8)?.let { actors ->
                        setupActorList(findViewById(R.id.other_actor), actors)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PlayerActivity", "loadVideoInfo failed", e)
            }
        }
    }

    // ==================== 手势 ====================

    private fun setupGestures(gestureTextView: TextView) {
        val renderSurface = findViewById<RenderSurfaceView>(R.id.renderSurface) ?: return
        renderSurface.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x; gestureStartY = event.y
                    gestureSeekAccum = 0f
                    dragPreviewPosMs = null
                    isHorizGesture = false; isVolGesture = false; isBrightnessGesture = false
                    isLongPressing = false
                    touchDownTime = System.currentTimeMillis()
                    originalSpeed = viewModel.state.value.currentSpeed
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, 400)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY
                    val absDx = kotlin.math.abs(dx); val absDy = kotlin.math.abs(dy)

                    if (!isHorizGesture && !isVolGesture && !isBrightnessGesture) {
                        if (absDx > 60 && absDx > absDy) {
                            isHorizGesture = true
                            gestureTextView.visibility = View.VISIBLE
                            mainHandler.removeCallbacks(longPressRunnable)
                            if (isLongPressing) { isLongPressing = false; viewModel.setSpeed(1.0f) }
                        } else if (absDy > 60 && absDy > absDx) {
                            if (event.x < v.width / 2f) isBrightnessGesture = true else isVolGesture = true
                            gestureTextView.visibility = View.VISIBLE
                            mainHandler.removeCallbacks(longPressRunnable)
                            if (isLongPressing) { isLongPressing = false; viewModel.setSpeed(1.0f) }
                        }
                    }

                    if (isHorizGesture) {
                        gestureSeekAccum += dx
                        val ratio = (gestureSeekAccum / v.width.toFloat()).coerceIn(-1f, 1f)
                        val duration = viewModel.state.value.durationMs
                        val base = viewModel.currentPositionMs
                        val target = (base + (ratio * duration * 0.2f).toLong()).coerceIn(0, duration)
                        dragPreviewPosMs = target
                        val deltaSec = ((target - base) / 1000)
                        gestureTextView.text = "${if (deltaSec >= 0) "+" else ""}${deltaSec}s  ${formatTime(target)} / ${formatTime(duration)}"
                        gestureStartX = event.x
                    } else if (isBrightnessGesture) {
                        val delta = -dy / v.height.toFloat()
                        val lp = window.attributes
                        lp.screenBrightness = (lp.screenBrightness + delta).coerceIn(0.01f, 1f)
                        window.attributes = lp
                        gestureTextView.text = "亮度 ${(lp.screenBrightness * 100).toInt()}%"
                        gestureStartY = event.y
                    } else if (isVolGesture) {
                        val delta = -dy / v.height.toFloat() * 100f
                        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (cur + delta / 100f * max).toInt().coerceIn(0, max), 0)
                        gestureTextView.text = "音量 ${(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max)}%"
                        gestureStartY = event.y
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragPreviewPosMs != null && isHorizGesture) {
                        viewModel.seekTo(dragPreviewPosMs!!)
                        if (::danmakuManager.isInitialized) danmakuManager.alignTo(dragPreviewPosMs!!)
                        dragPreviewPosMs = null
                    }
                    gestureTextView.visibility = View.GONE
                    isHorizGesture = false; isVolGesture = false; isBrightnessGesture = false
                    mainHandler.removeCallbacks(longPressRunnable)

                    if (isLongPressing) {
                        isLongPressing = false
                        viewModel.setSpeed(originalSpeed)
                    } else {
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastTapTime
                        val sameArea = kotlin.math.abs(event.x - lastTapX) < v.width / 3f &&
                                kotlin.math.abs(event.y - lastTapY) < v.height / 3f
                        if (elapsed < 350 && sameArea) {
                            if (event.x < v.width / 3f) {
                                val target = (viewModel.currentPositionMs - 10000).coerceAtLeast(0)
                                viewModel.seekTo(target)
                                if (::danmakuManager.isInitialized) danmakuManager.alignTo(target)
                                showGesture("-10s")
                            } else if (event.x > v.width * 2 / 3f) {
                                val target = (viewModel.currentPositionMs + 10000).coerceAtMost(viewModel.state.value.durationMs)
                                viewModel.seekTo(target)
                                if (::danmakuManager.isInitialized) danmakuManager.alignTo(target)
                                showGesture("+10s")
                            } else {
                                viewModel.togglePlayPause()
                            }
                            lastTapTime = 0
                        } else {
                            lastTapTime = now
                            lastTapX = event.x; lastTapY = event.y
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun showGesture(text: String) {
        val gestureTextView = findViewById<TextView>(R.id.gestureTextView)
        if (gestureTextView != null) {
            gestureTextView.text = text
            gestureTextView.visibility = View.VISIBLE
            mainHandler.removeCallbacks(gestureHideRunnable)
            mainHandler.postDelayed(gestureHideRunnable, 1000)
        }
    }

    private val gestureHideRunnable = Runnable {
        findViewById<TextView>(R.id.gestureTextView)?.visibility = View.GONE
    }

    // ==================== 轨道选择 ====================

    private fun showAudioSelection() {
        val tracks = viewModel.state.value.availableTracks
        val audios = tracks.filter { TrackType.fromMedia3Type(it.trackType) == TrackType.AUDIO }
        if (audios.isEmpty()) { Toast.makeText(this, "暂无音轨", Toast.LENGTH_SHORT).show(); return }
        showTrackPanel(audios, "音轨选择")
    }

    private fun showSubtitleSelection() {
        val tracks = viewModel.state.value.availableTracks
        val subs = tracks.filter { TrackType.fromMedia3Type(it.trackType) == TrackType.SUBTITLE }
        if (subs.isEmpty()) { Toast.makeText(this, "暂无字幕轨", Toast.LENGTH_SHORT).show(); return }
        showTrackPanel(subs, "字幕选择")
    }

    private fun showTrackPanel(tracks: List<KernelTrackInfo>, title: String) {
        val panel = findViewById<View>(R.id.track_selection_view) ?: return
        findViewById<View>(R.id.play_view_for_set)?.visibility = View.GONE
        findViewById<TextView>(R.id.track_selection_title)?.text = title
        val list = findViewById<RecyclerView>(R.id.track_selection_list)
        list?.layoutManager = LinearLayoutManager(this)
        list?.adapter = TrackSelectionAdapter(tracks) { track ->
            viewModel.selectTrack(track)
            // 选择后关闭轨道面板，根据上下文回到上滑面板或控制栏
            mainHandler.postDelayed({
                panel.visibility = View.GONE
                val swipePanel = findViewById<View>(R.id.panel_overlay)
                if (swipePanel?.visibility == View.VISIBLE) {
                    // 上滑面板开着 → 不调用 showControls（避免底部栏在上滑面板背后显示），
                    // 把焦点还给上滑面板的按钮
                    findViewById<View>(R.id.other_subtitle_btn)?.post {
                        findViewById<View>(R.id.other_subtitle_btn)?.requestFocus()
                    }
                } else {
                    showControls()
                }
            }, 200)
        }
        panel.visibility = View.VISIBLE
        // 先聚焦 RecyclerView 本身，下一帧再尝试聚焦第一个 item（确保布局完成后精确聚焦）
        list?.post {
            list?.requestFocus()
            list?.post {
                list?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    // ==================== 选集（旧 AlertDialog 保留） ====================

    private fun showEpisodeSelection() {
        val queue = viewModel.playQueue
        if (queue.size <= 1) { Toast.makeText(this, "仅单集，无选集", Toast.LENGTH_SHORT).show(); return }
        val titles = queue.mapIndexed { idx, (_, t) ->
            "${if (idx == viewModel.playQueueIndex) "▶" else " "} 第${idx + 1}集  $t"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("选集")
            .setItems(titles) { _, which -> viewModel.playAtQueueIndex(which); refreshEpisodeHighlight(); showControls() }
            .setOnDismissListener { showControls() }.show()
    }

    // ==================== 多版本切换 ====================

    private fun showVersionSelection() {
        if (mediaSources.size <= 1) { Toast.makeText(this, "仅一个版本", Toast.LENGTH_SHORT).show(); return }
        val items = mediaSources.map { ms ->
            val name = ms.name ?: ms.id
            val res = ms.let { "${it.width ?: "?"}×${it.height ?: "?"}" }
            val bit = ms.bitRate?.let { "${it / 1_000_000}Mbps" } ?: ""
            val size = ms.size?.let { "${it / 1_000_000}MB" } ?: ""
            val codec = ms.videoCodec ?: ""
            "$name  $res  ${listOfNotNull(codec, bit, size).joinToString(" ")}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("选择版本")
            .setItems(items) { _, which ->
                val target = mediaSources[which]
                if (target.id == mediaSources.firstOrNull()?.id) return@setItems
                switchMediaSource(target)
            }
            .setOnDismissListener { showControls() }.show()
    }

    private fun switchMediaSource(ms: EmbyMediaSource) {
        val itemId = intent.getStringExtra("itemId") ?: return
        val embyBaseUrl = intent.getStringExtra("embyBaseUrl") ?: return
        val embyApiKey = intent.getStringExtra("embyApiKey") ?: return
        val deviceId = "embytv_${android.os.Build.MODEL.replace(" ", "_")}"
        Toast.makeText(this, "切换版本中…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val userId = PreferencesHelper(this@PlayerActivity).activeEmbyServer()?.userId ?: ""
            val result = EmbyClient().getPlaybackInfo(embyBaseUrl, embyApiKey, userId, itemId)
            val newSessionId = result.getOrNull()?.playSessionId ?: playSessionId
            val newUrl = "$embyBaseUrl/emby/Videos/$itemId/stream.${ms.container.lowercase()}?DeviceId=$deviceId&MediaSourceId=${ms.id}&PlaySessionId=$newSessionId&Static=true&api_key=$embyApiKey"
            val pos = viewModel.currentPositionMs
            val title = intent.getStringExtra("title") ?: ""
            viewModel.setMedia(newUrl, title, mapOf("X-Emby-Token" to embyApiKey))
            val currentType = viewModel.state.value.currentPlayerType
            viewModel.switchPlayer(this@PlayerActivity, currentType)
            if (pos > 0) {
                delay(800)
                viewModel.seekTo(pos)
            }
        }
    }

    // ==================== 弹幕 ====================

    private fun loadDanmuForCurrentItem() {
        if (danmuLoaded) return
        val itemId = intent.getStringExtra("itemId") ?: ""
        val embyBaseUrl = intent.getStringExtra("embyBaseUrl") ?: ""
        val embyApiKey = intent.getStringExtra("embyApiKey") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        if (title.isBlank()) return

        Toast.makeText(this, "匹配弹幕中…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            // ① 优先：Emby 服务器 Danmu 插件（同 RodelQt 已验证：/api/danmu/{itemId} 返回 6707 条真实弹幕；
            //    直连内网服务器，无外网依赖，dandanplay 网络不稳/匹配规则问题的正解）
            if (itemId.isNotEmpty() && embyBaseUrl.isNotEmpty() && embyApiKey.isNotEmpty()) {
                val serverItems = danmuApi.fetchServerDanmu(embyBaseUrl, embyApiKey, itemId).getOrNull()
                if (!serverItems.isNullOrEmpty()) {
                    danmuLoaded = true
                    danmakuManager.loadDanmu(serverItems, viewModel.currentPositionMs)
                    findViewById<TextView>(R.id.other_config_tips)?.text = "弹幕已加载 (服务器)"
                    showGesture("弹幕已加载")
                    return@launch
                }
            }

            // ② fallback：dandanplay（服务器插件无数据时才走外部 API）
            val cleanTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "")
            val fileName = "$cleanTitle.mp4"
            val seasonEp = Regex("""[Ss](\d{1,2})[Ee](\d{1,2})""").find(title)
            val seasonNo = seasonEp?.groupValues?.get(1)
            val episodeNo = seasonEp?.groupValues?.get(2)
            val fileName2 = if (seasonEp != null) {
                val (s, e) = seasonEp.destructured
                "$cleanTitle.S${s.padStart(2, '0')}E${e.padStart(2, '0')}.mp4"
            } else null
            val remembered = PreferencesHelper(this@PlayerActivity).prefsString("danmu_ep_$cleanTitle", "")
            if (remembered.isNotBlank()) {
                loadDanmuByEpisodeId(remembered, "记忆")
                return@launch
            }
            var episodeId: String? = null
            var animeTitle = ""
            val names = listOfNotNull(fileName, fileName2)
            for (name in names) {
                val match = danmuApi.match(name).getOrNull()
                if (!match.isNullOrEmpty()) {
                    episodeId = match.first().episodeId
                    animeTitle = match.first().animeTitle
                    break
                }
            }
            if (episodeId == null) {
                val animeName = cleanTitle
                    .replace(Regex("""[Ss]\d{1,2}[Ee]\d{1,2}"""), "")
                    .replace(Regex("""第\s*\d{1,3}\s*[话集]"""), "")
                    .replace(Regex("""\s*[-_]\s*$"""), "")
                    .trim()
                if (animeName.isNotBlank()) {
                    val found = danmuApi.searchAnime(animeName).getOrNull().orEmpty()
                        .firstOrNull()
                    if (found != null) {
                        val eps = danmuApi.getEpisodes(found.animeId).getOrNull().orEmpty()
                        val target = if (seasonNo != null && episodeNo != null) {
                            eps.firstOrNull {
                                val epMatch = Regex("""第\s*(\d{1,2})\s*[话集]""").find(it.title)
                                val no = epMatch?.groupValues?.get(1)?.toIntOrNull()
                                no == episodeNo.toIntOrNull()
                            } ?: eps.firstOrNull { it.title.contains("$episodeNo") }
                        } else {
                            val cnNo = Regex("""第\s*(\d{1,2})\s*[话集]""").find(cleanTitle)
                                ?.groupValues?.get(1)?.toIntOrNull()
                            if (cnNo != null) {
                                eps.firstOrNull {
                                    val m = Regex("""第\s*(\d{1,2})\s*[话集]""").find(it.title)
                                    m?.groupValues?.get(1)?.toIntOrNull() == cnNo
                                } ?: eps.firstOrNull()
                            } else eps.firstOrNull()
                        }
                        if (target != null) {
                            episodeId = target.episodeId
                            animeTitle = found.title
                        }
                    }
                }
            }
            if (episodeId != null) {
                PreferencesHelper(this@PlayerActivity).setPrefsString("danmu_ep_$cleanTitle", episodeId!!)
                loadDanmuByEpisodeId(episodeId!!, animeTitle)
            } else {
                Toast.makeText(this@PlayerActivity, "未匹配到弹幕，长按弹幕按钮可手动搜索", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadDanmuByEpisodeId(episodeId: String, animeTitle: String) {
        lifecycleScope.launch {
            val result = danmuApi.downloadComments(episodeId)
            val items = result.getOrNull()
            if (items.isNullOrEmpty()) {
                Toast.makeText(this@PlayerActivity, "无弹幕数据", Toast.LENGTH_SHORT).show()
                return@launch
            }
            danmuLoaded = true
            // 🔴 必须先启用弹幕视图（否则 DanmakuView 保持 GONE + 未 start → "已加载"但看不见）
            if (!danmakuManager.isEnabled()) {
                danmakuManager.setEnabled(true)
                findViewById<ImageView>(R.id.other_danmu_status)?.setImageResource(R.drawable.dan_open)
                PreferencesHelper(this@PlayerActivity).setPrefsBoolean("danmu_enabled", true)
            }
            danmakuManager.loadDanmu(items, viewModel.currentPositionMs)
            findViewById<TextView>(R.id.other_config_tips)?.text = "弹幕已加载 $animeTitle"
            showGesture("弹幕已加载")
        }
    }

    /** 手动搜索弹幕：右侧抽屉面板（AfuseKtV panel_danmu_search 同款），长按弹幕按钮触发 */
    private fun showDanmuSearchDialog() {
        if (danmuSearchPanel?.isVisible == true) return
        val title = intent.getStringExtra("title") ?: return
        if (title.isBlank()) return
        val cleanTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "")
        // 预填：优先用剧集名(seriesName)——单集名(如"大结局上：时空逃脱篇1")搜不到番剧
        val seriesName = intent.getStringExtra("seriesName") ?: ""
        val prefillRaw = if (seriesName.isNotBlank()) seriesName else cleanTitle
        val prefill = prefillRaw
            .replace(Regex("""[Ss]\d{1,2}[Ee]\d{1,2}"""), "")
            .replace(Regex("""第\s*\d{1,3}\s*[话集]"""), "")
            .replace(Regex("""\s*[-_]\s*$"""), "")
            .trim()

        val panel = DanmuSearchPanel(
            activity = this,
            danmuApi = danmuApi,
            onEpisodePicked = { episodeId, epTitle ->
                // 记住映射，下次自动加载
                PreferencesHelper(this).setPrefsString("danmu_ep_$cleanTitle", episodeId)
                loadDanmuByEpisodeId(episodeId, epTitle)
            },
            onClosed = { danmuSearchPanel = null }
        )
        danmuSearchPanel = panel
        panel.show(prefill)
    }

    // ==================== 解码信息弹窗 ====================

    private fun showDecodeInfo() {
        val s = viewModel.state.value
                val info = s.kernelInfo
                val lines = buildString {
                    appendLine("内核: ${s.currentPlayerType.displayName}")
                    appendLine("解码: ${info.decodeMode}")
                    appendLine("格式: ${info.videoFormat}")
                    appendLine("编码: ${info.codec}")
                    appendLine("倍速: ${s.currentSpeed}X")
                    if (s.audioPassthrough) appendLine("音频直通: 开")
                    if (s.frameRateMatch) appendLine("帧率匹配: 开")
                }
        android.app.AlertDialog.Builder(this).setTitle("解码信息").setMessage(lines).setPositiveButton("关闭", null).show()
    }

    // ==================== 生命周期 ====================

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (viewModel.state.value.isPrepared) viewModel.resumeAfterBackground()
        if (::danmakuManager.isInitialized) danmakuManager.resume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.pause()
        if (::danmakuManager.isInitialized) danmakuManager.pause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.removeCallbacks(speedUpRunnable)
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(gestureHideRunnable)
        sleepTimerRunnable?.let { mainHandler.removeCallbacks(it) }
        if (::danmakuManager.isInitialized) danmakuManager.release()
        viewModel.release()
        super.onDestroy()
    }

    // ==================== 工具方法 ====================

    /** PGS 位图字幕（无字号概念）用 View 缩放调整大小：30px->0.6x, 120px->1.4x */
    private fun applyPgsSubtitleScale(size: Int) {
        val v = findViewById<android.widget.ImageView>(R.id.bitmapSubtitle) ?: return
        val scale = (size / 75f).coerceIn(0.6f, 1.4f) // 30->0.4? 取 30/75=0.4, 120/75=1.6 → 截断 0.6-1.4
        v.scaleX = scale
        v.scaleY = scale
        v.pivotX = v.width / 2f
        v.pivotY = v.height.toFloat()
    }

    /** 字幕字号 30-120px → 显示为百分比 50%-200%（100%=默认） */
    private fun pctText(size: Int): String = "${(size * 100 / 60)}%"

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun formatTime(ms: Long): String {
        if (ms < 0) return "00:00"
        val total = ms / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}