package com.starcinema.view

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem
import com.starcinema.api.EmbyPerson
import com.starcinema.api.TmdbClient
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentDetailBinding
import kotlinx.coroutines.launch

// 本地解析辅助（EmbyClient.kt 的同名函数是 private，跨文件不可见）
private fun toInt(value: Any?): Int? = when (value) {
    is Number -> value.toInt()
    is String -> value.toDoubleOrNull()?.toInt()
    else -> null
}

private fun toLong(value: Any?): Long? = when (value) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}

private fun toFloat(value: Any?): Float? = when (value) {
    is Number -> value.toFloat()
    is String -> value.toFloatOrNull()
    else -> null
}

class DetailFragment : Fragment() {

    companion object {
        /** 收藏状态变化标记：首页返回时据此刷新收藏行 */
        @JvmStatic
        var favoriteChanged = false

        /**
         * 统一打开详情：Episode 类型自动重定向到所属剧集详情（seriesId），
         * 并携带 seasonId/episodeId 用于定位季和集
         */
        @JvmStatic
        fun open(fm: androidx.fragment.app.FragmentManager, item: EmbyItem, backStackName: String = "detail") {
            val bundle = Bundle().apply {
                putString("itemId", item.id)
                if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) {
                    putString("seriesId", item.seriesId)
                    if (!item.seasonId.isNullOrBlank()) putString("seasonId", item.seasonId)
                    putString("episodeId", item.id)
                }
            }
            try {
                fm.beginTransaction()
                    .replace(R.id.nav_host_container, DetailFragment::class.java, bundle)
                    .addToBackStack(backStackName)
                    .commitAllowingStateLoss()
            } catch (_: Exception) {}
        }
    }

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!
    private val client = EmbyClient()
    private var baseUrl = ""
    private var apiKey = ""
    private var userId = ""
    private var itemId = ""
    private var item: EmbyItem? = null
    private var episodeList: List<EmbyItem> = emptyList()
    // 分季信息
    private var seasons: List<EmbyItem> = emptyList()
    private var selectedSeasonId: String? = null
    private var episodeIdHint: String? = null
    private var seriesIdFromArg: String? = null
    private var hasLoaded = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = PreferencesHelper(requireContext())
        val server = prefs.activeEmbyServer() ?: return
        baseUrl = server.baseUrl
        apiKey = server.accessToken
        userId = server.userId
        client.serverType = server.serverType
        itemId = arguments?.getString("itemId") ?: return
        // 如果从 Episode 重定向过来，用 seriesId 代替 itemId 加载剧集详情
        seriesIdFromArg = arguments?.getString("seriesId")
        val seasonIdArg = arguments?.getString("seasonId")
        if (seasonIdArg != null) selectedSeasonId = seasonIdArg
        episodeIdHint = arguments?.getString("episodeId")
        // 单集→剧集重定向
        if (seriesIdFromArg != null && seriesIdFromArg != itemId) {
            itemId = seriesIdFromArg!!
        }

        loadDetail()
    }

    private fun loadDetail() {
            binding.loadingIndicator.visibility = View.VISIBLE
            // TMDB key 不硬编码到代码里，从资源读取（避免反编译泄露）
            val tmdbKey = requireContext().getString(R.string.tmdb_api_key)
            lifecycleScope.launch {
                // ① Emby 基础详情
                client.getItemDetail(baseUrl, apiKey, userId, itemId).onSuccess { detail ->
                    if (!isAdded || _binding == null) return@onSuccess
                    item = detail
                    bindDetail(detail)
                    hasLoaded = true
                }.onFailure {
                    if (!isAdded || _binding == null) return@onFailure
                    binding.loadingIndicator.visibility = View.GONE
                }

                // ② PlaybackInfo 媒体全量（并行，成功则覆盖详情里的媒体源/流信息后重绑）
                client.getPlaybackInfoDetail(baseUrl, apiKey, userId, itemId).onSuccess { pi ->
                    if (!isAdded || _binding == null) return@onSuccess
                    val base = item
                    if (base != null && (!pi.mediaSources.isNullOrEmpty() || pi.videoStreamInfo != null)) {
                        item = base.copy(
                            mediaSources = pi.mediaSources ?: base.mediaSources,
                            videoStreamInfo = pi.videoStreamInfo ?: base.videoStreamInfo
                        )
                        bindDetail(item!!)
                    }
                }

                // ③ TMDB 补全（有 Tmdb providerId 才拉）：演员/分级/发行日期/制片公司
                val base = item
                val tmdbId = if (tmdbKey.isNotBlank() && base != null) base.providerIds?.get("Tmdb") else null
                if (tmdbId != null) {
                    TmdbClient(tmdbKey).getDetail(tmdbId, isMovie = base?.type == "Movie").onSuccess { tmdb ->
                        if (isAdded && item != null && _binding != null) {
                            val cur = item!!
                            // 合并：Emby 缺的分级/演员/发行日期用 TMDB 补
                            val updated = cur.copy(
                                officialRating = cur.officialRating?.takeIf { it.isNotBlank() } ?: tmdb.certification,
                                productionYear = cur.productionYear ?: tmdb.releaseDate?.take(4)?.toIntOrNull(),
                                // TMDB 演员头像（Emby people 没有头像时用）
                                people = cur.people?.takeIf { it.isNotEmpty() } ?: tmdb.cast.map { c ->
                                    EmbyPerson(
                                        id = c.id?.toString(),
                                        name = c.name,
                                        role = c.character,
                                        type = "Actor",
                                        primaryImageTag = null,
                                        imageUrl = TmdbClient.imageUrl(c.profilePath, "w185")
                                    )
                                }
                            )
                            item = updated
                            bindDetail(updated)
                        }
                    }
                }

                // 加载相似推荐
                client.getSimilarItems(baseUrl, apiKey, userId, itemId).onSuccess { sim ->
                    if (!isAdded || _binding == null) return@onSuccess
                    if (sim.isNotEmpty()) {
                        binding.recommendSection.visibility = View.VISIBLE
                        binding.recommendRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                        binding.recommendRecyclerView.adapter = SimplePosterAdapter(
                            sim, baseUrl, apiKey, client,
                            onClick = { item -> openDetail(item.id) },
                            layoutResId = R.layout.item_detail_landscape
                        )
                    }
                }

                // ④ 预告片（Emby 本地 Trailer 类型 item，可直接复用播放器）
                client.getTrailers(baseUrl, apiKey, userId, itemId).onSuccess { trailers ->
                    if (!isAdded || _binding == null) return@onSuccess
                    if (trailers.isNotEmpty()) {
                        binding.trailerSection.visibility = View.VISIBLE
                        binding.trailerRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                        binding.trailerRecyclerView.adapter = SimplePosterAdapter(
                            trailers, baseUrl, apiKey, client,
                            onClick = { tr -> launchPlayer(tr) },
                            layoutResId = R.layout.item_detail_card
                        )
                    }
                }
            }
    }

    private fun bindDetail(detail: EmbyItem) {
        binding.loadingIndicator.visibility = View.GONE

        try {
            // 标题
            binding.itemTitle.text = detail.name
            // 星光影院：香槟金渐变标题 + 衬线字体（布局A 规格）
            binding.itemTitle.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD), android.graphics.Typeface.BOLD)
            binding.itemTitle.post {
                if (isAdded && _binding != null && binding.itemTitle.width > 0) {
                    val w = binding.itemTitle.width.toFloat()
                    binding.itemTitle.paint.shader = android.graphics.LinearGradient(
                        0f, 0f, w, 0f,
                        intArrayOf(0xFFF6D98A.toInt(), 0xFFE8B64C.toInt(), 0xFFD9A735.toInt()),
                        null, android.graphics.Shader.TileMode.CLAMP
                    )
                    binding.itemTitle.invalidate()
                }
            }

            // 背景图（Backdrop → Thumb → Primary）
            val bgItemId = if (detail.type == "Episode" && !detail.seriesId.isNullOrBlank()) detail.seriesId else detail.id
            val fanartTag = detail.imageTags?.get("Thumb") ?: detail.imageTags?.get("Backdrop") ?: detail.imageTags?.get("Primary")
            val bgUrl = if (bgItemId == detail.id) {
                client.getImageUrl(baseUrl, bgItemId, fanartTag, apiKey, 1200)
            } else {
                client.getBackdropUrl(baseUrl, bgItemId, null, apiKey, 1200)
            }
            if (bgUrl != null) {
                EmbyImageLoader.load(binding.background, bgUrl)
            }

        // Logo（有才显示）
        val logoTag = detail.imageTags?.get("Logo")
        if (logoTag != null) {
            val logoUrl = client.getImageUrl(baseUrl, detail.id, logoTag, apiKey, 400, "Logo")
            if (logoUrl != null) {
                binding.logoImage.visibility = View.VISIBLE
                EmbyImageLoader.load(binding.logoImage, logoUrl)
            }
        }

        // 外部链接（IMDB / TMDb / 官网，点击浏览器打开）
        val extUrls = detail.externalUrls
        if (!extUrls.isNullOrEmpty()) {
            binding.externalLinksRow.removeAllViews()
            extUrls.take(6).forEach { (name, url) ->
                val px32 = 32.dpToPx()
                val px8 = 8.dpToPx()
                val btn = LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundResource(R.drawable.btn_select_circle)
                    isFocusable = true
                    isClickable = true
                    setPadding(14, 8, 14, 8)
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                        } catch (_: Exception) {}
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, px32
                    ).apply { marginEnd = px8 }
                    addView(ImageView(requireContext()).apply {
                        setImageResource(R.drawable.link_24px)
                        layoutParams = LinearLayout.LayoutParams(18.dpToPx(), 18.dpToPx())
                    })
                    addView(TextView(requireContext()).apply {
                        text = name
                        setTextColor(resources.getColor(R.color.white, null))
                        textSize = 14f
                        setPadding(px8, 0, 0, 0)
                    })
                    nextFocusUpId = R.id.name_box
                }
                binding.externalLinksRow.addView(btn)
            }
            binding.externalLinksRow.visibility = View.VISIBLE
        }

        // 评分行：评分 / 年份 / 时长（设计文档 v1.0：元信息行 年份·时长·★评分，去 [R] 分级与类型标签）
        binding.infoRow.visibility = View.VISIBLE
        if (detail.communityRating != null) {
            binding.ratingText.visibility = View.VISIBLE
            binding.ratingText.text = "★ ${"%.1f".format(detail.communityRating!!)}"
        }
        // 星光影院 v1.0：官方分级([R]/PG-13 等)不属于设计稿元信息行 → 保持 hidden
        if (detail.productionYear != null) {
            binding.yearText.visibility = View.VISIBLE
            binding.yearText.text = detail.productionYear.toString()
            binding.metaSep1.visibility = View.VISIBLE
        }
        val runtimeMin = detail.runTimeTicks?.let { it / 600000000 }
        if (runtimeMin != null && runtimeMin > 0) {
            binding.runtimeText.visibility = View.VISIBLE
            binding.runtimeText.text = "${runtimeMin} 分钟"
            binding.metaSep2.visibility = View.VISIBLE
        }
        // 星光影院 v1.0：类型标签(genre chips)不属于设计稿元信息行 → 保持 hidden
        // 季号（剧集详情时）
        if (detail.type == "Season" && detail.parentIndexNumber != null) {
            binding.seasonNumber.visibility = View.VISIBLE
            binding.seasonNumber.text = "S${detail.parentIndexNumber}"
        } else if (detail.type == "Episode" && detail.parentIndexNumber != null) {
            binding.seasonNumber.visibility = View.VISIBLE
            binding.seasonNumber.text = "S${detail.parentIndexNumber}E${detail.indexNumber ?: ""}"
        }

        // 操作按钮（AfuseKtV 风格：播放药丸 + 重播 + 收藏 + 更多 + 剧集）
        binding.actionBtnContainer.removeAllViews()
        addPlayButton(detail)
        // 重播：有播放进度时显示，点击从头播放（AfuseKtV re_play）
        val hasProgress = (detail.userData?.playbackPositionTicks ?: 0) > 0
        if (hasProgress) {
            addActionButton("", R.drawable.btn_select_circle, R.drawable.autoplay_24px) {
                val item2 = detail.copy()
                // 从头播放：清空进度
                launchPlayer(item2, restart = true)
            }
        }
        addActionButton("收藏", R.drawable.bg_btn_ghost, R.drawable.favorite_no_icon) { toggleFavorite(detail) }
        addActionButton("更多", R.drawable.btn_select_circle, R.drawable.more_vert) { showMoreMenu(detail) }
        if (detail.type == "Series") {
            addActionButton("剧集", R.drawable.btn_select_circle, R.drawable.episode) { scrollToEpisodes() }
        }
        // 详情页加载完成后聚焦第一个操作按钮（从单集进入时聚焦到剧集，不抢焦点）
        if (episodeIdHint == null) {
            binding.actionBtnContainer.post { binding.actionBtnContainer.getChildAt(0)?.requestFocus() }
        }

        // 简介（3行）
        if (!detail.overview.isNullOrBlank()) {
            binding.overviewText.visibility = View.VISIBLE
            binding.overviewText.text = detail.overview
        }

        // 剧集：Season/Series 都走分季选择（季海报行 + 当前季剧集）
        if (detail.type == "Season" || detail.type == "Series" || seriesIdFromArg != null) {
            val seriesId = if (detail.type == "Series") detail.id
            else detail.seriesId ?: seriesIdFromArg ?: detail.id
            // 定位：直接从单集点进来时，seriesId 就是详情 id；Season 类型找 seriesId
            loadSeasons(seriesId, detail)
        }

        // 演职人员（Emby People 优先；TMDB 补全演员用 TMDB 头像）
        val castPeopled = item?.people ?: detail.people
        if (!castPeopled.isNullOrEmpty()) {
            binding.castSection.visibility = View.VISIBLE
            val people = castPeopled.take(20)
            val names = people.map { it.name }
            val faceUrls = people.map { p ->
                // TMDB 补全的演员直接有 imageUrl；Emby 演员用 primaryImageTag 拼
                p.imageUrl ?: if (p.primaryImageTag != null && p.id != null) {
                    client.getImageUrl(baseUrl, p.id, p.primaryImageTag, apiKey, 120)
                } else null
            }
            binding.castRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            binding.castRecyclerView.adapter = CastAdapter(names, faceUrls) { pos ->
                if (pos < people.size) openPersonWorks(people[pos])
            }
        }

        // 媒体信息（独立方法，剧集加载选集流后重刷）
        bindMediaInfo(detail)
        } catch (e: Exception) {
            // 容错：防止详情渲染闪退
        }
    }

    /** 分季选择：加载季列表 → 季海报行 → 加载选中季的剧集 */
    private fun loadSeasons(seriesId: String, detail: EmbyItem) {
        lifecycleScope.launch {
            client.getSeasonList(baseUrl, apiKey, userId, seriesId).onSuccess { seasonItems ->
                val s = seasonItems.filter { it.type == "Season" }
                if (s.isEmpty()) return@onSuccess
                seasons = s
                binding.episodesSection.visibility = View.VISIBLE

                // 季选择行
                binding.seasonRecyclerView.visibility = View.VISIBLE
                binding.seasonRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                binding.seasonRecyclerView.adapter = SeasonAdapter(s, baseUrl, apiKey, client) { season ->
                    selectedSeasonId = season.id
                    loadSeasonEpisodes(season.id, detail)
                    // 聚焦当前季
                    binding.seasonRecyclerView.post {
                        val idx = s.indexOfFirst { it.id == season.id }
                        binding.seasonRecyclerView.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
                    }
                }

                // 默认选中季：从单集进入时用其 seasonId；Season 类型用自身 id；否则第一季
                val defaultSeason = when {
                    selectedSeasonId != null -> s.firstOrNull { it.id == selectedSeasonId } ?: s.first()
                    detail.type == "Season" && !detail.seriesId.isNullOrBlank() ->
                        s.firstOrNull { it.id == detail.id } ?: s.first()
                    else -> s.first()
                }
                selectedSeasonId = defaultSeason.id
                loadSeasonEpisodes(defaultSeason.id, detail)
            }
        }
    }

    /** 媒体信息：视频卡/音频卡/字幕卡 三卡横排（星光影院 v1.0 暗金风格，来自 MediaStreams 全量） */
    private fun bindMediaInfo(detail: EmbyItem) {
        try {
            binding.mediaInfoRow.visibility = View.VISIBLE
            binding.videoInfoCard.removeAllViews()
            binding.audioInfoCard.removeAllViews()
            binding.subtitleInfoCard.removeAllViews()

            // 优先取 MediaSources 里的原始流列表（含声道/采样率/语言/外挂等全量字段）
            val ms = detail.mediaSources?.firstOrNull()
            val rawStreams = ms?.mediaStreams ?: emptyList()
            val videoStream = rawStreams.firstOrNull { it["Type"] == "Video" }
            val audioStreams = rawStreams.filter { it["Type"] == "Audio" }
            val subtitleStreams = rawStreams.filter { it["Type"] == "Subtitle" }
            // 兜底：PlaybackInfo 概要（详情接口没返回 MediaStreams 时）
            val stream = detail.videoStreamInfo

            // ===== 视频卡 =====
            val hasVideo = videoStream != null || (stream != null && stream.codec != null)
            if (hasVideo) {
                addCardTitle(binding.videoInfoCard, "视频")
                val vs = videoStream
                if (vs != null) {
                    val res = when {
                        toInt(vs["Height"]) != null && toInt(vs["Height"])!! >= 2160 -> "4K"
                        toInt(vs["Height"]) != null && toInt(vs["Height"])!! >= 1080 -> "1080p"
                        toInt(vs["Height"]) != null && toInt(vs["Height"])!! >= 720 -> "720p"
                        toInt(vs["Height"]) != null -> "${toInt(vs["Height"])}p"
                        else -> null
                    }
                    addInfoRow(binding.videoInfoCard, "编码", (vs["Codec"] as? String)?.uppercase(java.util.Locale.US))
                    addInfoRow(binding.videoInfoCard, "分辨率", res)
                    val fps = toFloat(vs["RealFrameRate"]) ?: toFloat(vs["AverageFrameRate"])
                    addInfoRow(binding.videoInfoCard, "帧率", fps?.let { "%.0f fps".format(it) })
                    val br = toLong(vs["BitRate"]) ?: toLong(vs["Bitrate"])
                    addInfoRow(binding.videoInfoCard, "码率", br?.let { String.format("%.1f Mbps", it / 1000000.0) })
                    (vs["Profile"] as? String)?.let { p ->
                        val hdr = when {
                            p.contains("HDR10+") -> "HDR10+"
                            p.contains("HDR10") -> "HDR10"
                            p.contains("Dolby Vision") || p.contains("DV") -> "DV"
                            p.contains("HLG") -> "HLG"
                            else -> null
                        }
                        addInfoRow(binding.videoInfoCard, "范围", hdr)
                    }
                } else {
                    val res = if (stream?.height != null) {
                        when {
                            stream.height!! >= 2160 -> "4K"; stream.height!! >= 1080 -> "1080p"
                            stream.height!! >= 720 -> "720p"; else -> "${stream.height}p"
                        }
                    } else null
                    addInfoRow(binding.videoInfoCard, "编码", stream?.codec?.uppercase(java.util.Locale.US))
                    addInfoRow(binding.videoInfoCard, "分辨率", res)
                    stream?.frameRate?.let { addInfoRow(binding.videoInfoCard, "帧率", "%.0f fps".format(it)) }
                    stream?.bitRate?.let { addInfoRow(binding.videoInfoCard, "码率", String.format("%.1f Mbps", it / 1000000.0)) }
                }
            } else {
                binding.videoInfoCard.visibility = View.GONE
            }

            // ===== 音频卡 =====
            if (audioStreams.isNotEmpty() || (stream?.audioStreams?.isNotEmpty() == true)) {
                addCardTitle(binding.audioInfoCard, "音频")
                val audios = if (audioStreams.isNotEmpty()) audioStreams else emptyList()
                val fallbackCodecs = stream?.audioStreams ?: emptyList()
                if (audios.isNotEmpty()) {
                    audios.forEachIndexed { i, a ->
                        val codec = (a["Codec"] as? String)?.uppercase(java.util.Locale.US)
                        val ch = toInt(a["Channels"])
                        val lang = (a["Language"] as? String)?.let { it.uppercase(java.util.Locale.US) }
                        val display = (a["DisplayTitle"] as? String)?.trim()
                        val title = if (lang != null) "$codec · $lang" else codec
                        addInfoRow(binding.audioInfoCard,
                            if (audios.size > 1) "音轨${i + 1}" else "编码",
                            display ?: title ?: "—")
                        if (ch != null && ch > 0 && audios.size == 1) {
                            addInfoRow(binding.audioInfoCard, "声道", when (ch) {
                                1 -> "单声道"; 2 -> "双声道"; 6 -> "5.1"; 8 -> "7.1"; else -> "$ch 声道"
                            })
                        }
                    }
                } else {
                    addInfoRow(binding.audioInfoCard, "编码", fallbackCodecs.map { it.uppercase(java.util.Locale.US) }.joinToString(" "))
                }
            } else {
                binding.audioInfoCard.visibility = View.GONE
            }

            // ===== 字幕卡 =====
            if (subtitleStreams.isNotEmpty() || (stream?.subtitleStreams ?: 0) > 0) {
                addCardTitle(binding.subtitleInfoCard, "字幕")
                if (subtitleStreams.isNotEmpty()) {
                    addInfoRow(binding.subtitleInfoCard, "数量", "${subtitleStreams.size} 条")
                    subtitleStreams.take(4).forEach { s ->
                        val lang = (s["Language"] as? String)?.let { it.uppercase(java.util.Locale.US) }
                            ?: (s["DisplayTitle"] as? String)?.takeIf { it.isNotBlank() }?.substringBefore("(")?.trim()
                            ?: "未知"
                        val ext = (s["IsExternal"] as? Boolean) == true
                        val isDefault = (s["IsDefault"] as? Boolean) == true
                        val mark = when {
                            isDefault -> "●"; else -> if (ext) "外挂" else "内嵌"
                        }
                        addInfoRow(binding.subtitleInfoCard, "语言", "$lang $mark")
                    }
                } else {
                    addInfoRow(binding.subtitleInfoCard, "数量", "${stream?.subtitleStreams} 条")
                }
            } else {
                binding.subtitleInfoCard.visibility = View.GONE
            }

            // 全空则隐藏整行
            val anyVisible = binding.videoInfoCard.visibility == View.VISIBLE ||
                binding.audioInfoCard.visibility == View.VISIBLE ||
                binding.subtitleInfoCard.visibility == View.VISIBLE
            binding.mediaInfoRow.visibility = if (anyVisible) View.VISIBLE else View.GONE
        } catch (e: Exception) {
            // 容错：防止详情渲染闪退
        }
    }

    /** 三卡标题行：金色小图标 + 金色标题 */
    private fun addCardTitle(card: LinearLayout, title: String) {
        val px18 = 18.dpToPx()
        val px10 = 10.dpToPx()
        val iconRes = when (title) {
            "视频" -> R.drawable.ic_action_play_white
            "音频" -> R.drawable.exo_ic_audiotrack
            else -> R.drawable.exo_ic_subtitle_on
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px10 }
            addView(ImageView(requireContext()).apply {
                setImageResource(iconRes)
                setColorFilter(resources.getColor(R.color.star_gold, null), android.graphics.PorterDuff.Mode.SRC_ATOP)
                layoutParams = LinearLayout.LayoutParams(px18, px18)
            })
            addView(TextView(requireContext()).apply {
                text = title
                setTextColor(resources.getColor(R.color.star_gold, null))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(px10, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
        }
        card.addView(row)
    }

    /** 三卡信息行：灰色标签 + 白色值 */
    private fun addInfoRow(card: LinearLayout, label: String, value: String?) {
        if (value.isNullOrBlank()) return
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 4) }
            addView(TextView(requireContext()).apply {
                text = label
                setTextColor(resources.getColor(R.color.apple_text_tertiary, null))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(44.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(requireContext()).apply {
                text = value
                setTextColor(resources.getColor(R.color.white, null))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }
        card.addView(row)
    }

    /** 加载某一季的剧集列表 */
    private fun loadSeasonEpisodes(seasonId: String, detail: EmbyItem) {
        lifecycleScope.launch {
            client.getEpisodesInSeason(baseUrl, apiKey, userId, seasonId).onSuccess { eps ->
                if (eps.isNotEmpty()) {
                    episodeList = eps
                    binding.episodeRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                    // 星光影院：分集行标题 "第N季 · 共X集"
                    val seasonLabel = episodeList.firstOrNull()?.seasonName
                        ?: seasons.firstOrNull { it.id == seasonId }?.name
                        ?: "本季"
                    binding.episodeRowTitle.text = "$seasonLabel · 共${eps.size}集"
                    binding.episodeRecyclerView.adapter = EpisodeAdapter(
                        eps, baseUrl, apiKey, client,
                        onClick = { ep -> launchPlayer(ep) }
                    )
                    // 从单集进入：滚动并聚焦到那一集
                    val hint = episodeIdHint
                    if (hint != null) {
                        val pos = eps.indexOfFirst { it.id == hint }
                        if (pos >= 0) {
                            binding.episodeRecyclerView.layoutManager?.scrollToPosition(pos)
                            // 延迟重试聚焦（RecyclerView 布局完成前 ViewHolder 可能不存在）
                            listOf(100L, 300L, 600L).forEach { delayMs ->
                                binding.episodeRecyclerView.postDelayed({
                                    try {
                                        binding.episodeRecyclerView.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                                    } catch (_: Exception) {}
                                }, delayMs)
                            }
                            episodeIdHint = null
                        }
                    }

                    // 剧集详情（Series/Season 本身无 MediaStreams）：用当前季第一集的媒体流信息刷媒体卡
                    val cur = item
                    if (cur != null && cur.videoStreamInfo == null) {
                        val source = eps.firstOrNull { it.videoStreamInfo != null }
                        if (source != null) {
                            // mediaSources 也要带上（三卡需要完整 MediaStreams 流列表）
                            item = cur.copy(
                                videoStreamInfo = source.videoStreamInfo,
                                mediaSources = source.mediaSources ?: cur.mediaSources
                            )
                            bindMediaInfo(item!!)
                        }
                    }
                }
            }
        }
    }

    // ==================== 操作按钮 ====================

    /** 星光影院：金色胶囊播放按钮（黑图标+黑字"立即播放"） */
    private fun addPlayButton(detail: EmbyItem) {
        val px40 = 40.dpToPx()
        val px20 = 20.dpToPx()
        val px8 = 8.dpToPx()
        val btn = LinearLayout(requireContext()).apply {
            id = android.view.View.generateViewId()
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_btn_gold)
            isFocusable = true
            isClickable = true
            setPadding(24, 12, 24, 12)
            setOnClickListener { launchPlayer(detail) }
            // 🔴 星光影院：OK 键(23/66)不触发 click 的兜底 —— 显式转发 performClick
            setOnKeyListener { v, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    v.performClick()
                    true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, px40
            ).apply { marginEnd = px8 }
            addView(ImageView(requireContext()).apply {
                setImageResource(R.drawable.ic_action_play_white)
                setColorFilter(resources.getColor(R.color.star_bg, null), android.graphics.PorterDuff.Mode.SRC_ATOP)
                layoutParams = LinearLayout.LayoutParams(px20, px20)
            })
            addView(TextView(requireContext()).apply {
                text = "立即播放"
                setTextColor(resources.getColor(R.color.star_bg, null))
                textSize = 16f
                setPadding(px8, 0, 0, 0)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            // 向上指向顶部标题锚点（name_box 始终可见可聚焦），不再自引用锁死焦点
            nextFocusUpId = R.id.name_box
        }
        binding.actionBtnContainer.addView(btn)
    }

    /** 图标按钮：圆角 + 图标（AfuseKtV btn_select_circle 风格） */
    private fun addActionButton(label: String, bg: Int, icon: Int, onClick: () -> Unit) {
        val px40 = 40.dpToPx()
        val px20 = 20.dpToPx()
        val px8 = 8.dpToPx()
        val btn = LinearLayout(requireContext()).apply {
            id = android.view.View.generateViewId()
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(bg)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            // 🔴 星光影院：OK 键(23/66)不触发 click 的兜底 —— 显式转发 performClick
            setOnKeyListener { v, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    v.performClick()
                    true
                } else false
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, px40
            ).apply { marginEnd = px8 }
            addView(ImageView(requireContext()).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(px20, px20)
                setColorFilter(resources.getColor(R.color.white, null), android.graphics.PorterDuff.Mode.SRC_ATOP)
            })
            if (label.isNotEmpty()) {
                addView(TextView(requireContext()).apply {
                    this.text = label
                    setTextColor(resources.getColor(R.color.white, null))
                    textSize = 14f
                    setPadding(px8, 0, 0, 0)
                })
            }
            // 向上指向顶部标题锚点（name_box 始终可见可聚焦），不再自引用锁死焦点
            nextFocusUpId = R.id.name_box
        }
        binding.actionBtnContainer.addView(btn)
    }

    private fun toggleFavorite(item: EmbyItem) {
        val target = !(item.userData?.isFavorite ?: false)
        lifecycleScope.launch {
            // 服务器已更新（isFavorite 是数据类 val 不可变，本地不强制刷新）
            client.updateFavorite(baseUrl, apiKey, userId, item.id, target)
            // 打标记：返回首页时刷新收藏行
            favoriteChanged = true
        }
    }

    private fun showMoreMenu(item: EmbyItem) {
        val options = arrayOf("标记已看", "标记未看", "取消")
        val actions = arrayOf(
            { lifecycleScope.launch { client.markPlayed(baseUrl, apiKey, userId, item.id) } },
            { lifecycleScope.launch { client.markUnplayed(baseUrl, apiKey, userId, item.id) } },
            {}
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setItems(options) { _, which -> actions[which]() }
            .show()
    }

    private fun scrollToEpisodes() {
        if (binding.episodesSection.visibility == View.VISIBLE) {
            (binding.root.parent as? ViewGroup)?.let {
                // 简单滚动到剧集区域
                binding.episodesSection.requestFocus()
            }
        }
    }

    private fun openDetail(id: String) {
        try {
            val bundle = Bundle().apply { putString("itemId", id) }
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_container, DetailFragment::class.java, bundle)
                .addToBackStack("detail")
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            // 容错：防止跳转闪退
        }
    }

    private fun launchPlayer(item: EmbyItem, restart: Boolean = false) {
            val server = PreferencesHelper(requireContext()).activeEmbyServer() ?: return
            // 如果 item 是 Series/Season 类型且有剧集列表，定位到上次播放的剧集（或第一集）
            val isCollection = item.type == "Series" || item.type == "Season"
            val targetItem = if (isCollection && episodeList.isNotEmpty()) {
                val lastPlayed = episodeList.maxByOrNull { it.userData?.playbackPositionTicks ?: 0 }
                lastPlayed ?: episodeList.first()
            } else item
            val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
                putExtra("source", "${server.baseUrl}/emby/Videos/${targetItem.id}/stream?static=true&api_key=${server.accessToken}")
                putExtra("title", targetItem.name)
                // 🎯 剧集名: 弹幕搜索预填用 (单集名识别不到, 用剧集名才能搜到番剧)
                putExtra("seriesName", targetItem.seriesName ?: item.name)
                putExtra("itemId", targetItem.id)
                putExtra("embyBaseUrl", server.baseUrl)
                putExtra("embyApiKey", server.accessToken)
                putExtra("position", if (restart) 0 else (targetItem.userData?.playbackPositionTicks ?: 0) / 10000)
                // 传递播放队列（JSON [{url,title},...]）
                if (episodeList.isNotEmpty()) {
                    val queueJson = com.google.gson.Gson().toJson(episodeList.map { ep ->
                        mapOf(
                            "url" to "${server.baseUrl}/emby/Videos/${ep.id}/stream?static=true&api_key=${server.accessToken}",
                            "title" to (ep.name ?: "")
                        )
                    })
                    putExtra("queue", queueJson)
                }
            }
            startActivity(intent)
        }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    /** 打开演员作品页 */
    private fun openPersonWorks(person: EmbyPerson) {
        try {
            val bundle = Bundle().apply {
                putString("personId", person.id ?: person.name)
                putString("personName", person.name)
                putString("personImageTag", person.primaryImageTag)
            }
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_container, PersonWorksFragment::class.java, bundle)
                .addToBackStack("detail")
                .commitAllowingStateLoss()
        } catch (e: Exception) {
            // 容错：防止跳转闪退
        }
    }

    override fun onResume() {
        super.onResume()
        // 从播放器返回后恢复焦点
        if (hasLoaded) {
            binding.actionBtnContainer.post { binding.actionBtnContainer.getChildAt(0)?.requestFocus() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** 剧集列表适配器：SxxExx + 剧集名 + 焦点处理 */
class EpisodeAdapter(
    private val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val layoutResId: Int = R.layout.item_episode_card,
    private val onClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    private val imageUrls = items.map { item ->
        val isEpisode = item.type == "Episode" && !item.seriesId.isNullOrBlank()
        val tag = item.imageTags?.get("Primary") ?: item.primaryImageTag
        if (tag != null) {
            // 有图：episode 用自己的图，其他用自己的 id
            client.getImageUrl(baseUrl, item.id, tag, apiKey, 320)
        } else if (isEpisode) {
            // 单集无图时用 Series 的 Primary 图（不传 tag，Emby 返回最新版）
            "$baseUrl/emby/Items/${item.seriesId}/Images/Primary?maxHeight=320&quality=60&api_key=$apiKey"
        } else null
    }

    fun epLabel(item: EmbyItem): String {
        val ep = item.indexNumber?.let { "E%02d".format(it) } ?: ""
        val season = item.parentIndexNumber?.let { "S%02d".format(it) }
            ?: item.seasonName?.let { sn -> Regex("\\d+").find(sn)?.value?.toIntOrNull()?.let { "S%02d".format(it) } } ?: ""
        return if (season.isNotEmpty() || ep.isNotEmpty()) "$season$ep" else ""
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(layoutResId, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(h: ViewHolder, pos: Int) {
        val item = items[pos]
        val scale = FocusStyleHelper.scaleMultiplier(h.itemView.context)
        val hidden = FocusStyleHelper.hidden(h.itemView.context)
        val label = epLabel(item)
        h.subtitle.text = label
        h.title.text = item.name
        // 星光影院：时长标签（右上角，mm:ss）
        val rt = item.runTimeTicks
        h.durationBadge?.text = if (rt != null && rt > 0) {
            val sec = (rt / 10_000_000).toInt()
            "%d:%02d".format(sec / 60, sec % 60)
        } else ""
        EmbyImageLoader.load(h.image, imageUrls[pos])
        h.itemView.setOnClickListener { onClick(item) }
        
        // 🔴 星光影院：OK 键(23/66)不触发 click 的兜底 —— 显式转发 performClick
        h.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                v.performClick()
                true
            } else false
        }
        h.itemView.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, hasFocus ->
            val card = h.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(120).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, h.itemView.context)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
            // 星光影院：聚焦时集数文字变白（未聚焦灰白）
            h.subtitle.setTextColor(
                if (hasFocus) android.graphics.Color.WHITE
                else h.itemView.context.getColor(R.color.star_text_secondary)
            )
        }
    }

    class ViewHolder(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.posterImage)
        val title: TextView = v.findViewById(R.id.titleText)
        val subtitle: TextView = v.findViewById(R.id.subtitleText)
        val durationBadge: TextView? = v.findViewById(R.id.durationBadge)
        val imageBox: View? = v.findViewById(R.id.imageBox)
    }
}

