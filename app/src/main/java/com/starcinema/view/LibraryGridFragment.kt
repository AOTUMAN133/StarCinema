package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rubensousa.dpadrecyclerview.layoutmanager.PivotLayoutManager
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentLibraryGridBinding
import kotlinx.coroutines.launch

/**
 * 媒体库页（设计文档 v1.0 界面08）：左侧 18% 导航 + 大标题 + 筛选 5 标签 + 4×3 横版网格
 */
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

    private var currentSortBy = "DateCreated"
    private var currentSortOrder = "Descending"
    private var currentYear: String? = null
    private var currentGenre: String? = null
    private val filterActive = HashMap<Int, Boolean>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val server = PreferencesHelper(requireContext()).activeEmbyServer() ?: return
        baseUrl = server.baseUrl; apiKey = server.accessToken; userId = server.userId
        client.serverType = server.serverType
        libraryId = arguments?.getString("libraryId") ?: ""
        libraryName = arguments?.getString("libraryName") ?: ""
        collectionType = arguments?.getString("collectionType")
        binding.titleText.text = libraryName.ifBlank { "媒体库" }

        setupSidebar()
        binding.gridRecyclerView.layoutManager = PivotLayoutManager(
            RecyclerView.LayoutManager.Properties().apply {
                orientation = RecyclerView.VERTICAL
                spanCount = 4
            }
        )

        binding.filterAll.setOnClickListener { setFilter("all") }
        binding.filterLatest.setOnClickListener { setFilter("latest") }
        binding.filterTop.setOnClickListener { setFilter("top") }
        binding.filterYear.setOnClickListener { showYearDialog() }
        binding.filterGenre.setOnClickListener { showGenreDialog() }
        highlightFilter("all")

        loadItems()
    }

    private fun setupSidebar() {
        binding.libraryList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.libraryList.adapter = SidebarAdapter { item ->
            when (item) {
                is SidebarItem.Entry -> when (item.key) {
                    "home" -> requireActivity().supportFragmentManager.popBackStack()
                    "search" -> (activity as? com.starcinema.MainActivity)?.openFragment(SearchFragment())
                    "settings" -> (activity as? com.starcinema.MainActivity)?.openFragment(SettingsFragment())
                }
                is SidebarItem.Lib -> {
                    val frag = LibraryGridFragment().apply {
                        arguments = Bundle().apply {
                            putString("libraryId", item.lib.id)
                            putString("libraryName", item.lib.name)
                            putString("collectionType", item.lib.collectionType)
                        }
                    }
                    (activity as? com.starcinema.MainActivity)?.openFragment(frag)
                }
            }
        }
        // 完整媒体库列表（与首页一致）
        val items = mutableListOf<SidebarItem>()
        items.add(SidebarItem.Entry("home", getString(R.string.sidebar_home)))
        items.add(SidebarItem.Entry("search", getString(R.string.sidebar_search)))
        items.add(SidebarItem.Lib(com.starcinema.api.EmbyLibrary(libraryId, libraryName, collectionType ?: "")))
        items.add(SidebarItem.Entry("settings", getString(R.string.sidebar_settings)))
        (binding.libraryList.adapter as SidebarAdapter).submitList(items)
        lifecycleScope.launch {
            client.getLibraries(baseUrl, apiKey, userId).onSuccess { libs ->
                if (!isAdded || _binding == null) return@onSuccess
                val full = mutableListOf<SidebarItem>()
                full.add(SidebarItem.Entry("home", getString(R.string.sidebar_home)))
                full.add(SidebarItem.Entry("search", getString(R.string.sidebar_search)))
                libs.forEach { full.add(SidebarItem.Lib(it)) }
                full.add(SidebarItem.Entry("settings", getString(R.string.sidebar_settings)))
                (binding.libraryList.adapter as SidebarAdapter).submitList(full)
            }
        }
    }

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
        fun apply(v: TextView, active: Boolean) {
            filterActive[v.id] = active
            v.setBackgroundResource(if (active) R.drawable.bg_sidebar_focus else R.drawable.bg_tag_idle)
            v.setTextColor(if (active) v.context.getColor(R.color.bg_primary) else v.context.getColor(R.color.text_secondary))
        }
        apply(binding.filterAll, mode == "all")
        apply(binding.filterLatest, mode == "latest")
        apply(binding.filterTop, mode == "top")
        binding.filterYear.text = if (currentYear == null) "年份 ▾" else "年份 $currentYear ▾"
        binding.filterGenre.text = if (currentGenre == null) "类型 ▾" else "$currentGenre ▾"
        apply(binding.filterYear, currentYear != null)
        apply(binding.filterGenre, currentGenre != null)
    }

    private fun showYearDialog() {
        val current = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val years = (current downTo current - 30).map { it.toString() }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("选择年份")
            .setItems((listOf("全部年份") + years).toTypedArray()) { _, which ->
                currentYear = if (which == 0) null else years[which - 1]
                highlightFilter("")
                loadItems()
            }.show()
    }

    private fun showGenreDialog() {
        val genres = listOf("动作", "科幻", "喜剧", "悬疑", "爱情", "动画", "纪录", "战争", "奇幻", "惊悚")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("选择类型")
            .setItems((listOf("全部类型") + genres).toTypedArray()) { _, which ->
                currentGenre = if (which == 0) null else genres[which - 1]
                highlightFilter("")
                loadItems()
            }.show()
    }

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
            client.getItems(baseUrl, apiKey, userId, libraryId, typeFilter(), 200, currentSortBy, currentSortOrder, 0, currentYear, currentGenre)
                .onSuccess { page ->
                    binding.loadingIndicator.visibility = View.GONE
                    binding.gridRecyclerView.adapter = GridAdapter(page.items, baseUrl, apiKey, client) { item ->
                        DetailFragment.open(requireActivity().supportFragmentManager, item)
                    }
                    requestGridFocus()
                }.onFailure {
                    binding.loadingIndicator.visibility = View.GONE
                }
        }
    }

    private fun requestGridFocus() {
        binding.gridRecyclerView.post { retryGridFocus(0) }
    }

    private fun retryGridFocus(attempt: Int) {
        if (!isAdded || _binding == null) return
        val vh = binding.gridRecyclerView.findViewHolderForAdapterPosition(0)
        if (vh != null && vh.itemView.requestFocus()) return
        if (attempt >= 8) { binding.filterAll.requestFocus(); return }
        binding.gridRecyclerView.postDelayed({ retryGridFocus(attempt + 1) }, 250)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** 4×3 网格适配器 */
class GridAdapter(
    private val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<GridAdapter.ViewHolder>() {

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
        val cr = item.communityRating
        if (cr != null && cr > 0) {
            h.rating.visibility = View.VISIBLE
            h.rating.text = "%.1f".format(cr)
        } else h.rating.visibility = View.GONE
        EmbyImageLoader.load(h.image, urls[pos])
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

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.posterImage)
        val title: TextView = v.findViewById(R.id.titleText)
        val rating: TextView = v.findViewById(R.id.ratingText)
        val imageBox: View? = v.findViewById(R.id.imageBox)
    }
}