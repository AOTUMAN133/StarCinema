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
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem
import com.starcinema.api.EmbyPerson
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentDetailBinding
import kotlinx.coroutines.launch

/**
 * 详情页（设计文档 v1.0 界面04/05）：
 * 背景海报 + 金渐变标题 + 元信息行 + 简介2行 + 立即播放/收藏 + 主演 + 分季/分集 + 相关推荐 + 媒体三卡
 */
class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!
    private val client = EmbyClient()
    private var baseUrl = ""
    private var apiKey = ""
    private var userId = ""
    private var item: EmbyItem? = null
    private var episodeIdHint: String? = null
    private var seriesIdFromArg: String? = null
    private var selectedSeasonId: String? = null
    private var episodeList = emptyList<EmbyItem>()
    private val seasons = mutableListOf<EmbyItem>()

    companion object {
        fun open(fm: FragmentManager, item: EmbyItem, backStackName: String = "detail") {
            val frag = DetailFragment().apply {
                arguments = Bundle().apply { putString("itemId", item.id) }
            }
            fm.beginTransaction()
                .replace(R.id.container, frag)
                .addToBackStack(backStackName)
                .commitAllowingStateLoss()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val server = PreferencesHelper(requireContext()).activeEmbyServer()
        if (server == null) { requireActivity().supportFragmentManager.popBackStack(); return }
        baseUrl = server.baseUrl; apiKey = server.accessToken; userId = server.userId
        client.serverType = server.serverType
        val itemId = arguments?.getString("itemId")
        if (itemId == null) { requireActivity().supportFragmentManager.popBackStack(); return }
        loadDetail(itemId)
    }

    private fun loadDetail(itemId: String) {
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            client.getItemDetail(baseUrl, apiKey, userId, itemId).onSuccess { detail ->
                if (!isAdded || _binding == null) return@onSuccess
                item = detail
                bindDetail(detail)
            }.onFailure {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun bindDetail(detail: EmbyItem) {
        binding.loadingIndicator.visibility = View.GONE
        try {
            binding.itemTitle.text = detail.name
            binding.itemTitle.setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
            binding.itemTitle.post {
                if (isAdded && _binding != null && binding.itemTitle.width > 0) {
                    binding.itemTitle.paint.shader = android.graphics.LinearGradient(
                        0f, 0f, binding.itemTitle.width.toFloat(), 0f,
                        intArrayOf(0xFFF6D98A.toInt(), 0xFFE8B64C.toInt(), 0xFFD9A735.toInt()),
                        null, android.graphics.Shader.TileMode.CLAMP
                    )
                    binding.itemTitle.invalidate()
                }
            }

            // 背景：Backdrop → Thumb → Primary
            val bgItemId = if (detail.type == "Episode" && !detail.seriesId.isNullOrBlank()) detail.seriesId else detail.id
            val fanartTag = detail.imageTags?.get("Thumb") ?: detail.imageTags?.get("Backdrop") ?: detail.imageTags?.get("Primary")
            val bgUrl = if (bgItemId == detail.id) {
                client.getImageUrl(baseUrl, bgItemId, fanartTag, apiKey, 1200)
            } else client.getBackdropUrl(baseUrl, bgItemId, null, apiKey, 1200)
            if (bgUrl != null) EmbyImageLoader.load(binding.background, bgUrl)

            // 元信息行：★评分 · 年份 · 时长（设计文档 v1.0，去 [R] 分级与类型）
            binding.infoRow.visibility = View.VISIBLE
            if (detail.communityRating != null) {
                binding.ratingText.visibility = View.VISIBLE
                binding.ratingText.text = "★ ${"%.1f".format(detail.communityRating!!)}"
            }
            if (detail.productionYear != null) {
                binding.yearText.visibility = View.VISIBLE
                binding.yearText.text = detail.productionYear.toString()
                binding.metaSep1.visibility = View.VISIBLE
            }
            val runtimeMin = detail.runTimeTicks?.let { it / 600000000 }
            if (runtimeMin != null && runtimeMin > 0) {
                binding.runtimeText.visibility = View.VISIBLE
                binding.runtimeText.text = "${runtimeMin} 分钟"
            }

            // 简介 2 行
            if (!detail.overview.isNullOrBlank()) {
                binding.overviewText.visibility = View.VISIBLE
                binding.overviewText.text = detail.overview
            }

            // 操作按钮：立即播放（金色实心）+ 收藏（描边）+ 更多；剧集详情加"剧集"
            binding.actionBtnContainer.removeAllViews()
            addPlayButton(detail)
            addActionButton("收藏", R.drawable.bg_btn_ghost, R.drawable.ic_sidebar_home) { toggleFavorite(detail) }
            addActionButton("更多", R.drawable.bg_btn_ghost, R.drawable.ic_sidebar_search) { showMoreMenu(detail) }
            if (detail.type == "Series" || detail.type == "Season") {
                addActionButton("剧集", R.drawable.bg_btn_ghost, R.drawable.ic_sidebar_library) { scrollToEpisodes() }
            }
            binding.actionBtnContainer.post { binding.actionBtnContainer.getChildAt(0)?.requestFocus() }

            // 剧集：分季 + 分集
            if (detail.type == "Season" || detail.type == "Series" || seriesIdFromArg != null) {
                val seriesId = if (detail.type == "Series") detail.id
                else detail.seriesId ?: seriesIdFromArg ?: detail.id
                loadSeasons(seriesId, detail)
            }

            // 主演
            val people = detail.people
            if (!people.isNullOrEmpty()) {
                binding.castSection.visibility = View.VISIBLE
                binding.castRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                binding.castRecyclerView.adapter = CastAdapter(people.take(20), baseUrl, apiKey, client) { p ->
                    openPersonWorks(p)
                }
            }

            // 相关推荐：横版 4 张（Thumb/Backdrop，异步加载）
            lifecycleScope.launch {
                client.getSimilarItems(baseUrl, apiKey, userId, detail.id, 4).onSuccess { recs ->
                    if (!isAdded || _binding == null || recs.isEmpty()) return@onSuccess
                    binding.recommendSection.visibility = View.VISIBLE
                    binding.recommendRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                    binding.recommendRecyclerView.adapter = RecommendAdapter(recs, baseUrl, apiKey, client) { rec ->
                        openDetail(rec.id)
                    }
                }
            }

            bindMediaInfo(detail)
        } catch (_: Exception) {}
    }

    // ===== 媒体三卡 =====
    private fun bindMediaInfo(detail: EmbyItem) {
        try {
            val ms = detail.mediaSources?.firstOrNull()
            val rawStreams = ms?.mediaStreams ?: emptyList()
            val videoStream = rawStreams.firstOrNull { it["Type"] == "Video" }
            val audioStreams = rawStreams.filter { it["Type"] == "Audio" }
            val subtitleStreams = rawStreams.filter { it["Type"] == "Subtitle" }

            // 视频卡
            if (videoStream != null) {
                binding.videoInfoCard.removeAllViews()
                addCardTitle(binding.videoInfoCard, "视频")
                addInfoRow(binding.videoInfoCard, "编码", (videoStream["Codec"] as? String)?.uppercase(java.util.Locale.US))
                val h = toInt(videoStream["Height"])
                val res = when { h == null -> null; h >= 2160 -> "4K"; h >= 1080 -> "1080p"; h >= 720 -> "720p"; else -> "${h}p" }
                addInfoRow(binding.videoInfoCard, "分辨率", res)
                val fps = toFloat(videoStream["RealFrameRate"]) ?: toFloat(videoStream["AverageFrameRate"])
                addInfoRow(binding.videoInfoCard, "帧率", fps?.let { "%.0f fps".format(it) })
                val br = toLong(videoStream["BitRate"]) ?: toLong(videoStream["Bitrate"])
                addInfoRow(binding.videoInfoCard, "码率", br?.let { String.format("%.1f Mbps", it / 1000000.0) })
            } else binding.videoInfoCard.visibility = View.GONE

            // 音频卡
            if (audioStreams.isNotEmpty()) {
                binding.audioInfoCard.removeAllViews()
                addCardTitle(binding.audioInfoCard, "音频")
                audioStreams.forEachIndexed { i, a ->
                    val codec = (a["Codec"] as? String)?.uppercase(java.util.Locale.US)
                    val lang = (a["Language"] as? String)?.let { it.uppercase(java.util.Locale.US) }
                    val display = (a["DisplayTitle"] as? String)?.trim()
                    addInfoRow(binding.audioInfoCard,
                        if (audioStreams.size > 1) "音轨${i + 1}" else "编码",
                        display ?: listOfNotNull(codec, lang).joinToString(" · ") ?: "—")
                    val ch = toInt(a["Channels"])
                    if (ch != null && ch > 0 && audioStreams.size == 1) {
                        addInfoRow(binding.audioInfoCard, "声道", when (ch) {
                            1 -> "单声道"; 2 -> "双声道"; 6 -> "5.1"; 8 -> "7.1"; else -> "$ch 声道"
                        })
                    }
                }
            } else binding.audioInfoCard.visibility = View.GONE

            // 字幕卡
            if (subtitleStreams.isNotEmpty()) {
                binding.subtitleInfoCard.removeAllViews()
                addCardTitle(binding.subtitleInfoCard, "字幕")
                addInfoRow(binding.subtitleInfoCard, "数量", "${subtitleStreams.size} 条")
                subtitleStreams.take(4).forEach { s ->
                    val lang = (s["Language"] as? String)?.let { it.uppercase(java.util.Locale.US) }
                        ?: (s["DisplayTitle"] as? String)?.takeIf { it.isNotBlank() }?.substringBefore("(")?.trim()
                        ?: "未知"
                    val ext = (s["IsExternal"] as? Boolean) == true
                    val isDefault = (s["IsDefault"] as? Boolean) == true
                    val mark = when { isDefault -> "●"; ext -> "外挂"; else -> "内嵌" }
                    addInfoRow(binding.subtitleInfoCard, "语言", "$lang $mark")
                }
            } else binding.subtitleInfoCard.visibility = View.GONE

            val anyVisible = binding.videoInfoCard.visibility == View.VISIBLE ||
                binding.audioInfoCard.visibility == View.VISIBLE ||
                binding.subtitleInfoCard.visibility == View.VISIBLE
            binding.mediaInfoRow.visibility = if (anyVisible) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    private fun addCardTitle(card: LinearLayout, title: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
            addView(TextView(requireContext()).apply {
                text = title
                setTextColor(resources.getColor(R.color.gold_primary, null))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }
        card.addView(row)
    }

    private fun addInfoRow(card: LinearLayout, label: String, value: String?) {
        if (value.isNullOrBlank()) return
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, dp(4)) }
            addView(TextView(requireContext()).apply {
                text = label
                setTextColor(resources.getColor(R.color.text_muted, null))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(requireContext()).apply {
                text = value
                setTextColor(resources.getColor(R.color.text_primary, null))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }
        card.addView(row)
    }

    // ===== 分季/分集 =====
    private fun loadSeasons(seriesId: String, detail: EmbyItem) {
        lifecycleScope.launch {
            client.getSeasonList(baseUrl, apiKey, userId, seriesId).onSuccess { seasonItems ->
                val s = seasonItems.filter { it.type == "Season" }
                if (s.isEmpty()) return@onSuccess
                seasons.clear(); seasons.addAll(s)
                binding.episodesSection.visibility = View.VISIBLE
                binding.seasonRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                binding.seasonRecyclerView.adapter = SeasonAdapter(s) { season ->
                    selectedSeasonId = season.id
                    loadSeasonEpisodes(season.id, detail)
                }
                val defaultSeason = when {
                    detail.type == "Season" && !detail.seriesId.isNullOrBlank() -> s.firstOrNull { it.id == detail.id } ?: s.first()
                    else -> s.first()
                }
                selectedSeasonId = defaultSeason.id
                loadSeasonEpisodes(defaultSeason.id, detail)
            }
        }
    }

    private fun loadSeasonEpisodes(seasonId: String, detail: EmbyItem) {
        lifecycleScope.launch {
            client.getEpisodesInSeason(baseUrl, apiKey, userId, seasonId).onSuccess { eps ->
                if (eps.isEmpty()) return@onSuccess
                episodeList = eps
                binding.episodeRecyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
                val seasonLabel = eps.firstOrNull()?.seasonName ?: seasons.firstOrNull { it.id == seasonId }?.name ?: "本季"
                binding.episodeRowTitle.text = "$seasonLabel · 共${eps.size}集"
                binding.episodeRecyclerView.adapter = EpisodeAdapter(eps, baseUrl, apiKey, client) { ep ->
                    launchPlayer(ep)
                }
                // 从单集进入：定位到那集
                val hint = episodeIdHint
                if (hint != null) {
                    val pos = eps.indexOfFirst { it.id == hint }
                    if (pos >= 0) {
                        binding.episodeRecyclerView.layoutManager?.scrollToPosition(pos)
                        listOf(100L, 300L, 600L).forEach { d ->
                            binding.episodeRecyclerView.postDelayed({
                                try { binding.episodeRecyclerView.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() } catch (_: Exception) {}
                            }, d)
                        }
                        episodeIdHint = null
                    }
                }
            }
        }
    }

    // ===== 操作按钮 =====
    private fun addPlayButton(detail: EmbyItem) {
        val btn = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_btn_gold)
            isFocusable = true
            isClickable = true
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { launchPlayer(detail) }
            setOnKeyListener { v, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) { v.performClick(); true } else false
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                .apply { marginEnd = dp(8) }
            addView(TextView(requireContext()).apply {
                text = "立即播放"
                setTextColor(resources.getColor(R.color.bg_primary, null))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            nextFocusUpId = R.id.name_box
        }
        binding.actionBtnContainer.addView(btn)
    }

    private fun addActionButton(label: String, bg: Int, icon: Int, onClick: () -> Unit) {
        val btn = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(bg)
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnKeyListener { v, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) { v.performClick(); true } else false
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                .apply { marginEnd = dp(8) }
            addView(ImageView(requireContext()).apply {
                setImageResource(icon)
                setColorFilter(resources.getColor(R.color.text_primary, null), android.graphics.PorterDuff.Mode.SRC_ATOP)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            })
            if (label.isNotEmpty()) {
                addView(TextView(requireContext()).apply {
                    text = label
                    setTextColor(resources.getColor(R.color.text_primary, null))
                    textSize = 14f
                    setPadding(dp(8), 0, 0, 0)
                })
            }
            nextFocusUpId = R.id.name_box
        }
        binding.actionBtnContainer.addView(btn)
    }

    private fun toggleFavorite(item: EmbyItem) {
        val target = !(item.userData?.isFavorite ?: false)
        lifecycleScope.launch { client.updateFavorite(baseUrl, apiKey, userId, item.id, target) }
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
        if (binding.episodesSection.visibility == View.VISIBLE) binding.episodesSection.requestFocus()
    }

    private fun openDetail(id: String) {
        open(requireActivity().supportFragmentManager, EmbyItem(id = id, name = "", type = "", isFolder = false, isVideo = true))
    }

    private fun openPersonWorks(person: EmbyPerson) {
        try {
            val frag = PersonWorksFragment().apply {
                arguments = Bundle().apply {
                    putString("personId", person.id ?: person.name)
                    putString("personName", person.name)
                    putString("personImageTag", person.primaryImageTag)
                }
            }
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.container, frag)
                .addToBackStack("detail")
                .commitAllowingStateLoss()
        } catch (_: Exception) {}
    }

    private fun launchPlayer(item: EmbyItem) {
        val server = PreferencesHelper(requireContext()).activeEmbyServer() ?: return
        val targetItem = if ((item.type == "Series" || item.type == "Season") && episodeList.isNotEmpty()) {
            episodeList.maxByOrNull { it.userData?.playbackPositionTicks ?: 0 } ?: episodeList.first()
        } else item
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra("source", "${server.baseUrl}/emby/Videos/${targetItem.id}/stream?static=true&api_key=${server.accessToken}")
            putExtra("title", targetItem.name)
            putExtra("seriesName", targetItem.seriesName ?: item.name)
            putExtra("itemId", targetItem.id)
            putExtra("embyBaseUrl", server.baseUrl)
            putExtra("embyApiKey", server.accessToken)
            putExtra("position", (targetItem.userData?.playbackPositionTicks ?: 0) / 10000)
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

    override fun onResume() {
        super.onResume()
        if (item != null && _binding != null && binding.actionBtnContainer.childCount > 0) {
            binding.actionBtnContainer.post { binding.actionBtnContainer.getChildAt(0)?.requestFocus() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
}

/** 分集适配器 */
class EpisodeAdapter(
    private val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private val imageUrls = items.map { item ->
        val tag = item.imageTags?.get("Primary") ?: item.primaryImageTag
        if (tag != null) client.getImageUrl(baseUrl, item.id, tag, apiKey, 320)
        else if (!item.seriesId.isNullOrBlank()) "$baseUrl/emby/Items/${item.seriesId}/Images/Primary?maxHeight=320&quality=60&api_key=$apiKey"
        else null
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode_card, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val scale = FocusStyleHelper.scaleMultiplier(h.itemView.context)
        val hidden = FocusStyleHelper.hidden(h.itemView.context)
        val ep = item.indexNumber?.let { "E%02d".format(it) } ?: ""
        val season = item.parentIndexNumber?.let { "S%02d".format(it) } ?: ""
        h.epLabel.text = "$season$ep"
        h.epTitle.text = item.name
        val rt = item.runTimeTicks?.let { it / 600000000 }
        h.durationBadge.text = rt?.let { "%d:%02d".format(it / 60, it % 60) } ?: ""
        val url = imageUrls[pos]
        if (url != null) EmbyImageLoader.load(h.image, url)
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) { v.performClick(); true } else false
        }
        h.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val card = h.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(200).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, h.itemView.context)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.posterImage)
        val epLabel: TextView = v.findViewById(R.id.epLabel)
        val epTitle: TextView = v.findViewById(R.id.epTitle)
        val durationBadge: TextView = v.findViewById(R.id.durationBadge)
        val imageBox: View? = v.findViewById(R.id.imageBox)
    }
}

/** 分季胶囊适配器 */
class SeasonAdapter(
    private val items: List<EmbyItem>,
    private val onClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<SeasonAdapter.VH>() {

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_season_tab, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.label.text = item.name ?: "第${pos + 1}季"
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) { v.performClick(); true } else false
        }
        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_sidebar_focus else R.drawable.bg_tag_idle)
            h.label.setTextColor(if (hasFocus) v.context.getColor(R.color.bg_primary) else v.context.getColor(R.color.text_secondary))
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.seasonLabel)
    }
}