/** 季选择适配器：横向季海报，聚焦/点击切换该季剧集 */
class SeasonAdapter(
    private val seasons: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<SeasonAdapter.ViewHolder>() {

    private val imageUrls = seasons.map { s ->
        // 季海报优先 Primary，无则用 Thumb（Season 常见只有 Thumb）
        val tag = s.imageTags?.get("Primary") ?: s.imageTags?.get("Thumb") ?: s.primaryImageTag
        if (tag != null) client.getImageUrl(baseUrl, s.id, tag, apiKey, 320) else null
    }

    override fun getItemCount() = seasons.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_season_tab, parent, false)
        return ViewHolder(v)
    }
    override fun onBindViewHolder(h: ViewHolder, pos: Int) {
        val s = seasons[pos]
        h.text.text = s.name ?: "S${s.indexNumber ?: "?"}"
        // 星光影院：胶囊标签——聚焦/选中=金底黑字，未选中=深灰底灰字
        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_sidebar_focus else R.drawable.bg_season_tab_idle)
            h.text.setTextColor(
                if (hasFocus) v.context.getColor(R.color.star_bg)
                else v.context.getColor(R.color.star_text_secondary)
            )
        }
        h.itemView.setOnClickListener { onClick(s) }

        // 🔴 星光影院：OK 键(23/66)不触发 click 的兜底 —— 显式转发 performClick
        h.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                v.performClick()
                true
            } else false
        }

    }
    class ViewHolder(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.seasonTabText)
    }
}

