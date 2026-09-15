package com.starcinema.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.starcinema.api.EmbyClient
import com.starcinema.player.kernel.PlayerFactory
import com.starcinema.player.kernel.VideoLog
import com.starcinema.player.kernel.VideoPlayerEventListener
import com.starcinema.player.kernel.AbstractVideoPlayer
import com.starcinema.player.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerState(
    val isPlaying: Boolean = false,
    val isPrepared: Boolean = false,
    val isLoading: Boolean = true,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPercent: Int = 0,
    /** 当前播放标题（切集后更新，UI 进度条上方单集标题跟随） */
    val mediaTitle: String = "",
    val currentSpeed: Float = 1.0f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val error: String? = null,
    val subtitleText: String? = null,
    val subtitleBitmap: android.graphics.Bitmap? = null,
    val isBuffering: Boolean = false,
    val currentPlayerType: PlayerType = PlayerType.TYPE_EXO_PLAYER,
    val availableTracks: List<KernelTrackInfo> = emptyList(),
    val kernelInfo: com.starcinema.player.model.VideoKernelInfo = com.starcinema.player.model.VideoKernelInfo(),
    val zoomScale: Float = 1.0f,
    val audioPassthrough: Boolean = false,
    val frameRateMatch: Boolean = false,
    val nextEpisodeTitle: String? = null,
    val nextEpisodeUrl: String? = null,
    val isLooping: Boolean = false,
    /** 解码模式索引：0=HW 1=HW+ 2=SW（MPV 有效） */
    val decodeModeIndex: Int = 0
)

class PlayerViewModel : ViewModel(), VideoPlayerEventListener {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** 高频播放位置（ms），独立于低频 state，仅影响进度条 UI */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** 当前播放位置（命令式读取：手势/快捷键用，不触发重组） */
    val currentPositionMs: Long
        get() = _positionMs.value

    private var player: AbstractVideoPlayer? = null
    private var currentPlayerType = PlayerType.TYPE_EXO_PLAYER
    private var positionUpdateJob: kotlinx.coroutines.Job? = null
    private var lastKernelInfoUpdate = 0L
    private var pendingSurface: android.view.Surface? = null
    private var appContext: android.content.Context? = null
    private var pendingSeekMs: Long = 0L
    /** 手动切集标志：切集时旧文件 END_FILE/STOP 事件不触发自动播下一集 */
    private var suppressAutoNext = false
    /** 🔴 切集代数：每次切集递增；onPrepared 记录代数。onCompletion 时若代数不符
     *    （旧文件 END_FILE 迟到于新集 prepared 之后）→ 忽略，不误报 Emby/不乱切集 */
    private var switchGeneration = 0
    private var preparedGeneration = -1
    var skipIntroSeconds: Int = 0
    var shaderProfile: Int = 0

    var mediaSource: String = ""
        private set
    var mediaHeaders: Map<String, String> = emptyMap()
        private set
    var mediaTitle: String = ""
        private set
    var mediaSubtitleUrls: List<Pair<String, String>> = emptyList()
        private set
    var playQueue: List<Pair<String, String>> = emptyList()
        private set
    var playQueueIndex: Int = 0
        private set

    // Emby 播放进度回写
    private var embyClient: EmbyClient? = null
    private var embyBaseUrl: String = ""
    private var embyApiKey: String = ""
    private var embyItemId: String = ""
    private var playSessionId: String = ""

    // 后台自动暂停标记（按 Home 时暂停，回前台时恢复）
    var automaticallyPausedOnBackground: Boolean = false

    fun setEmbyContext(baseUrl: String, apiKey: String, itemId: String) {
        embyClient = EmbyClient()
        embyBaseUrl = baseUrl
        embyApiKey = apiKey
        embyItemId = itemId
    }

    fun setMedia(source: String, title: String, headers: Map<String, String>? = null, subtitleUrls: List<Pair<String, String>>? = null) {
        mediaSource = source
        mediaTitle = title
        mediaHeaders = headers ?: emptyMap()
        mediaSubtitleUrls = subtitleUrls ?: emptyList()
    }

