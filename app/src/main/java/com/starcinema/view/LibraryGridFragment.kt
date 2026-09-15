package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.rubensousa.dpadrecyclerview.layoutmanager.PivotLayoutManager
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentLibraryGridBinding
import kotlinx.coroutines.launch

class LibraryGridFragment : Fragment() {
    private var _binding: FragmentLibraryGridBinding? = null
    private val binding get() = _binding!!
    private val client = EmbyClient()
    private var baseUrl = ""
    private var apiKey = ""
    private var userId = ""
    private var libraryId = ""
    private var libraryName = ""
    private var collectionType: String? = null
    private var uiType = "library" // library=资源库, favorites=我的收藏

    // 排序选项
    private val sortOptions = listOf(
        "DateCreated" to "添加日期",
        "Name" to "名称",
        "PremiereDate" to "首播日期",
        "ProductionYear" to "年份",
        "CommunityRating" to "评分",
        "Runtime" to "时长",
        "PlayCount" to "播放次数",
        "DatePlayed" to "最近播放",
        "SortName" to "排序名"
    )
    private var currentSortBy = "DateCreated"
    private var currentSortOrder = "Descending"
    // 星光影院：年份/类型筛选
    private var currentYear: String? = null
    private var currentGenre: String? = null
    /** 当前选中的筛选标签 id（adapter 建立后同步给网格卡上键直达） */
    private var activeFilterId = R.id.filterAll
    /** 标签选中态表（id → 是否选中），配合焦点描边样式刷新 */
    private val filterActive = HashMap<Int, Boolean>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryGridBinding.inflate(inflater, container, false)
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
        uiType = arguments?.getString("uiType") ?: "library"
        libraryId = arguments?.getString("libraryId") ?: ""
        libraryName = arguments?.getString("libraryName") ?: ""
        collectionType = arguments?.getString("collectionType")

        binding.titleText.text = libraryName

        // 设计文档 v1.0：媒体库页左侧常驻 18% 导航栏（Logo + 首页/搜索/设置 + 媒体库列表）
        setupSidebar()

        val props = RecyclerView.LayoutManager.Properties().apply {
            orientation = RecyclerView.VERTICAL
            spanCount = 4 // 设计文档 v1.0：4列×3行 横版网格（12 张/屏）
        }
        binding.gridRecyclerView.layoutManager = PivotLayoutManager(props)

        // 星光影院：筛选标签（全部/最新/高分/年份/类型）→ 切换排序重载
        binding.filterAll.setOnClickListener { setFilter("all") }
        binding.filterLatest.setOnClickListener { setFilter("latest") }
        binding.filterTop.setOnClickListener { setFilter("top") }
        binding.filterYear.setOnClickListener { showYearDialog() }
        binding.filterGenre.setOnClickListener { showGenreDialog() }
        highlightFilter("all")