/** 简单海报 RecyclerView 适配器（横版卡用 Thumb/Backdrop 图源，避免竖版海报被裁切） */
class SimplePosterAdapter(
    private val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val layoutResId: Int = R.layout.item_poster_card,
    private val onClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<SimplePosterAdapter.ViewHolder>() {

    private val landscape = layoutResId == R.layout.item_detail_landscape
    private val imageUrls = items.map { item ->
        val id = item.id
        if (landscape) {
            val tag = item.imageTags?.get("Thumb") ?: item.imageTags?.get("Backdrop") ?: item.imageTags?.get("Primary")
            if (tag != null) client.getImageUrl(baseUrl, id, tag, apiKey, 480, "Thumb")
            else client.getBackdropUrl(baseUrl, id, null, apiKey, 480)
        } else {
            client.getImageUrl(baseUrl, id, item.imageTags?.get("Primary") ?: item.primaryImageTag, apiKey, 320)
        }
    }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(layoutResId, parent, false)
        return ViewHolder(v)
    }
    override fun onBindViewHolder(h: ViewHolder, pos: Int) {
        val item = items[pos]
        val scale = FocusStyleHelper.scaleMultiplier(h.itemView.context)
        val hidden = FocusStyleHelper.hidden(h.itemView.context)
        h.title.text = item.name
        // 星光影院：横版卡副标题填年份（设计稿相关推荐规格）
        if (landscape) {
            h.subtitle?.text = item.productionYear?.toString() ?: ""
        }
        // 设计稿：相关推荐无角标
        EmbyImageLoader.load(h.image, imageUrls[pos])
        h.itemView.setOnClickListener { onClick(item) }
        
        // 🔴 星光影院：OK 键(23/66)不触发 click 的兜底 —— 显式转发 performClick
        h.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                v.performClick()
                true
            } else false
        }
        h.itemView.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, hasFocus ->
            val card = h.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(120).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, h.itemView.context)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
            // 星光影院：聚焦时片名变金（演员作品页规格）
            h.title.setTextColor(
                if (hasFocus) v.context.getColor(R.color.star_gold)
                else v.context.getColor(R.color.white)
            )
        }
    }
    class ViewHolder(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.posterImage)
        val title: TextView = v.findViewById(R.id.titleText)
        val subtitle: TextView? = v.findViewById(R.id.subtitleText)
        val unwatchedBadge: TextView? = null // 设计稿：无角标
        val imageBox: View? = v.findViewById(R.id.imageBox)
    }
}