/** 主演圆头像适配器 */
class CastAdapter(
    private val people: List<EmbyPerson>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyPerson) -> Unit
) : RecyclerView.Adapter<CastAdapter.VH>() {

    private val urls = people.map { p ->
        p.imageUrl ?: if (p.primaryImageTag != null && p.id != null) client.getImageUrl(baseUrl, p.id, p.primaryImageTag, apiKey, 120) else null
    }

    override fun getItemCount() = people.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_cast_card, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = people[pos]
        h.name.text = p.name
        val url = urls[pos]
        if (url != null) EmbyImageLoader.load(h.avatar, url)
        h.itemView.setOnClickListener { onClick(p) }
        h.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) { v.performClick(); true } else false
        }
        h.itemView.setOnFocusChangeListener { v, hasFocus ->
            val card = h.avatarBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                card.setStrokeColor(
                    android.content.res.ColorStateList.valueOf(if (hasFocus) v.context.getColor(R.color.gold_primary) else 0x00F5C542.toInt())
                )
                if (!FocusStyleHelper.hidden(v.context)) {
                    card.animate().scaleX(if (hasFocus) 1.06f else 1f).scaleY(if (hasFocus) 1.06f else 1f)
                        .setDuration(200).start()
                }
            }
            h.name.setTextColor(if (hasFocus) v.context.getColor(R.color.gold_primary) else v.context.getColor(R.color.text_secondary))
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.avatarImage)
        val name: TextView = v.findViewById(R.id.actorName)
        val avatarBox: View? = v.findViewById(R.id.avatarBox)
    }
}

/** 相关推荐横版适配器 */
class RecommendAdapter(
    private val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<RecommendAdapter.VH>() {

    private val urls = items.map { item ->
        val fanartTag = item.imageTags?.get("Thumb") ?: item.imageTags?.get("Backdrop")
        if (fanartTag != null) client.getImageUrl(baseUrl, item.id, fanartTag, apiKey, 640, "Thumb")
        else client.getBackdropUrl(baseUrl, item.id, null, apiKey, 640)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_detail_landscape, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val scale = FocusStyleHelper.scaleMultiplier(h.itemView.context)
        val hidden = FocusStyleHelper.hidden(h.itemView.context)
        h.title.text = item.name
        h.subtitle.text = item.productionYear?.toString() ?: item.type
        val url = urls[pos]
        if (url != null) EmbyImageLoader.load(h.image, url)
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) { v.performClick(); true } else false
        }
        h.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val card = h.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(200).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, h.itemView.context)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.posterImage)
        val title: TextView = v.findViewById(R.id.titleText)
        val subtitle: TextView = v.findViewById(R.id.subtitleText)
        val imageBox: View? = v.findViewById(R.id.imageBox)
    }
}