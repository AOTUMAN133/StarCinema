package com.starcinema.view

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentSearchBinding
import kotlinx.coroutines.launch

/**
 * 全局搜索页（支持分类筛选）
 * - 遥控器 OK/ENTER 直接搜索
 * - 默认排除单集（只搜电影+剧集）
 * - 跳详情页 try-catch 防闪退
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val client = EmbyClient()
    private var baseUrl = ""
    private var apiKey = ""
    private var userId = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val server = PreferencesHelper(requireContext()).activeEmbyServer()
        if (server != null) {
            baseUrl = server.baseUrl
            apiKey = server.accessToken
            userId = server.userId
            client.serverType = server.serverType
        }

        // 设计文档 v1.0：搜索页左侧常驻 18% 导航栏
        setupSidebar()

        binding.searchList.layoutManager = GridLayoutManager(requireContext(), 4)

        // 星光影院：搜索前显示"大家都在看"（默认推荐）
        if (baseUrl.isNotEmpty()) loadDefaultRecommend()

        // 搜索按钮点击
        binding.searchBtn.setOnClickListener { doSearch() }

        // 星光影院：热门搜索词点击 → 填入搜索框并搜索
        val hotWords = listOf(
            binding.hotWord1, binding.hotWord2, binding.hotWord3,
            binding.hotWord4, binding.hotWord5
        )
        hotWords.forEach { tv ->
            tv.setOnClickListener {
                val word = tv.text.toString()
                binding.searchEdit.setText(word)
                binding.searchEdit.setSelection(word.length)
                doSearch()
            }
            tv.setOnFocusChangeListener { v, hasFocus ->
                v.setBackgroundResource(if (hasFocus) R.drawable.bg_sidebar_focus else R.drawable.bg_season_tab_idle)
                (v as android.widget.TextView).setTextColor(
                    if (hasFocus) v.context.getColor(R.color.star_bg)
                    else v.context.getColor(R.color.star_text_secondary)
                )
            }
        }

        // IME 搜索键（遥控器键盘回车）
        binding.searchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                doSearch()
                true
            } else false
        }

        // 遥控器 D-pad 处理：EditText 获得焦点时 OK/ENTER 直接搜
        binding.searchEdit.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                doSearch()
                true
            } else false
        }

        // Chip 组选择变化 → 重新搜索
        binding.searchChips.setOnCheckedStateChangeListener { _, _ ->
            if (lastQuery.isNotBlank()) doSearch()
        }

        binding.searchEdit.requestFocus()
    }

    private var lastQuery = ""

    /** 星光影院：搜索前显示"大家都在看"（各库最新内容聚合） */
    private fun loadDefaultRecommend() {
        if (baseUrl.isEmpty()) return
        lifecycleScope.launch {
            client.getLibraries(baseUrl, apiKey, userId).onSuccess { libs ->
                val items = mutableListOf<EmbyItem>()
                libs.forEach { lib ->
                    val typeFilter = when (lib.collectionType) {
                        "movies" -> "Movie"
                        "tvshows" -> "Series"
                        else -> null
                    }
                    client.getLatestItems(baseUrl, apiKey, userId, lib.id, 4, typeFilter)
                        .onSuccess { list ->
                            list.forEach { if (items.none { it.id == it.id }) items.add(it) }
                        }
                }
                kotlinx.coroutines.delay(300)
                if (items.isEmpty() || !isAdded || _binding == null) return@onSuccess
                binding.defaultTitleRow.visibility = View.VISIBLE
                binding.emptyHint.visibility = View.GONE
                binding.searchList.visibility = View.VISIBLE
                binding.searchList.adapter = SearchResultAdapter(
                    items, baseUrl, apiKey, client,
                    onClick = { openDetail(it) }
                )
            }
        }
    }

    private fun doSearch() {
        val query = binding.searchEdit.text.toString().trim()
        if (query.isEmpty() || baseUrl.isEmpty()) return
        // 搜索后隐藏键盘
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchEdit.windowToken, 0)
        lastQuery = query
        binding.defaultTitleRow.visibility = View.GONE
        search(query, currentTypeFilter())
    }

    /** 类型过滤：全部=Movie,Series（不含Episode），单集可选 */
    private fun currentTypeFilter(): String = when (binding.searchChips.checkedChipId) {
        R.id.chipMovie -> "Movie"
        R.id.chipSeries -> "Series"
        R.id.chipEpisode -> "Episode"
        else -> "Movie,Series" // 全部默认不含单集
    }

    private fun search(query: String, typeFilter: String) {
        binding.loading.visibility = View.VISIBLE
        binding.emptyHint.visibility = View.GONE
        binding.searchList.visibility = View.GONE

        lifecycleScope.launch {
            client.search(baseUrl, apiKey, userId.ifBlank { null }, query, typeFilter)
                .onSuccess { results ->
                    binding.loading.visibility = View.GONE
                    if (results.isEmpty()) {
                        binding.emptyHint.text = "没有找到「$query」相关结果"
                        binding.emptyHint.visibility = View.VISIBLE
                        return@onSuccess
                    }
                    binding.searchList.visibility = View.VISIBLE
                    binding.searchList.adapter = SearchResultAdapter(
                        results, baseUrl, apiKey, client,
                        onClick = { openDetail(it) }
                    )
                    binding.searchList.post {
                        binding.searchList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }.onFailure { e ->
                    binding.loading.visibility = View.GONE
                    binding.emptyHint.text = "搜索失败：${e.message ?: "未知错误"}"
                    binding.emptyHint.visibility = View.VISIBLE
                }
        }
    }

    /** 设计文档 v1.0：搜索页左侧常驻导航栏（Logo + 首页/搜索/设置 + 媒体库列表，与首页一致） */
    private fun setupSidebar() {
        val sidebarAdapter = SidebarAdapter { item ->
            when (item) {
                is SidebarItem.Entry -> when (item.key) {
                    "home" -> parentFragmentManager.popBackStack()
                    "search" -> { /* 已在搜索页 */ }
                    "settings" -> parentFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_container, SettingsFragment())
                        .addToBackStack("home")
                        .commitAllowingStateLoss()
                }
                is SidebarItem.Lib -> {
                    val bundle = Bundle().apply {
                        putString("libraryId", item.lib.id)
                        putString("libraryName", item.lib.name)
                        putString("collectionType", item.lib.collectionType)
                    }
                    val frag = LibraryGridFragment()
                    frag.arguments = bundle
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.nav_host_container, frag)
                        .addToBackStack("home")
                        .commitAllowingStateLoss()
                }
            }
        }
        binding.libraryList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.libraryList.adapter = sidebarAdapter
        val items = mutableListOf<SidebarItem>()
        items.add(SidebarItem.Entry("home", "首页"))
        items.add(SidebarItem.Entry("search", "搜索"))
        items.add(SidebarItem.Entry("settings", "设置"))
        sidebarAdapter.submitList(items)
    }

    private fun openDetail(item: EmbyItem) {
        DetailFragment.open(requireActivity().supportFragmentManager, item, "search")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}