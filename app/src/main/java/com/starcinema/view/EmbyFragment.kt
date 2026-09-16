package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem
import com.starcinema.api.EmbyLibrary
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentEmbyBinding
import com.starcinema.model.VideoType
import kotlinx.coroutines.launch

/**
 * 首页（设计文档 v1.0 界面03）：
 * 左侧 18% 导航（Logo + 首页/搜索/设置 + 媒体库列表）、顶部 Tab、Banner 5s 轮播、热门推荐/继续观看行
 */
class EmbyFragment : Fragment() {

    private var _binding: FragmentEmbyBinding? = null
    private val binding get() = _binding!!
    private val client = EmbyClient()
    private var baseUrl = ""
    private var apiKey = ""
    private var userId = ""
    private lateinit var adapter: VideoTypeRecyclerAdapterDiff
    private lateinit var sidebarAdapter: SidebarAdapter
    private var lastClickedRow = -1
    private var lastClickedItem = -1

    // Banner 轮播
    private val heroItems = mutableListOf<EmbyItem>()
    private var heroIndex = 0
    private val heroHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heroAutoPlay = object : Runnable {
        override fun run() {
            if (heroItems.size > 1 && isAdded) {
                showHero((heroIndex + 1) % heroItems.size)
                heroHandler.postDelayed(this, 5000)
            }
        }
    }

    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        if (isAdded) {
            val fm = requireActivity().supportFragmentManager
            if (fm.backStackEntryCount == 0 && !::adapter.isInitialized || (::adapter.isInitialized && adapter.itemCount == 0)) {
                val p = PreferencesHelper(requireContext())
                val sv = p.activeEmbyServer()
                if (sv != null) {
                    baseUrl = sv.baseUrl; apiKey = sv.accessToken; userId = sv.userId
                    client.serverType = sv.serverType
                    if (!::adapter.isInitialized) setupHomeList()
                    loadHomeData()
                }
            } else if (lastClickedRow >= 0 && ::adapter.isInitialized) {
                binding.contentList.post { restoreFocusToItem() }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEmbyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().supportFragmentManager.addOnBackStackChangedListener(backStackListener)
        val server = PreferencesHelper(requireContext()).activeEmbyServer()
        if (server == null) {
            view.post {
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.container, ServerListFragment())
                    .addToBackStack("home")
                    .commitAllowingStateLoss()
            }
            return
        }
        baseUrl = server.baseUrl; apiKey = server.accessToken; userId = server.userId
        client.serverType = server.serverType
        setupHomeList()
        wireHeroControls()
        wireTopNav()
        loadHomeData()
    }

    private fun setupHomeList() {
        binding.contentList.layoutManager = LinearLayoutManager(requireContext())
        adapter = VideoTypeRecyclerAdapterDiff()
        binding.contentList.adapter = adapter
        setupSidebar()
    }