/** 演职人员适配器（支持 Emby 人物 + TMDB 演员，TMDB 头像走 image URL） */
class CastAdapter(
    private val names: List<String>,
    private val imageUrls: List<String?>,
    private val onClick: (Int) -> Unit = {}
) : RecyclerView.Adapter<CastAdapter.ViewHolder>() {

    override fun getItemCount() = names.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cast_card, parent, false)
        return ViewHolder(v)
    }
    override fun onBindViewHolder(h: ViewHolder, pos: Int) {
        val scale = FocusStyleHelper.scaleMultiplier(h.itemView.context)
        val hidden = FocusStyleHelper.hidden(h.itemView.context)
        h.title.text = names[pos]
        val url = imageUrls.getOrNull(pos)
        if (url.isNullOrBlank()) {
            // 无图：显示首字占位
            h.image.setImageDrawable(null)
            h.image.setBackgroundColor(0x33FFFFFF.toInt())
        } else {
            EmbyImageLoader.load(h.image, url)
        }
        h.itemView.setOnClickListener { onClick(pos) }
        // 🔴 星光影院：OK 键(23/66)不触发 click 的兜底 —— 显式转发 performClick
        h.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                v.performClick()
                true
            } else false
        }
        h.itemView.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, hasFocus ->
            val card = h.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(120).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, h.itemView.context)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
            // 星光影院：聚焦时名字变金（设计稿 A2 规格）
            h.title.setTextColor(
                if (hasFocus) v.context.getColor(R.color.star_gold)
                else v.context.getColor(R.color.star_text_secondary)
            )
        }
    }
    class ViewHolder(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.posterImage)
        val title: TextView = v.findViewById(R.id.titleText)
        val imageBox: View? = v.findViewById(R.id.imageBox)
    }
}