    fun setPlayQueue(queue: List<Pair<String, String>>, startIndex: Int = 0) {
        playQueue = queue
        playQueueIndex = startIndex
        if (queue.size > 1) {
            _state.update { it.copy(nextEpisodeTitle = queue.getOrNull(startIndex + 1)?.second) }
        }
    }

    /** 播放下一个（队列切换，保留 surface 不重建播放器） */
    fun playNext() {
        if (playQueueIndex + 1 >= playQueue.size) { Toast("已经是最后一集"); return }
        playQueueIndex++
        suppressAutoNext = true
        switchInQueue()
    }

    /** 播放上一个 */
    fun playPrevious() {
        if (playQueueIndex <= 0) { Toast("已经是第一集"); return }
        playQueueIndex--
        suppressAutoNext = true
        switchInQueue()
    }

    /** 跳转到队列中指定索引 */
    fun playAtQueueIndex(index: Int) {
        if (index < 0 || index >= playQueue.size) return
        if (index == playQueueIndex) return
        playQueueIndex = index
        suppressAutoNext = true
        switchInQueue()
    }

    private fun switchInQueue() {
        val (url, nextTitle) = playQueue.getOrNull(playQueueIndex) ?: return
        switchGeneration++   // 🔴 切集代数递增：迟到的旧文件 END_FILE 会被 onCompletion 忽略
        mediaSource = url
        mediaTitle = nextTitle
        VideoLog.i("VM switchInQueue index=$playQueueIndex title=$nextTitle url=$url")
        _state.update { it.copy(
            mediaTitle = nextTitle,
            nextEpisodeTitle = playQueue.getOrNull(playQueueIndex + 1)?.second,
            nextEpisodeUrl = url, isLoading = true, error = null
        ) }
        // 切集 = 换 itemId：旧集收尾上报 stopped，新集清空 session 让 play() 上报新 start
        // （否则新集从不 reportPlaybackStart，进度上报记到旧集 → 播放记录错乱/丢失）
        rotatePlaybackSession(url)
        try {
            player?.stop()
            player?.reset() // 会清掉视频 Surface
            player?.setDataSource(mediaSource, mediaHeaders)
            player?.prepareAsync()
            // reset() 清了 Surface，prepareAsync 后重新挂上，否则画面卡住
            pendingSurface?.let { player?.setSurface(it) }
        } catch (_: Exception) {
            appContext?.let { initPlayer(it, currentPlayerType) }
        }
    }

    /** 从播放 URL 提取 itemId（Emby queue URL: .../emby/Videos/{itemId}/stream?...） */
    private fun extractItemIdFromUrl(url: String): String {
        return Regex("""Videos/([^/?]+)/stream""").find(url)?.groupValues?.get(1) ?: ""
    }

    /**
     * 切集/自动下一集时轮换 Emby 播放会话：
     * 1. 旧集用当前进度上报 Stopped（正确收尾，旧集进度保留）
     * 2. 更新 embyItemId 到新集
     * 3. 清空 playSessionId → 新集首次 play() 会 reportPlaybackStart
     */
    private fun rotatePlaybackSession(newUrl: String) {
        val newItemId = extractItemIdFromUrl(newUrl)
        if (newItemId.isEmpty() || newItemId == embyItemId) return
        // 旧集收尾
        val oldPos = player?.getCurrentPosition() ?: 0L
        val oldItemId = embyItemId
        val oldSession = playSessionId
        if (embyClient != null && oldItemId.isNotEmpty() && oldSession.isNotEmpty()) {
            val apiKey = embyApiKey
            val baseUrl = embyBaseUrl
            val ticks = oldPos * 10_000
            viewModelScope.launch(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                embyClient?.reportPlaybackStopped(baseUrl, apiKey, oldItemId, oldSession, ticks)
            }
        }
        // 切到新集：只更新 itemId 并清空 session，start 上报交给 onPrepared（新集真正加载完成时，
        // 避免切集瞬间上报 start 但新集实际加载失败 → Emby 端出现"幽灵播放记录"）
        embyItemId = newItemId
        playSessionId = ""
        VideoLog.i("VM rotatePlaybackSession -> $newItemId (old stopped, session cleared)")
    }

