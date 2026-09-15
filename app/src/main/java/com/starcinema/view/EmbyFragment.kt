package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem
import com.starcinema.api.EmbyLibrary
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentEmbyBinding
import com.starcinema.model.VideoType
import kotlinx.coroutines.launch

/**
 * 首页 Fragment（参考 AfuseKtV EmbyFragment）
 * 用 VideoType 行模型 + VideoTypeRecyclerAdapterDiff 驱动
 * 数据加载逻辑复用 HomeFragment 的 Emby API 调用
 */
class EmbyFragment : Fragment() {

    private var _binding: FragmentEmbyBinding? = null
    private val binding get() = _binding!!
    private val client = EmbyClient()
    private var baseUrl = ""
    private var apiKey = ""
    private var userId = ""
    private var serverName = ""

    private lateinit var adapter: VideoTypeRecyclerAdapterDiff
    private var lastClickedRow = -1 // 记录点击海报所在行
    private var lastClickedItem = -1 // 记录点击海报在行内的位置
    private lateinit var sidebarAdapter: SidebarAdapter
    private var sidebarExpanded = false // 星光影院：抽屉默认收起

    // ====== 星光影院：Hero 大横幅轮播 ======
    private val heroItems = mutableListOf<EmbyItem>()
    private var heroIndex = 0
    private val heroHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heroAutoPlay = object : Runnable {
        override fun run() {
            if (heroItems.size > 1 && isAdded) {
                showHero((heroIndex + 1) % heroItems.size)
                heroHandler.postDelayed(this, 6000)
            }
        }
    }
    // 详情页返回时恢复聚焦（ViewPager2 的 Fragment 不会收到 onResume，必须监听返回栈）
    private val backStackListener = FragmentManager.OnBackStackChangedListener {
        if (isAdded) {
            val fm = requireActivity().supportFragmentManager
            if (fm.backStackEntryCount == 0) {
                // 返回栈已清空 → 回到首页
                // 详情页改过收藏 → 刷新收藏行（重新加载首页数据）
                if (DetailFragment.favoriteChanged) {
                    DetailFragment.favoriteChanged = false
                    loadHomeData()
                } else if (!::adapter.isInitialized || adapter.itemCount == 0) {
                    // 🔴 首次添加服务器后返回：adapter 无数据（首启 server==null 走了设置页，
                    //    从未 loadHomeData）→ 必须主动加载，否则首页黑屏（ViewPager2 Fragment
                    //    不会收 onResume，只能靠这里兜底）
                    // 先重读 prefs：首启时 baseUrl/apiKey 未设置，必须从新保存的服务器补上
                    val p = PreferencesHelper(requireContext())
                    val sv = p.activeEmbyServer()
                    if (sv != null) {
                        baseUrl = sv.baseUrl; apiKey = sv.accessToken
                        userId = sv.userId; client.serverType = sv.serverType
                        // 首启 adapter 未初始化 → 先建列表再加载（否则 buildRows 无适配器 → 空页）
                        if (!::adapter.isInitialized) setupHomeList()
                        loadHomeData()
                    }
                } else if (lastClickedRow >= 0) {
                    // 精确恢复被点击的海报聚焦
                    binding.mediaSourceList.post { restoreFocusToItem() }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEmbyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 🔴 返回栈监听器必须先注册：首次启动 server==null 会走设置页后返回（popBackStack），
        //    此时 ViewPager2 的 Fragment 不触发 onResume，必须靠这个监听器兜底加载首页数据，
        //    否则添加服务器返回后首页黑屏。放 server 判断之前确保首启也生效。
        requireActivity().supportFragmentManager.addOnBackStackChangedListener(backStackListener)

        val prefs = PreferencesHelper(requireContext())
        val server = prefs.activeEmbyServer()
        if (server == null) {
            // 无服务器：引导用户到服务器列表添加（隐私信息只存本地 SharedPreferences，绝不入源码）
            view.post {
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_container, com.starcinema.view.ServerListFragment())
                    .addToBackStack("home")
                    .commitAllowingStateLoss()
            }
            return
        }
        baseUrl = server.baseUrl
        apiKey = server.accessToken
        userId = server.userId
        client.serverType = server.serverType

        setupHomeList()
        wireHeroControls()
        loadHomeData()
    }

    /** 初始化首页列表（layoutManager/adapter/焦点链），首启或添加服务器后返回时调用 */
    private fun setupHomeList() {
        binding.mediaSourceList.layoutManager = LinearLayoutManager(requireContext())
        binding.mediaSourceList.setItemViewCacheSize(200)

        adapter = VideoTypeRecyclerAdapterDiff()
        binding.mediaSourceList.adapter = adapter

        // 初始化左侧媒体库栏
        setupSidebar()

        // 🔴 焦点链由 buildRows() 统一管理（依赖 heroItems 是否非空），不在此处重复设置
        binding.root.setOnKeyListener(null)
        binding.mediaSourceList.setOnKeyListener(null)
    }

    /** 星光影院：左侧媒体库栏（可滚动，可收缩） */
    private fun setupSidebar() {
        binding.libraryList.layoutManager = LinearLayoutManager(requireContext())
        sidebarAdapter = SidebarAdapter { item ->
            when (item) {
                is SidebarItem.Entry -> when (item.key) {
                    "home" -> { /* 已在首页 */ }
                    "search" -> {
                        requireActivity().supportFragmentManager.beginTransaction()
                            .replace(R.id.nav_host_container, SearchFragment())
                            .addToBackStack("home")
                            .commitAllowingStateLoss()
                    }
                    "settings" -> {
                        requireActivity().supportFragmentManager.beginTransaction()
                            .replace(R.id.nav_host_container, SettingsFragment())
                            .addToBackStack("home")
                            .commitAllowingStateLoss()
                    }
                }
                is SidebarItem.Lib -> openLibrary(item.lib)
            }
        }
        binding.libraryList.adapter = sidebarAdapter
        binding.libraryList.clipToPadding = false

        // 星光影院：滚动箭头（列表可滚动时显示对应箭头）
        binding.libraryList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val llm = rv.layoutManager as? LinearLayoutManager ?: return
                binding.sidebarUpArrow.visibility =
                    if (llm.findFirstVisibleItemPosition() > 0) View.VISIBLE else View.GONE
                binding.sidebarDownArrow.visibility =
                    if (llm.findLastVisibleItemPosition() < llm.itemCount - 1) View.VISIBLE else View.GONE
            }
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                onScrolled(rv, 0, 0)
            }
        })

        // 星光影院：抽屉交互（遮罩点击收起 / 抽屉内右键或返回收起；左键在最左边缘无动作）
        binding.drawerScrim.setOnClickListener { closeDrawer() }
        binding.librarySidebar.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == android.view.KeyEvent.KEYCODE_BACK)
            ) {
                closeDrawer()
                true
            } else false
        }
    }

    /** 星光影院：打开导航抽屉（推挤式：抽屉滑出 + 首页内容缩窄让位 + 遮罩） */
    private fun openDrawer() {
        if (sidebarExpanded) return
        sidebarExpanded = true
        binding.drawerScrim.visibility = View.VISIBLE
        binding.drawerScrim.alpha = 0f
        binding.drawerScrim.animate().alpha(1f).setDuration(250).start()
        binding.librarySidebar.animate().translationX(0f).setDuration(250).start()
        // 星光影院：内容区缩窄让位（marginStart 动画，animateLayoutChanges 自动过渡）
        val lp = binding.homeContent.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.marginStart = dp(200)
        binding.homeContent.layoutParams = lp
        // 抽屉打开时隐藏顶部栏（避免 Logo 与抽屉重叠）
        activity?.findViewById<View>(R.id.topNavBar)?.visibility = View.GONE
        // 🔴 焦点必须落到具体 item 而非 RecyclerView 本体，否则 DPAD_CENTER 不被消费 → 点击无反应。
        //    循环重试：adapter 数据可能晚于抽屉动画填充（fillSidebar 在 loadHomeData 后），
        //    一旦 item0 可用立即聚焦，最多重试 1.2s
        binding.libraryList.postDelayed({
            if (!isAdded || _binding == null) return@postDelayed
            tryFocusSidebarItem(0, 12)
        }, 160)
    }

    private fun tryFocusSidebarItem(position: Int, attemptsLeft: Int) {
        if (!isAdded || _binding == null || attemptsLeft <= 0 || sidebarExpanded.not()) return
        val vh = binding.libraryList.findViewHolderForAdapterPosition(position)
        if (vh != null) {
            vh.itemView.requestFocus()
        } else {
            binding.libraryList.postDelayed({ tryFocusSidebarItem(position, attemptsLeft - 1) }, 100)
        }
    }

    /** 星光影院：全局左键兜底（MainActivity dispatchKeyEvent 调用），返回 true=已开抽屉。
     * 只在焦点位于"最左边"时开抽屉（行标题/第一张海报/videoList 本体），
     * 其余位置左键留给 DpadRecyclerView 在行内移动。force=true 用于 ☰ 按钮。 */
    fun onGlobalLeftKey(force: Boolean = false): Boolean {
        if (sidebarExpanded || isHeroBannerFocused()) return false
        if (!force && !isFocusAtLeftEdge()) return false
        openDrawer()
        return true
    }

    /** 焦点是否在内容区最左边（仅当焦点是"海报 item 且是该行可视最左一张"时返回 true） */
    private fun isFocusAtLeftEdge(): Boolean {
        val f = requireActivity().currentFocus ?: return false
        var videoList: com.rubensousa.dpadrecyclerview.DpadRecyclerView? = null
        var itemView: android.view.View? = null
        var cur: android.view.View? = f
        while (cur != null && cur.parent !== binding.mediaSourceList) {
            if (cur is com.rubensousa.dpadrecyclerview.DpadRecyclerView) videoList = cur
            if (videoList != null && itemView == null && cur.parent === videoList) itemView = cur
            // 🔴 行标题/更多 按左一律不开抽屉（用户要求：只有海报最左才弹）
            if (cur.id == R.id.typeText || cur.id == R.id.moreText) return false
            cur = cur.parent as? android.view.View
        }
        if (videoList == null || itemView == null) return false  // 非行内海报 → 不开抽屉
        // 🔴 判断"该海报是可视区域最左一张"：其 left ≤ paddingStart（左边无其他可见海报）
        return itemView.left <= videoList.paddingStart + 1  // +1 容许亚像素
    }

    /** 抽屉是否打开（MainActivity 返回键判断） */
    fun isDrawerOpen(): Boolean = sidebarExpanded

    /** 关闭抽屉（MainActivity 返回键调用） */
    fun closeDrawerFromActivity() {
        closeDrawer()
    }

    /** 星光影院：收起导航抽屉（滑回 + 内容回位 + 遮罩淡出） */
    private fun closeDrawer() {
        if (!sidebarExpanded) return
        sidebarExpanded = false
        binding.drawerScrim.animate().alpha(0f).setDuration(200).withEndAction {
            binding.drawerScrim.visibility = View.GONE
        }.start()
        binding.librarySidebar.animate().translationX(-dp(200).toFloat()).setDuration(250).start()
        val lp = binding.homeContent.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.marginStart = 0
        binding.homeContent.layoutParams = lp
        // 抽屉收起后恢复顶部栏
        activity?.findViewById<View>(R.id.topNavBar)?.visibility = View.VISIBLE
        binding.mediaSourceList.post { binding.mediaSourceList.requestFocus() }
    }

    /** 填充左侧媒体库栏（固定入口 + Emby 媒体库） */
    private fun fillSidebar(libs: List<EmbyLibrary>) {
        val items = mutableListOf<SidebarItem>()
        items.add(SidebarItem.Entry("home", "首页"))
        items.add(SidebarItem.Entry("search", "搜索"))
        libs.forEach { items.add(SidebarItem.Lib(it)) }
        items.add(SidebarItem.Entry("settings", "设置"))
        sidebarAdapter.submitList(items)
    }

    override fun onResume() {
        super.onResume()
        // 注册焦点样式刷新回调（设置页改完 prefs 后即时生效）
        FocusStyleNotifier.listener = { refreshFocusStyles() }
        val prefs = PreferencesHelper(requireContext())
        val server = prefs.activeEmbyServer()
        if (server != null && (baseUrl.isEmpty() || server.baseUrl != baseUrl)) {
            baseUrl = server.baseUrl
            apiKey = server.accessToken
            userId = server.userId
            client.serverType = server.serverType
            loadHomeData()
        } else if (::adapter.isInitialized && adapter.itemCount > 0 && lastClickedRow >= 0) {
            // 返回首页时精确恢复被点击的海报聚焦
            binding.mediaSourceList.post { restoreFocusToItem() }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::adapter.isInitialized) FocusStyleNotifier.listener = null
    }

    /** 设置页改了焦点样式 → 重建所有海报边框（即时生效） */
    private fun refreshFocusStyles() {
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    /** 恢复到之前点击的海报（按行列坐标，多重延时重试直到行布局完成） */
    private fun restoreFocusToItem() {
        val row = lastClickedRow
        val item = lastClickedItem
        if (row < 0 || item < 0) return
        binding.mediaSourceList.scrollToPosition(row)
        retryFocus(row, item, 0)
    }

    private fun retryFocus(row: Int, item: Int, attempt: Int) {
        if (attempt > 6 || !isAdded) return
        val vh = binding.mediaSourceList.findViewHolderForAdapterPosition(row)
        if (vh == null) {
            // 行还没布局出来，延时重试
            binding.mediaSourceList.postDelayed({ retryFocus(row, item, attempt + 1) }, 200)
            return
        }
        val videoList = vh.itemView.findViewById<com.rubensousa.dpadrecyclerview.DpadRecyclerView>(com.starcinema.R.id.videoList)
        if (videoList == null) return
        // DpadRecyclerView 自带选中 API：滚动并选中指定位置
        videoList.setSelectedPosition(item)
        videoList.postDelayed({
            videoList.findViewHolderForAdapterPosition(item)?.itemView?.requestFocus()
            // DpadRecyclerView 选中后自动给子项焦点
            videoList.findViewHolderForAdapterPosition(item)?.itemView?.isFocusable = true
        }, 150)
    }

    // ====== 星光影院：Hero 大横幅（独立定时轮播，不受焦点影响） ======
    private fun showHero(index: Int) {
        if (heroItems.isEmpty() || !isAdded || _binding == null) return
        heroIndex = ((index % heroItems.size) + heroItems.size) % heroItems.size
        val item = heroItems[heroIndex]

        binding.bannerArea.visibility = View.VISIBLE
        binding.bannerTitle.text = if (item.type == "Episode" && !item.seriesName.isNullOrBlank()) item.seriesName else item.name
        binding.bannerOverview.text = item.overview ?: ""

        // 星光影院：Hero 独立海报（加载到 heroBackground 圆角卡，交叉淡化）
        // Hero 是宽幅卡 → 必须用横版 Backdrop(16:9)，竖版 Primary 会被 centerCrop 掐头去尾
        // Emby 协议：backdropImageTags 是独立 List<String> 字段（不是 imageTags map），优先级最高
        val bgItemId = if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) item.seriesId else item.id
        val backdropTag = item.backdropImageTags?.firstOrNull()
        val bgUrl = client.getBackdropUrl(baseUrl, bgItemId, backdropTag, apiKey, 1200)
            ?: client.getImageUrl(baseUrl, bgItemId, item.imageTags?.get("Thumb"), apiKey, 1200, "Thumb")
            ?: client.getImageUrl(baseUrl, bgItemId, item.imageTags?.get("Primary"), apiKey, 1200)
        if (bgUrl != null) {
            // 底层：完整图 centerCrop 全宽铺满（兜底,确保超宽 Hero 无黑边）+ 上层覆盖渐变暗化
            binding.heroBackgroundBlur.animate().alpha(0f).setDuration(150).withEndAction {
                EmbyImageLoader.load(binding.heroBackgroundBlur, bgUrl)
                binding.heroBackgroundBlur.animate().alpha(1f).setDuration(300).start()
            }.start()
            // 上层：完整剧照 fitCenter 居中（不切割,16:9 原图完整显示）
            binding.heroBackground.animate().alpha(0f).setDuration(150).withEndAction {
                EmbyImageLoader.load(binding.heroBackground, bgUrl)
                binding.heroBackground.animate().alpha(1f).setDuration(300).start()
            }.start()
        }

        // 轮播指示点（当前=金色长条，其余=灰色圆点）
        binding.bannerDots.removeAllViews()
        for (i in heroItems.indices) {
            val dot = View(requireContext())
            if (i == heroIndex) {
                dot.layoutParams = android.widget.LinearLayout.LayoutParams(
                    dp(22), dp(6)
                ).apply { marginStart = dp(4); marginEnd = dp(4); gravity = android.view.Gravity.CENTER_VERTICAL }
                (dot.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart = dp(4)
                (dot.layoutParams as android.widget.LinearLayout.LayoutParams).marginEnd = dp(4)
                dot.setBackgroundResource(R.drawable.bg_hero_dot_active)
            } else {
                dot.layoutParams = android.widget.LinearLayout.LayoutParams(dp(6), dp(6))
                (dot.layoutParams as android.widget.LinearLayout.LayoutParams).marginStart = dp(4)
                (dot.layoutParams as android.widget.LinearLayout.LayoutParams).marginEnd = dp(4)
                dot.setBackgroundResource(R.drawable.bg_hero_dot_idle)
            }
            binding.bannerDots.addView(dot)
        }
    }

    // ====== 星光影院：Hero 手动控制（v1.3.59：聚焦时左右翻页/OK 进详情，自动轮播暂停恢复） ======
    private fun stopHeroAutoPlay() {
        heroHandler.removeCallbacks(heroAutoPlay)
    }

    private fun startHeroAutoPlay() {
        stopHeroAutoPlay()
        if (heroItems.size > 1 && isAdded) heroHandler.postDelayed(heroAutoPlay, 6000)
    }

    /** Hero 横幅是否持有焦点（MainActivity 左键拦截须跳过，让 Hero 自己翻页） */
    fun isHeroBannerFocused(): Boolean = _binding != null && binding.bannerArea.isFocused

    /** Hero 是否有内容（用于 MainActivity 全局左/右键路由判断） */
    fun hasHeroItems(): Boolean = heroItems.isNotEmpty()

    /** 当前焦点是否在内容行（mediaSourceList）内 */
    fun isFocusInsideContentRow(): Boolean = isFocusInsideRows()

    /** Activity 直接调用 flipHero（用于全局方向键路由），保持焦点在 Hero */
    fun flipHeroFromActivity(delta: Int) {
        flipHero(delta)
        binding.bannerArea.post { if (isAdded && _binding != null) binding.bannerArea.requestFocus() }
    }

    /** 当前焦点是否在内容行（mediaSourceList）内 */
    private fun isFocusInsideRows(): Boolean {
        val f = requireActivity().currentFocus ?: return false
        var cur: android.view.View? = f
        while (cur != null) {
            if (cur === binding.mediaSourceList) return true
            cur = cur.parent as? android.view.View
        }
        return false
    }

    /** 全局上键（MainActivity dispatchKeyEvent 调用）：焦点在第一行且 Hero 有内容 → 聚焦 Hero；返回 true=已处理 */
    fun onGlobalUpKey(): Boolean {
        if (!isAdded || _binding == null || isHeroBannerFocused()) return false
        val f = requireActivity().currentFocus ?: return false
        if (!isFocusInsideRows()) return false
        // 焦点所在行必须是第一行（第二行上键留给 DpadRecyclerView 自己回第一行）
        var rowView: android.view.View? = f
        while (rowView != null && rowView.parent !== binding.mediaSourceList) {
            rowView = rowView.parent as? android.view.View
        }
        if (rowView != null) {
            if (binding.mediaSourceList.getChildAdapterPosition(rowView) != 0) return false
        } else if (f !== binding.mediaSourceList) {
            return false
        }
        // 有 Hero → 聚焦 Hero；无 Hero → 直达顶部导航
        if (heroItems.isNotEmpty()) {
            binding.bannerArea.requestFocus()
        } else {
            activity?.findViewById<View>(R.id.nav_home)?.requestFocus()
        }
        return true
    }

    private fun flipHero(delta: Int) {
        if (heroItems.size <= 1) return
        stopHeroAutoPlay()
        showHero(heroIndex + delta)
        // 保持焦点在 bannerArea（showHero 重载背景图可能被 ImageView 抢走焦点，导致下一次左右键走错分支）
        binding.bannerArea.post { if (isAdded && _binding != null) binding.bannerArea.requestFocus() }
    }

    /** 接入 Hero 遥控器控制：聚焦金描边 + 左右翻页 + OK/播放进详情 */
    private fun wireHeroControls() {
        // 聚焦金色描边（foreground 覆盖在海报上，12dp 圆角对齐 bg_hero_card）
        val focusColor = FocusStyleHelper.focusColor(requireContext())
        val borderWidth = dp(PreferencesHelper(requireContext()).focusBorderWidth.coerceAtLeast(2))
        val radius = dp(12)
        val focused = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0x00000000.toInt())
            setStroke(borderWidth, focusColor)
            cornerRadius = radius.toFloat()
        }
        val normal = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(0x00000000.toInt())
            cornerRadius = radius.toFloat()
        }
        binding.bannerArea.foreground = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }

        // 聚焦/失焦 → 暂停/恢复自动轮播；左右键翻页/OK 进详情
        binding.bannerArea.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) stopHeroAutoPlay() else startHeroAutoPlay()
        }
        binding.bannerArea.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { flipHero(-1); true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { flipHero(1); true }
                // 🔴 Hero 上键显式跳顶部导航（不依赖 nextFocusUpId，防容器拦截）
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    activity?.findViewById<View>(R.id.nav_home)?.requestFocus() ?: false
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    heroItems.getOrNull(heroIndex)?.let { openDetail(it) }
                    true
                }
                else -> false
            }
        }

        // 设计稿：Hero 无独立按钮，整个 Hero 区域点按直接进详情
        binding.bannerArea.setOnClickListener {
            heroItems.getOrNull(heroIndex)?.let { openDetail(it) }
        }
    }

    /** 星光影院：媒体库卡片聚焦 → 背景虚化（保留纯黑底，Hero 卡独立不受影响） */
    private fun updateLibraryBackground(lib: EmbyLibrary, focused: Boolean) {
        if (!focused) return
        // 星光影院 v1.3.57：首页背景保持纯黑，Hero 为独立海报卡，不加载全屏背景图
        // 虚化背景逻辑暂留（分类页 LibraryGridFragment 使用）
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun formatBannerTime(ms: Long): String {
        if (ms < 0) return "00:00"
        val total = ms / 1000
        val h = total / 3600; val m = (total % 3600) / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, total % 60) else "%02d:%02d".format(m, total % 60)
    }

    // ========== 数据加载 ==========

    private fun loadHomeData() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.errorText.visibility = View.GONE

        lifecycleScope.launch {
            client.ping(baseUrl).onSuccess { serverName = it }

            client.getLibraries(baseUrl, apiKey, userId).onSuccess { libs ->
                val resumeItems = mutableListOf<EmbyItem>()
                val latest = mutableListOf<Pair<EmbyLibrary, List<EmbyItem>>>()

                client.getResumeItems(baseUrl, apiKey, userId).onSuccess { resumeItems.addAll(it) }

                libs.forEach { lib ->
                    launch {
                        val typeFilter = when (lib.collectionType) {
                            "movies" -> "Movie"
                            "tvshows" -> "Series"
                            "boxsets" -> "BoxSet"
                            "music" -> "MusicAlbum"
                            else -> null
                        }
                        // 按最新入库内容聚合（剧集新增单集→排前），与 Emby 首页一致；类型过滤交给服务端
                        client.getLatestItems(baseUrl, apiKey, userId, lib.id, 8, typeFilter).onSuccess { items ->
                            synchronized(latest) { latest.add(lib to items) }
                            buildRows(libs, resumeItems, latest)
                        }
                    }
                }

                kotlinx.coroutines.delay(500)
                buildRows(libs, resumeItems, latest)
            }.onFailure { e ->
                binding.loadingIndicator.visibility = View.GONE
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.text = "连接失败：${e.message ?: "未知错误"}"
                // M5 前的设置页跳转已裁剪
            }
        }
    }

    private fun openLibrary(lib: EmbyLibrary) {
        val bundle = Bundle().apply {
            putString("libraryId", lib.id)
            putString("libraryName", lib.name)
            putString("collectionType", lib.collectionType)
        }
        val frag = LibraryGridFragment()
        frag.arguments = bundle
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_container, frag)
            .addToBackStack("home")
            .commitAllowingStateLoss()
    }

    private fun openFavorites() {
        val bundle = Bundle().apply {
            putString("uiType", "favorites")
            putString("libraryName", "我的收藏")
        }
        val frag = LibraryGridFragment()
        frag.arguments = bundle
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_container, frag)
            .addToBackStack("home")
            .commitAllowingStateLoss()
    }

    private fun openDetail(item: EmbyItem) {
        // 记录行列位置（用于返回后恢复聚焦）
        val rows = if (::adapter.isInitialized) adapter.currentList else emptyList()
        for (i in rows.indices) {
            val ha = rows[i].adapter as? HorizontalItemAdapter ?: continue
            val pos = ha.items.indexOfFirst { it.id == item.id }
            if (pos >= 0) { lastClickedRow = i; lastClickedItem = pos; break }
        }
        DetailFragment.open(requireActivity().supportFragmentManager, item, "home")
    }

    private fun buildRows(libs: List<EmbyLibrary>, resumeItems: List<EmbyItem>, latest: List<Pair<EmbyLibrary, List<EmbyItem>>>) {
        binding.loadingIndicator.visibility = View.GONE
        binding.errorText.visibility = View.GONE

        // 星光影院：填充左侧媒体库栏
        fillSidebar(libs)

        // 星光影院：Hero 轮播数据（最新内容优先 + 播放记录补位，最多 8 张，随机顺序）
        heroItems.clear()
        latest.forEach { (_, items) -> if (items.isNotEmpty()) heroItems.add(items.first()) }
        resumeItems.take(4).forEach { if (heroItems.size < 8 && heroItems.none { h -> h.id == it.id }) heroItems.add(it) }
        heroItems.shuffle()
        // 🔴 焦点链无条件设置（heroItems 空时内容行直达顶部导航，避免上键断链无法聚焦）
        if (heroItems.isNotEmpty()) {
            heroHandler.removeCallbacks(heroAutoPlay)
            showHero(0)
            startHeroAutoPlay()
            // Hero 可聚焦：行内上键 → Hero → 顶部导航；Hero 下键 → 内容行
            binding.mediaSourceList.nextFocusUpId = R.id.bannerArea
            binding.bannerArea.nextFocusUpId = R.id.nav_home
            binding.bannerArea.nextFocusDownId = R.id.mediaSourceList
            // 顶部导航下键 → Hero
            activity?.findViewById<View>(R.id.nav_menu_btn)?.nextFocusDownId = R.id.bannerArea
            activity?.findViewById<View>(R.id.nav_home)?.nextFocusDownId = R.id.bannerArea
            activity?.findViewById<View>(R.id.nav_search)?.nextFocusDownId = R.id.bannerArea
            activity?.findViewById<View>(R.id.nav_settings)?.nextFocusDownId = R.id.bannerArea
        } else {
            // 无 Hero：内容行上键直达顶部导航，导航下键回内容行
            binding.mediaSourceList.nextFocusUpId = R.id.nav_home
            activity?.findViewById<View>(R.id.nav_home)?.nextFocusDownId = R.id.mediaSourceList
            activity?.findViewById<View>(R.id.nav_search)?.nextFocusDownId = R.id.mediaSourceList
            activity?.findViewById<View>(R.id.nav_settings)?.nextFocusDownId = R.id.mediaSourceList
        }
        // 首页加载完成后聚焦第一个可聚焦项

        val rows = mutableListOf<VideoType>()

        // 定稿图：热门推荐第一行（跨库聚合）
        val hot = latest.flatMap { it.second }.distinctBy { it.id }.take(24)
        if (hot.isNotEmpty()) {
            val hotAdapter = HorizontalItemAdapter(
                hot, baseUrl, apiKey, client,
                onClick = { openDetail(it) },
                landscape = false
            )
            rows.add(VideoType("热门推荐", hotAdapter, see = true, onTitleClick = { openLibrary(EmbyLibrary("", "热门推荐", "")) }))
        }

        // 继续观看行（第二行）
        if (resumeItems.isNotEmpty()) {
            val resumeAdapter = HorizontalItemAdapter(
                resumeItems, baseUrl, apiKey, client,
                onClick = { openDetail(it) },
                landscape = true
            )
            rows.add(VideoType("继续观看", resumeAdapter, see = true))
        }

        adapter.submitList(rows)
        // 首页加载完成后聚焦第一个可聚焦项
        binding.mediaSourceList.post {
            binding.mediaSourceList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        heroHandler.removeCallbacks(heroAutoPlay)
        requireActivity().supportFragmentManager.removeOnBackStackChangedListener(backStackListener)
        _binding = null
    }
}