        // 星光影院：聚焦控制——标签自身拦截方向键（根视图监听收不到事件，须在标签上直接处理）：
        // 全部=左边缘吞左键、类型=右边缘吞右键、标签行=最顶层吞上键、下键→进网格
        fun wireFilter(v: TextView, swallowLeft: Boolean, swallowRight: Boolean) {
            // 焦点视觉：金底+白描边（与选中金底区分，肉眼可见焦点位置）
            v.setOnFocusChangeListener { _, hasFocus -> refreshFilterStyle(v, hasFocus) }
            v.setOnKeyListener { _, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> true                                   // 标签行最顶 → 吞
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { requestGridFocus(); true }          // 下键 → 进网格
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> swallowLeft                           // 全部再左 → 吞
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> swallowRight                         // 类型再右 → 吞
                    else -> false
                }
            }
        }
        wireFilter(binding.filterAll, swallowLeft = true, swallowRight = false)
        wireFilter(binding.filterLatest, swallowLeft = false, swallowRight = false)
        wireFilter(binding.filterTop, swallowLeft = false, swallowRight = false)
        wireFilter(binding.filterYear, swallowLeft = false, swallowRight = false)
        wireFilter(binding.filterGenre, swallowLeft = false, swallowRight = true)

        loadItems()
    }

    /** 设计文档 v1.0：媒体库页左侧常驻导航栏（Logo + 首页/搜索/设置 + 媒体库列表，与首页一致） */
    private fun setupSidebar() {
        val sidebarAdapter = SidebarAdapter { item ->
            when (item) {
                is SidebarItem.Entry -> when (item.key) {
                    "home" -> parentFragmentManager.popBackStack()
                    "search" -> parentFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_container, SearchFragment())
                        .addToBackStack("home")
                        .commitAllowingStateLoss()
                    "settings" -> parentFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_container, SettingsFragment())
                        .addToBackStack("home")
                        .commitAllowingStateLoss()
                }
                is SidebarItem.Lib -> {
                    // 切换媒体库：replace 自身
                    val bundle = Bundle().apply {
                        putString("libraryId", item.lib.id)
                        putString("libraryName", item.lib.name)
                        putString("collectionType", item.lib.collectionType)
                    }
                    val frag = LibraryGridFragment()
                    frag.arguments = bundle
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_container, frag)
                        .commitAllowingStateLoss()
                }
            }
        }
        binding.libraryList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.libraryList.adapter = sidebarAdapter
        // 填充固定入口 + 动态媒体库列表
        val items = mutableListOf<SidebarItem>()
        items.add(SidebarItem.Entry("home", "首页"))
        items.add(SidebarItem.Entry("search", "搜索"))
        items.add(SidebarItem.Lib(com.starcinema.api.EmbyLibrary(libraryId, libraryName, collectionType ?: "movies", collectionType)))
        items.add(SidebarItem.Entry("settings", "设置"))
        sidebarAdapter.submitList(items)
        // 侧栏可聚焦：媒体库列表首项即为当前库（选中态由 Adapter 的 onFocusChange 控制）
    }

    /** 排序对话框：选择排序字段 + 升降序 */
    private fun showSortDialog() {
        val options = sortOptions.map { it.second } + listOf(if (currentSortOrder == "Ascending") "降序" else "升序")
        val arr = options.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("排序方式")
            .setItems(arr) { _, which ->
                if (which == options.size - 1) {
                    // 切换升降序
                    currentSortOrder = if (currentSortOrder == "Ascending") "Descending" else "Ascending"
                    loadItems()
                } else {
                    currentSortBy = sortOptions[which].first
                    loadItems()
                }
            }
            .show()
    }

    /** 根据媒体库类型获取类型过滤（tvshows → Series，movies → Movie，其他 → null） */
    private fun typeFilter(): String? = when (collectionType) {
        "tvshows" -> "Series"
        "movies" -> "Movie"
        "boxsets" -> "BoxSet"
        "music" -> "MusicAlbum"
        else -> null
    }

    private fun loadItems() {
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            // 收藏模式：走 FavoritesOnly 接口
            if (uiType == "favorites") {
                client.getFavoriteItems(baseUrl, apiKey, userId, 200)
                    .onSuccess { page ->
                        binding.loadingIndicator.visibility = View.GONE
                        binding.gridRecyclerView.adapter = GridAdapter(page.items, baseUrl, apiKey, client, { item ->
                            openDetail(item)
                        }, { item -> updateBlurBackground(item) })
                        requestGridFocus()
                    }.onFailure {
                        binding.loadingIndicator.visibility = View.GONE
                    }
            } else {
                client.getItems(baseUrl, apiKey, userId, libraryId, typeFilter(), 200, currentSortBy, currentSortOrder, 0, currentYear, currentGenre)
                    .onSuccess { page ->
                        binding.loadingIndicator.visibility = View.GONE
                        binding.gridRecyclerView.adapter = GridAdapter(page.items, baseUrl, apiKey, client, { item ->
                            openDetail(item)
                        }, { item -> updateBlurBackground(item) })
                        requestGridFocus()
                    }.onFailure {
                        binding.loadingIndicator.visibility = View.GONE
                    }
            }
        }
    }

    // ====== 星光影院：筛选标签 ======
    private fun setFilter(mode: String) {
        currentSortBy = when (mode) {
            "latest" -> "DateCreated"
            "top" -> "CommunityRating"
            else -> "SortName"
        }
        currentSortOrder = if (mode == "latest" || mode == "top") "Descending" else "Ascending"
        highlightFilter(mode)
        loadItems()
    }

    private fun highlightFilter(mode: String) {
        val act = R.drawable.bg_sidebar_focus
        val nor = R.drawable.bg_sidebar_normal
        // 选中=金底黑字（定稿图），未选中=深底浅灰字
        fun apply(v: TextView, active: Boolean) {
            filterActive[v.id] = active
            if (!v.isFocused) {
                v.setBackgroundResource(if (active) act else nor)
                v.setTextColor(if (active) v.context.getColor(R.color.star_bg) else v.context.getColor(R.color.star_text_secondary))
            }
        }
        apply(binding.filterAll, mode == "all")
        apply(binding.filterLatest, mode == "latest")
        apply(binding.filterTop, mode == "top")
        binding.filterYear.text = if (currentYear == null) "年份 ▾" else "年份 $currentYear ▾"
        binding.filterGenre.text = if (currentGenre == null) "类型 ▾" else "$currentGenre ▾"
        apply(binding.filterYear, currentYear != null)
        apply(binding.filterGenre, currentGenre != null)
        // 网格上键直达当前选中的筛选标签
        activeFilterId = when {
            mode == "latest" -> R.id.filterLatest
            mode == "top" -> R.id.filterTop
            currentYear != null -> R.id.filterYear
            currentGenre != null -> R.id.filterGenre
            else -> R.id.filterAll
        }
        (binding.gridRecyclerView.adapter as? GridAdapter)?.nextFilterId = activeFilterId
    }

    private fun isFilterRowFocused(): Boolean =
        binding.filterAll.isFocused || binding.filterLatest.isFocused || binding.filterTop.isFocused ||
            binding.filterYear.isFocused || binding.filterGenre.isFocused

    /** 刷新标签样式：聚焦视觉由 drawable 的 state_focused 自动给白描边（state_focused + state_selected → 金描边） */
    private fun refreshFilterStyle(v: TextView, focused: Boolean) {
        val active = filterActive[v.id] == true
        v.setBackgroundResource(if (active) R.drawable.bg_sidebar_focus else R.drawable.bg_sidebar_normal)
        v.setTextColor(v.context.getColor(if (active) R.color.star_bg else R.color.star_text_secondary))
    }

    // ====== 星光影院：年份/类型筛选弹层 ======
    private fun showYearDialog() {
        val current = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val years = (current downTo current - 30).map { it.toString() }
        val arr = listOf("全部年份") + years
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("选择年份")
            .setItems(arr.toTypedArray()) { _, which ->
                currentYear = if (which == 0) null else years[which - 1]
                highlightFilter("")
                loadItems()
            }
            .show()
    }

    private fun showGenreDialog() {
        val genres = listOf("动作", "科幻", "喜剧", "悬疑", "爱情", "动画", "纪录", "战争", "奇幻", "惊悚")
        val arr = listOf("全部类型") + genres
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("选择类型")
            .setItems(arr.toTypedArray()) { _, which ->
                currentGenre = if (which == 0) null else genres[which - 1]
                highlightFilter("")
                loadItems()
            }
            .show()
    }

    // ====== 星光影院：焦点海报虚化背景（小尺寸加载+拉伸=模糊，焦点移动动态切换） ======
    private fun updateBlurBackground(item: EmbyItem) {
        if (!isAdded || _binding == null) return
        val url = client.getImageUrl(baseUrl, item.id, item.imageTags?.get("Primary") ?: item.primaryImageTag, apiKey, 200)
        if (url.isNullOrBlank()) {
            binding.blurBackground.visibility = View.GONE
            return
        }
        binding.blurBackground.visibility = View.VISIBLE
        binding.blurBackground.animate().alpha(0.4f).setDuration(200).start()
        EmbyImageLoader.loadSmall(binding.blurBackground, url)
    }

    /** 请求网格首项焦点，使方向键可用（带重试：网格未布局时 requestFocus 会失败，焦点会留在首页分支导致方向键全被首页吃掉） */
    private fun requestGridFocus() {
        // 同步网格上键的目标筛选标签（highlightFilter 早于 adapter 建立）
        (binding.gridRecyclerView.adapter as? GridAdapter)?.nextFilterId = activeFilterId
        binding.gridRecyclerView.post { retryGridFocus(0) }
    }

    private fun retryGridFocus(attempt: Int) {
        if (!isAdded || _binding == null) return
        val vh = binding.gridRecyclerView.findViewHolderForAdapterPosition(0)
        if (vh != null && vh.itemView.requestFocus()) return
        if (attempt >= 8) {
            // 兜底：聚焦筛选标签，确保焦点从首页分支切到本页（方向键随后即可工作）
            binding.filterAll.requestFocus()
            return
        }
        binding.gridRecyclerView.postDelayed({ retryGridFocus(attempt + 1) }, 250)
    }

    private fun openDetail(item: EmbyItem) {
        DetailFragment.open(requireActivity().supportFragmentManager, item)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class GridAdapter(
    private val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyItem) -> Unit,
    private val onFocusItem: (EmbyItem) -> Unit = {}
) : RecyclerView.Adapter<GridAdapter.ViewHolder>() {

    /** 网格上键直达的筛选标签 id（随选中筛选更新） */
    var nextFilterId: Int = 0

    private val urls = items.map { client.getImageUrl(baseUrl, it.id, it.imageTags?.get("Primary") ?: it.primaryImageTag, apiKey, 300) }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid_card, parent, false)
        return ViewHolder(v)
    }
    override fun onBindViewHolder(h: ViewHolder, pos: Int) {
        val item = items[pos]
        val scale = FocusStyleHelper.scaleMultiplier(h.itemView.context)
        val hidden = FocusStyleHelper.hidden(h.itemView.context)
        h.title.text = item.name
        // 星光影院：定稿规格——评分纯数字（设计稿无 ★ 前缀），位于片名下方卡内
        val cr = item.communityRating
        if (cr != null && cr > 0) {
            h.rating.visibility = View.VISIBLE
            h.rating.text = "%.1f".format(cr)
        } else {
            h.rating.visibility = View.GONE
        }
        // 定稿：媒体库页无角标（原 unwatchedBadge 逻辑已随定稿删除）
        EmbyImageLoader.load(h.image, urls[pos])
        h.itemView.setOnClickListener { onClick(item) }
        // 网格上键 → 直达当前选中的筛选标签（防止几何搜索逃逸到首页）
        if (nextFilterId != 0) h.itemView.nextFocusUpId = nextFilterId
        h.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            // AfuseKtV 风格：缩放 imageBox（海报卡片整体放大+边框），标题不缩放
            val card = h.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(200).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, h.itemView.context)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
            // 星光影院：焦点海报虚化背景
            if (hasFocus) onFocusItem(item)
        }
    }
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.posterImage)
        val title: TextView = v.findViewById(R.id.titleText)
        val rating: TextView = v.findViewById(R.id.ratingText)
        val imageBox: View? = v.findViewById(R.id.imageBox)
    }
}