    private fun Toast(msg: String) {
        android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun initPlayer(androidContext: android.content.Context, playerType: PlayerType = PlayerType.TYPE_EXO_PLAYER) {
        VideoLog.isPrintLog = true
        currentPlayerType = playerType
        appContext = androidContext.applicationContext
        player?.release()
        player = null

        val factory = PlayerFactory.getFactory(playerType)
        val newPlayer = factory.createPlayer(androidContext)
        newPlayer.setPlayerEventListener(this)
        newPlayer.initPlayer()
        newPlayer.setShaderConfig(shaderProfile)
        // 设置外挂字幕（EXO 用它构建 MediaItem；MPV 用 sub-add 命令，在 FILE_LOADED 后追加）
        if (mediaSubtitleUrls.isNotEmpty()) {
            newPlayer.setSubtitleUrls(mediaSubtitleUrls)
        }
        newPlayer.setDataSource(mediaSource, mediaHeaders)
        // 应用在播放器创建前就已就绪的 surface（修复黑屏只有声音问题）
        pendingSurface?.let { surface ->
            if (surface.isValid) {
                newPlayer.setSurface(surface)
            }
        }
        newPlayer.prepareAsync()
        player = newPlayer

        // 无条件开始播放：surfaceCreated 可能早于 initPlayer 触发（play() 空转），也可能晚于 initPlayer
        // 调用 play() 确保 playWhenReady=true，ExoPlayer 会在 surface 就绪后自动渲染
        play()

        _state.value = _state.value.copy(
            currentPlayerType = playerType,
            isLoading = true,
            isPrepared = false,  // 重置就绪标志：新内核未加载完成，seek 会进 pendingSeekMs 等 onPrepared
            error = null
        )
    }

    fun setSurface(surface: android.view.Surface) {
        VideoLog.i("VM setSurface valid=${surface.isValid} player=${player != null}")
        pendingSurface = surface
        player?.setSurface(surface)
    }

    fun clearSurface() {
        VideoLog.i("VM clearSurface")
        pendingSurface = null
        player?.clearSurface()
    }

    fun setSurfaceSize(width: Int, height: Int) {
        player?.setSurfaceSize(width, height)
    }

    fun setOsdSurface(surface: android.view.Surface?) {
        player?.setOsdSurface(surface)
    }

    /** ASS 字幕拓展：把 AssSubtitleView 挂到指定容器（EXO 内核用 libass 渲染 ASS/SSA） */
    fun attachAssSubtitleView(container: android.view.ViewGroup?) {
        player?.attachAssSubtitleView(container)
    }

    fun play() {
        // pendingSurface 仅用于初始化暂存，播放时不需要检查
        if (player != null) {
            VideoLog.i("VM play() hasSurface=${hasSurface()}")
            player?.start()
            _state.value = _state.value.copy(isPlaying = true)
            startPositionUpdates()
            // Emby 播放开始通知：仅首次建立会话时上报一次。
            // 暂停→续播、切换内核（switchPlayer）复用同一 playSessionId，不再重复上报 start
            if (embyClient != null && embyItemId.isNotEmpty() && playSessionId.isEmpty()) {
                val apiKey = embyApiKey
                val baseUrl = embyBaseUrl
                val itemId = embyItemId
                val sessionId = java.util.UUID.randomUUID().toString()
                playSessionId = sessionId
                // 续播时从当前进度开始会话，报告更准确
                val startPosTicks = (player?.getCurrentPosition() ?: 0L) * 10_000
                viewModelScope.launch {
                    embyClient?.reportPlaybackStart(baseUrl, apiKey, itemId, sessionId, positionTicks = startPosTicks)
                }
            }
        }
    }

    fun pause() {
        VideoLog.i("VM pause()")
        player?.pause()
        _state.value = _state.value.copy(isPlaying = false)
        stopPositionUpdates()
    }

    fun togglePlayPause() {
        // 用播放器真实状态判断，不用 state（state.isPlaying 可能因 mpv END_FILE 粘死为 false）
        val realPlaying = player?.isPlaying() ?: false
        VideoLog.i("VM togglePlayPause realPlaying=$realPlaying state=${_state.value.isPlaying}")
        if (realPlaying) pause() else play()
    }

    /** 当前是否已绑定有效的渲染 surface（用于判断后台恢复时能否立即播） */
    fun hasSurface(): Boolean {
        return player?.let { it.hasValidSurface() } == true
    }

    /**
     * 从后台恢复播放：
     * - 如果 surface 仍在（app 短暂切走未销毁 surface）→ 直接恢复播放
     * - 如果 surface 已销毁（SurfaceView 走了 surfaceDestroyed）→ 不在此 play，
     *   等 SurfaceHolder.Callback.surfaceCreated 回调中的 play() 恢复，否则黑屏
     */
    fun resumeAfterBackground() {
        if (hasSurface()) {
            play()
        } else {
            VideoLog.i("surface not ready yet, wait for surfaceCreated")
        }
    }

    fun seekTo(positionMs: Long) {
        // 播放器未就绪时先存起来，onPrepared 后再执行
        if (player == null || !_state.value.isPrepared) {
            pendingSeekMs = positionMs
            return
        }
        player?.seekTo(positionMs)
    }

    /** 自动跳片头：从配置的秒数位置开始播放 */
    fun autoSkipIntro() {
        val sec = skipIntroSeconds
        if (sec > 0) {
            seekTo(sec * 1000L)
        }
    }

    fun setSpeed(speed: Float) {
        player?.setSpeed(speed)
        _state.value = _state.value.copy(currentSpeed = speed)
    }

    fun setAspectRatio(ratio: String) {
        player?.setAspectRatio(ratio)
    }

    fun setZoom(scale: Float) {
        player?.setZoom(scale)
        _state.value = _state.value.copy(zoomScale = scale)
    }

    fun setSubtitleOffset(offsetMs: Long) {
        player?.setSubtitleOffset(offsetMs)
    }

    /** 运行时字幕大小（MPV 实时生效，EXO 忽略） */
    fun setSubtitleSize(size: Int) {
        player?.setSubtitleSize(size)
    }

    /** 运行时字幕位置（MPV 实时生效，EXO 忽略） */
    fun setSubtitlePosition(pos: Int) {
        player?.setSubtitlePosition(pos)
    }

    fun setAudioPassthrough(enabled: Boolean) {
        player?.setAudioPassthrough(enabled)
        _state.value = _state.value.copy(audioPassthrough = enabled)
    }

    fun toggleLoop() {
        val newVal = !_state.value.isLooping
        _state.update { it.copy(isLooping = newVal) }
        player?.setLooping(newVal)
    }

    /** 切换解码模式：0=HW 1=HW+ 2=SW（MPV 运行时设 hwdec property，EXO 重建播放器） */
    fun setHwdecMode(index: Int) {
        _state.update { it.copy(decodeModeIndex = index) }
        player?.setHwdecMode(index)
        // 立即刷新内核信息（解码切换后不用等 2 秒周期刷新）
        refreshKernelInfoNow()
    }

    /** 立即从播放器读取一次内核信息并刷新 state（解码/内核切换后调用） */
    private fun refreshKernelInfoNow() {
        val ki = player?.getKernelInfo() ?: return
        val cur = _state.value
        _state.value = cur.copy(
            kernelInfo = ki.copy(bufferSpeedKBps = cur.kernelInfo.bufferSpeedKBps)
        )
    }

    fun setFrameRateMatch(enabled: Boolean) {
        player?.setFrameRateMatch(enabled)
        _state.value = _state.value.copy(frameRateMatch = enabled)
    }

    fun switchPlayer(androidContext: android.content.Context, newType: PlayerType) {
        val currentPos = player?.getCurrentPosition() ?: 0L
        android.util.Log.i("SynoPlayer", "switchPlayer: ${currentPlayerType}->$newType currentPos=$currentPos")
        player?.release()
        player = null
        stopPositionUpdates()

        currentPlayerType = newType
        initPlayer(androidContext, newType)
        // 切播放器后尝试恢复进度
        viewModelScope.launch {
            delay(500)
            if (currentPos > 0) {
                android.util.Log.i("SynoPlayer", "switchPlayer seekTo $currentPos (prepared=${_state.value.isPrepared})")
                seekTo(currentPos)
            }
        }
    }

    fun release() {
        stopPositionUpdates()
        // 上报播放停止（含位置，让 Emby 记录进度）
        val pos = player?.getCurrentPosition() ?: 0L
        if (embyClient != null && embyItemId.isNotEmpty() && playSessionId.isNotEmpty()) {
            val apiKey = embyApiKey
            val baseUrl = embyBaseUrl
            val itemId = embyItemId
            val sessionId = playSessionId
            val ticks = pos * 10_000
            viewModelScope.launch(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                embyClient?.reportPlaybackStopped(baseUrl, apiKey, itemId, sessionId, ticks)
            }
        }
        playSessionId = ""
        player?.release()
        player = null
    }

    fun selectTrack(track: KernelTrackInfo) {
        val trackType = TrackType.fromMedia3Type(track.trackType)
        android.util.Log.i("SynoPlayer", "VM selectTrack: trackType=${track.trackType} fromMedia3=${trackType} id=${track.id}")
        if (trackType != null) {
            // 先更新本地选中状态，让 UI 立即响应（不依赖 mpv 的 track-list 事件）
            val updatedTracks = _state.value.availableTracks.map { t ->
                if (t.trackType == track.trackType) t.copy(selected = t.id == track.id) else t
            }
            _state.value = _state.value.copy(availableTracks = updatedTracks)
            val bean = VideoTrackBean(
                id = track.id,
                name = track.label ?: track.id,
                type = trackType,
                language = track.language
            )
            player?.selectTrack(bean)
        }
    }

    // ==================== VideoPlayerEventListener ====================

    override fun onPrepared() {
        // 新源加载完成：清掉切集抑制标志（此后自然播完的 END_FILE 才会触发自动下一集）
        suppressAutoNext = false
        preparedGeneration = switchGeneration   // 🔴 记录当前代数：自然播完的 END_FILE 代数一致
        viewModelScope.launch(Dispatchers.Main) {
        _state.update { it.copy(
            isPrepared = true,
            isLoading = false,
            durationMs = player?.getDuration() ?: 0
        ) }
        // 执行续播 seek
        if (pendingSeekMs > 0) {
            val pos = pendingSeekMs
            pendingSeekMs = 0
            player?.seekTo(pos)
        } else if (skipIntroSeconds > 0) {
            // 自动跳片头：无续播位置时跳
            player?.seekTo(skipIntroSeconds * 1000L)
        } else {
            // 智能跳片头：Emby intro marker（无续播位置且配置开启时）
            maybeSkipIntro()
        }
        // 不直接 start，等 surface 就绪后由 play() 控制
        play()
        } }

    /** 自动跳过片头：优先用 Emby 服务的 intro marker，失败则退回配置秒数 */
    private fun maybeSkipIntro() {
        val client = embyClient ?: return
        val baseUrl = embyBaseUrl
        val apiKey = embyApiKey
        val itemId = embyItemId
        if (baseUrl.isBlank() || itemId.isBlank()) return
        val userId = com.starcinema.app.PreferencesHelper(appContext ?: return).activeEmbyServer()?.userId ?: return
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val markers = client.getIntroMarkers(baseUrl, apiKey, userId, itemId).getOrNull().orEmpty()
                if (markers.isNotEmpty()) {
                    val (startMs, endMs) = markers.first()
                    val currentPos = player?.getCurrentPosition() ?: 0L
                    // 仅在片头区间内（或刚开播）跳转，避免误跳已看过的位置
                    if (currentPos < endMs && currentPos < 5_000) {
                        VideoLog.i("MPV/EXO auto-skip intro: $startMs→$endMs")
                        player?.seekTo(endMs)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onError(failure: PlaybackFailure) {
        viewModelScope.launch(Dispatchers.Main) {
        _state.update { it.copy(
            isLoading = false,
            error = failure.message
        ) }
        VideoLog.e("Player error: ${failure.category} - ${failure.message}")

        // EXO 播放失败 → 自动降级到 MPV（保存当前进度）
        if (currentPlayerType == PlayerType.TYPE_EXO_PLAYER && failure.allowFallback()) {
            val savedPos = player?.getCurrentPosition() ?: 0L
            VideoLog.i("EXO failed, auto-fallback to MPV, savedPos=$savedPos")
            val ctx = appContext
            if (ctx != null) {
                switchPlayer(ctx, PlayerType.TYPE_MPV_PLAYER)
            }
            if (savedPos > 0) {
                delay(1000)
                player?.seekTo(savedPos)
            }
        }
        } }

    override fun onCompletion() {
        // 🔴 代数不符 = 旧文件迟到的 END_FILE（切集后新集已 prepared，旧 END_FILE 才到）：
        //    忽略，不上报 Emby、不自动切集（否则"时灵时不灵"的错报/乱切）
        if (preparedGeneration != switchGeneration) {
            VideoLog.i("VM onCompletion stale (gen ${preparedGeneration} != ${switchGeneration}) ignored")
            return
        }
        // 手动切集/停止时的 END_FILE 不是自然播完：不触发自动播下一集
        if (suppressAutoNext) {
            suppressAutoNext = false
            VideoLog.i("VM onCompletion suppressed (manual switch)")
            viewModelScope.launch(Dispatchers.Main) {
                _state.update { it.copy(isPlaying = false) }
            }
            return
        }
        viewModelScope.launch(Dispatchers.Main) {
        _state.update { it.copy(isPlaying = false) } }
        stopPositionUpdates()
        // 自动播放下一个（如果有队列）：复用播放器实例（保留 surface），直接切流
        if (playQueueIndex + 1 < playQueue.size) {
            playQueueIndex++
            val (nextUrl, nextTitle) = playQueue[playQueueIndex]
            mediaSource = nextUrl
            mediaTitle = nextTitle
            _state.update { it.copy(mediaTitle = nextTitle, nextEpisodeTitle = playQueue.getOrNull(playQueueIndex + 1)?.second, nextEpisodeUrl = nextUrl, isLoading = true, error = null) }
            // 自动下一集同样轮换会话：旧集进度收尾（此时位置=片尾），新集重新上报 start
            rotatePlaybackSession(nextUrl)
            try {
                player?.stop()
                player?.reset()
                player?.setDataSource(mediaSource, mediaHeaders)
                player?.prepareAsync()
            } catch (_: Exception) {
                // stop/reset 失败则暴力重建
                appContext?.let { initPlayer(it, currentPlayerType) }
            }
            return
        }
        // 播放完成：上报停止（标记已看完）
        val pos = player?.getCurrentPosition() ?: 0L
        if (embyClient != null && embyItemId.isNotEmpty() && playSessionId.isNotEmpty()) {
            val apiKey = embyApiKey
            val baseUrl = embyBaseUrl
            val itemId = embyItemId
            val sessionId = playSessionId
            val ticks = pos * 10_000
            viewModelScope.launch(Dispatchers.Main + kotlinx.coroutines.NonCancellable) {
                embyClient?.reportPlaybackStopped(baseUrl, apiKey, itemId, sessionId, ticks)
            }
            playSessionId = ""
        }
    }

    override fun onVideoSizeChange(width: Int, height: Int) {
        viewModelScope.launch(Dispatchers.Main) {
        _state.update { it.copy(videoWidth = width, videoHeight = height) } }
    }

    override fun onInfo(what: Int, extra: Int) {
        viewModelScope.launch(Dispatchers.Main) {
        when (what) {
            PlayerConstant.MEDIA_INFO_BUFFERING_START -> {
                _state.update { it.copy(isBuffering = true) }
            }
            PlayerConstant.MEDIA_INFO_BUFFERING_END -> {
                _state.update { it.copy(isBuffering = false) }
            }
            PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START -> {}
        } }
    }

    override fun onSubtitleText(subtitle: String?) {
        viewModelScope.launch(Dispatchers.Main) {
        _state.update { it.copy(subtitleText = subtitle, subtitleBitmap = null) } }
    }

    override fun onSubtitleBitmap(bitmap: android.graphics.Bitmap?) {
        viewModelScope.launch(Dispatchers.Main) {
        _state.update { it.copy(subtitleBitmap = bitmap, subtitleText = null) } }
    }

    override fun onTracksChanged(report: KernelTrackReport) {
        viewModelScope.launch(Dispatchers.Main) {
        _state.update { it.copy(availableTracks = report.tracks, isLoading = false) } }
    }

    // ==================== 位置更新 ====================

    private fun startPositionUpdates() {
        stopPositionUpdates()
        var lastReportTicks = 0L
        var lastSpeedUpdate = 0L
        positionUpdateJob = viewModelScope.launch {
            while (true) {
                val pos = player?.getCurrentPosition() ?: 0L
                val dur = player?.getDuration() ?: 0L
                val buf = player?.getBufferedPercentage() ?: 0
                val playing = player?.isPlaying() ?: false
                val now = System.currentTimeMillis()

                // 高频位置 → 独立 flow，只触发进度条 UI 重组
                _positionMs.value = pos

                // 低频字段仅在变化时才更新 state（避免整屏重组）
                val s = _state.value
                if (dur != s.durationMs || playing != s.isPlaying || buf != s.bufferedPercent) {
                    _state.value = s.copy(
                        durationMs = dur,
                        bufferedPercent = buf,
                        isPlaying = playing
                    )
                }
                // 网速：每 2 秒刷新一次（避免高频 getTcpSpeed JNI 调用）
                if (now - lastSpeedUpdate > 2000) {
                    lastSpeedUpdate = now
                    val speedKBs = (player?.getTcpSpeed() ?: 0) / 1024
                    val cur = _state.value
                    if (speedKBs.toInt() != cur.kernelInfo.bufferSpeedKBps) {
                        _state.value = cur.copy(
                            kernelInfo = cur.kernelInfo.copy(bufferSpeedKBps = speedKBs.toInt())
                        )
                    }
                }

                // 内核信息（硬解/软解+格式/编码）：每 2 秒从播放器读取刷新，
                // 让切换解码/内核后右上角显示真实状态
                if (now - lastKernelInfoUpdate > 2000) {
                    lastKernelInfoUpdate = now
                    val ki = player?.getKernelInfo()
                    val cur = _state.value
                    if (ki != null && (ki.decodeMode != cur.kernelInfo.decodeMode ||
                            ki.videoFormat != cur.kernelInfo.videoFormat ||
                            ki.codec != cur.kernelInfo.codec)) {
                        _state.value = cur.copy(
                            kernelInfo = ki.copy(bufferSpeedKBps = cur.kernelInfo.bufferSpeedKBps)
                        )
                    }
                }

                // Emby 进度回写：每 5 秒（或位置跳变）上报一次（避免刷爆服务器）
                val ticks = pos * 10_000
                if (embyClient != null && embyItemId.isNotEmpty() && playSessionId.isNotEmpty()) {
                    if (ticks - lastReportTicks > 50_000_000) {
                        lastReportTicks = ticks
                        val apiKey = embyApiKey
                        val baseUrl = embyBaseUrl
                        val itemId = embyItemId
                        val sessionId = playSessionId
                        viewModelScope.launch {
                            embyClient?.reportPlaybackProgress(baseUrl, apiKey, itemId, sessionId, ticks, !playing)
                        }
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }
}