    // ===== 左侧导航栏 =====
    private fun setupSidebar() {
        binding.libraryList.layoutManager = LinearLayoutManager(requireContext())
        sidebarAdapter = SidebarAdapter { item ->
            when (item) {
                is SidebarItem.Entry -> when (item.key) {
                    "home" -> {}
                    "search" -> openPage(SearchFragment())
                    "settings" -> openPage(SettingsFragment())
                }
                is SidebarItem.Lib -> openLibrary(item.lib)
            }
        }
        binding.libraryList.adapter = sidebarAdapter
        binding.libraryList.clipToPadding = false
        // 侧栏内右键/返回 → 回内容区
        binding.librarySidebar.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == android.view.KeyEvent.KEYCODE_BACK)
            ) { focusContent(); true } else false
        }
    }

    private fun fillSidebar(libs: List<EmbyLibrary>) {
        val items = mutableListOf<SidebarItem>()
        items.add(SidebarItem.Entry("home", getString(R.string.sidebar_home)))
        items.add(SidebarItem.Entry("search", getString(R.string.sidebar_search)))
        libs.forEach { items.add(SidebarItem.Lib(it)) }
        items.add(SidebarItem.Entry("settings", getString(R.string.sidebar_settings)))
        sidebarAdapter.selectedKey = "home"
        sidebarAdapter.submitList(items)
    }

    private fun openPage(fragment: Fragment) {
        (activity as? com.starcinema.MainActivity)?.openFragment(fragment)
    }

    private fun openLibrary(lib: EmbyLibrary) {
        val frag = LibraryGridFragment().apply {
            arguments = Bundle().apply {
                putString("libraryId", lib.id)
                putString("libraryName", lib.name)
                putString("collectionType", lib.collectionType)
            }
        }
        openPage(frag)
    }

    private fun openDetail(item: EmbyItem) {
        val rows = if (::adapter.isInitialized) adapter.currentList else emptyList()
        for (i in rows.indices) {
            val ha = rows[i].adapter as? HorizontalItemAdapter ?: continue
            val pos = ha.items.indexOfFirst { it.id == item.id }
            if (pos >= 0) { lastClickedRow = i; lastClickedItem = pos; break }
        }
        DetailFragment.open(requireActivity().supportFragmentManager, item)
    }

    // ===== 顶部 Tab 栏 =====
    private fun wireTopNav() {
        binding.navHome.setOnClickListener { binding.navHome.requestFocus() }
        binding.navSearch.setOnClickListener { openPage(SearchFragment()) }
        binding.navSettings.setOnClickListener { openPage(SettingsFragment()) }
        listOf(binding.navHome, binding.navSearch, binding.navSettings).forEach { tv ->
            tv.setOnKeyListener { v, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) { v.performClick(); true } else false
            }
        }
        // 时间
        binding.nowTime.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                if (_binding != null && isAdded) {
                    binding.nowTime.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 30_000)
                }
            }
        }, 30_000)
    }

    // ===== Banner 轮播 =====
    private fun showHero(index: Int) {
        if (heroItems.isEmpty() || !isAdded || _binding == null) return
        heroIndex = ((index % heroItems.size) + heroItems.size) % heroItems.size
        val item = heroItems[heroIndex]
        binding.bannerArea.visibility = View.VISIBLE
        binding.bannerTitle.text = if (item.type == "Episode" && !item.seriesName.isNullOrBlank()) item.seriesName else item.name
        binding.bannerOverview.text = item.overview ?: ""
        // 设计文档 v1.0：Banner 金色标签（类型，真实数据；无则不显示）
        val tag = item.genres?.firstOrNull() ?: typeLabel(item.type)
        if (!tag.isNullOrBlank()) {
            binding.bannerTag.text = tag
            binding.bannerTag.visibility = View.VISIBLE
        } else binding.bannerTag.visibility = View.GONE
        val bgItemId = if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) item.seriesId else item.id
        val backdropTag = item.backdropImageTags?.firstOrNull()
        val bgUrl = client.getBackdropUrl(baseUrl, bgItemId, backdropTag, apiKey, 1200)
            ?: client.getImageUrl(baseUrl, bgItemId, item.imageTags?.get("Thumb"), apiKey, 1200, "Thumb")
            ?: client.getImageUrl(baseUrl, bgItemId, item.imageTags?.get("Primary"), apiKey, 1200)
        if (bgUrl != null) EmbyImageLoader.load(binding.heroBackground, bgUrl)
        // 指示点：当前金长条，其余灰圆点
        binding.bannerDots.removeAllViews()
        for (i in heroItems.indices) {
            val dot = View(requireContext())
            if (i == heroIndex) {
                dot.layoutParams = android.widget.LinearLayout.LayoutParams(dp(22), dp(6)).apply {
                    marginStart = dp(4); marginEnd = dp(4); gravity = android.view.Gravity.CENTER_VERTICAL
                }
                dot.setBackgroundResource(R.drawable.bg_hero_dot_active)
            } else {
                dot.layoutParams = android.widget.LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
                dot.setBackgroundResource(R.drawable.bg_hero_dot_idle)
            }
            binding.bannerDots.addView(dot)
        }
    }

    private fun stopHeroAutoPlay() = heroHandler.removeCallbacks(heroAutoPlay)

    private fun startHeroAutoPlay() {
        stopHeroAutoPlay()
        if (heroItems.size > 1 && isAdded) heroHandler.postDelayed(heroAutoPlay, 5000)
    }

    private fun wireHeroControls() {
        // 聚焦金描边
        val borderWidth = dp(PreferencesHelper(requireContext()).focusBorderWidth.coerceAtLeast(2))
        val radius = dp(12)
        val focused = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0x00000000.toInt()); setStroke(borderWidth, 0xFFF5C542.toInt()); cornerRadius = radius.toFloat()
        }
        val normal = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0x00000000.toInt()); cornerRadius = radius.toFloat()
        }
        binding.bannerArea.foreground = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        binding.bannerArea.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) stopHeroAutoPlay() else startHeroAutoPlay()
        }
        binding.bannerArea.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { flipHero(-1); true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { flipHero(1); true }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> { binding.navHome.requestFocus(); true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    heroItems.getOrNull(heroIndex)?.let { openDetail(it) }; true
                }
                else -> false
            }
        }
        binding.bannerArea.setOnClickListener {
            heroItems.getOrNull(heroIndex)?.let { openDetail(it) }
        }
    }

    private fun flipHero(delta: Int) {
        if (heroItems.size <= 1) return
        stopHeroAutoPlay()
        showHero(heroIndex + delta)
        binding.bannerArea.post { if (isAdded && _binding != null) binding.bannerArea.requestFocus() }
    }

    // ===== 焦点管理 =====
    fun focusSidebar() {
        if (!isAdded || _binding == null) return
        binding.libraryList.postDelayed({ tryFocusSidebarItem(0, 12) }, 80)
    }

    private fun tryFocusSidebarItem(position: Int, attemptsLeft: Int) {
        if (!isAdded || _binding == null || attemptsLeft <= 0) return
        val vh = binding.libraryList.findViewHolderForAdapterPosition(position)
        if (vh != null) vh.itemView.requestFocus()
        else binding.libraryList.postDelayed({ tryFocusSidebarItem(position, attemptsLeft - 1) }, 100)
    }

    fun focusContent() {
        binding.contentList.post { binding.contentList.requestFocus() }
    }

    fun isHeroBannerFocused(): Boolean = _binding != null && binding.bannerArea.isFocused

    fun isFocusInSidebar(): Boolean {
        if (!isAdded || _binding == null) return false
        val f = requireActivity().currentFocus ?: return false
        var cur: View? = f
        while (cur != null) {
            if (cur === binding.librarySidebar || cur === binding.libraryList) return true
            if (cur.parent === binding.libraryList) return true
            cur = cur.parent as? View
        }
        return false
    }

    /** 内容区最左元素（行标题/最左海报）按左 → 进侧栏 */
    fun onGlobalLeftKey(force: Boolean = false): Boolean {
        if (isHeroBannerFocused()) return false
        if (!force && !isFocusAtLeftEdge()) return false
        focusSidebar(); return true
    }

    private fun isFocusAtLeftEdge(): Boolean {
        val f = requireActivity().currentFocus ?: return false
        var videoList: com.rubensousa.dpadrecyclerview.DpadRecyclerView? = null
        var itemView: View? = null
        var cur: View? = f
        while (cur != null && cur.parent !== binding.contentList) {
            if (cur is com.rubensousa.dpadrecyclerview.DpadRecyclerView) videoList = cur
            if (videoList != null && itemView == null && cur.parent === videoList) itemView = cur
            if (cur.id == R.id.typeText) return true
            if (cur.id == R.id.moreText) return false
            cur = cur.parent as? View
        }
        if (videoList == null || itemView == null) return false
        return itemView.left <= videoList.paddingStart + 1
    }

    fun isFocusInsideContentRow(): Boolean {
        if (!isAdded || _binding == null) return false
        val f = requireActivity().currentFocus ?: return false
        var cur: View? = f
        while (cur != null) {
            if (cur === binding.contentList) return true
            cur = cur.parent as? View
        }
        return false
    }

    /** 第一行上键 → Hero；Hero 上键 → 顶栏（MainActivity 路由调用） */
    fun onGlobalUpKey(): Boolean {
        if (!isAdded || _binding == null || isHeroBannerFocused()) return false
        val f = requireActivity().currentFocus ?: return false
        if (!isFocusInsideContentRow()) return false
        var rowView: View? = f
        while (rowView != null && rowView.parent !== binding.contentList) rowView = rowView.parent as? View
        if (rowView != null) {
            if (binding.contentList.getChildAdapterPosition(rowView) != 0) return false
        } else if (f !== binding.contentList) return false
        if (heroItems.isNotEmpty()) binding.bannerArea.requestFocus()
        else binding.navHome.requestFocus()
        return true
    }

    private fun restoreFocusToItem() {
        val row = lastClickedRow; val item = lastClickedItem
        if (row < 0 || item < 0) return
        binding.contentList.scrollToPosition(row)
        retryFocus(row, item, 0)
    }

    private fun retryFocus(row: Int, item: Int, attempt: Int) {
        if (attempt > 6 || !isAdded) return
        val vh = binding.contentList.findViewHolderForAdapterPosition(row)
        if (vh == null) {
            binding.contentList.postDelayed({ retryFocus(row, item, attempt + 1) }, 200); return
        }
        val videoList = vh.itemView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.videoList) ?: return
        videoList.scrollToPosition(item)
        videoList.postDelayed({
            videoList.findViewHolderForAdapterPosition(item)?.itemView?.requestFocus()
        }, 150)
    }

    // ===== 数据加载 =====
    private fun loadHomeData() {
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            client.getLibraries(baseUrl, apiKey, userId).onSuccess { libs ->
                val resumeItems = mutableListOf<EmbyItem>()
                val latest = mutableListOf<Pair<EmbyLibrary, List<EmbyItem>>>()
                client.getResumeItems(baseUrl, apiKey, userId).onSuccess { resumeItems.addAll(it) }
                libs.forEach { lib ->
                    launch {
                        val typeFilter = when (lib.collectionType) {
                            "movies" -> "Movie"; "tvshows" -> "Series"; "boxsets" -> "BoxSet"; "music" -> "MusicAlbum"
                            else -> null
                        }
                        client.getLatestItems(baseUrl, apiKey, userId, lib.id, 8, typeFilter).onSuccess { items ->
                            synchronized(latest) { latest.add(lib to items) }
                            buildRows(libs, resumeItems, latest)
                        }
                    }
                }
                kotlinx.coroutines.delay(500)
                buildRows(libs, resumeItems, latest)
            }.onFailure {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun buildRows(libs: List<EmbyLibrary>, resumeItems: List<EmbyItem>, latest: List<Pair<EmbyLibrary, List<EmbyItem>>>) {
        binding.loadingIndicator.visibility = View.GONE
        fillSidebar(libs)
        // Hero：各库最新 + 播放记录补位，最多 8
        heroItems.clear()
        latest.forEach { (_, items) -> if (items.isNotEmpty()) heroItems.add(items.first()) }
        resumeItems.take(4).forEach { if (heroItems.size < 8 && heroItems.none { h -> h.id == it.id }) heroItems.add(it) }
        if (heroItems.isNotEmpty()) {
            heroHandler.removeCallbacks(heroAutoPlay)
            showHero(0)
            startHeroAutoPlay()
            binding.contentList.nextFocusUpId = R.id.bannerArea
            binding.bannerArea.nextFocusUpId = R.id.navHome
            binding.bannerArea.nextFocusDownId = R.id.contentList
            binding.navHome.nextFocusDownId = R.id.bannerArea
            binding.navSearch.nextFocusDownId = R.id.bannerArea
            binding.navSettings.nextFocusDownId = R.id.bannerArea
        } else {
            binding.contentList.nextFocusUpId = R.id.navHome
            binding.navHome.nextFocusDownId = R.id.contentList
            binding.navSearch.nextFocusDownId = R.id.contentList
            binding.navSettings.nextFocusDownId = R.id.contentList
        }
        val rows = mutableListOf<VideoType>()
        // 热门推荐：跨库轮转各取 1 张（避免单一库霸屏），共 6 张竖版海报（设计文档：6 张）
        val hot = mutableListOf<EmbyItem>()
        val perLib = latest.map { (_, items) -> items.firstOrNull() }.filterNotNull()
        var idx = 0
        while (hot.size < 6 && perLib.isNotEmpty()) {
            perLib.forEach { item ->
                if (hot.size < 6 && hot.none { it.id == item.id }) {
                    hot.add(item); idx++
                }
            }
            // 全部轮完还不够 6 张：从各库第 2 张起补
            if (idx == perLib.size) break
        }
        if (hot.size < 6) {
            latest.flatMap { it.second }.forEach { item ->
                if (hot.size < 6 && hot.none { it.id == item.id }) hot.add(item)
            }
        }
        if (hot.isNotEmpty()) {
            rows.add(VideoType(
                typeText = getString(R.string.hot_row),
                adapter = HorizontalItemAdapter(hot, baseUrl, apiKey, client, onClick = { openDetail(it) }),
                see = false
            ))
        }
        // 继续观看：横版含进度
        if (resumeItems.isNotEmpty()) {
            rows.add(VideoType(
                typeText = getString(R.string.continue_watch),
                adapter = HorizontalItemAdapter(resumeItems, baseUrl, apiKey, client, onClick = { openDetail(it) }, landscape = true),
                see = false
            ))
        }
        adapter.submitList(rows)
        binding.contentList.post {
            binding.contentList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized && adapter.itemCount > 0 && lastClickedRow >= 0) {
            binding.contentList.post { restoreFocusToItem() }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        heroHandler.removeCallbacks(heroAutoPlay)
        requireActivity().supportFragmentManager.removeOnBackStackChangedListener(backStackListener)
        _binding = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 类型 → 中文标签（Banner 金标，真实数据兜底） */
    private fun typeLabel(type: String?): String? = when (type) {
        "Movie" -> "电影"
        "Series" -> "剧集"
        "Episode" -> "剧集"
        "MusicAlbum" -> "音乐"
        "BoxSet" -> "合集"
        else -> null
    }
}