package com.starcinema.view

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * 搜索页（设计文档 v1.0 界面07）：胶囊搜索框 + 热门 6 标签（聚焦火焰）+ 大家都在看
 */
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val client = EmbyClient()
    private var baseUrl = ""
    private var apiKey = ""
    private var userId = ""
    private var lastQuery = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val server = PreferencesHelper(requireContext()).activeEmbyServer()
        if (server != null) {
            baseUrl = server.baseUrl; apiKey = server.accessToken; userId = server.userId
            client.serverType = server.serverType
        }
        setupSidebar()
        binding.searchList.layoutManager = GridLayoutManager(requireContext(), 4)
        if (baseUrl.isNotEmpty()) loadDefaultRecommend()

        binding.searchBtn.setOnClickListener { doSearch() }
        binding.searchBtn.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) { v.performClick(); true } else false
        }

        // 热门 6 标签：点击搜索；聚焦金色实心 + 火焰图标（设计文档：选中金色实心带火焰）
        val hotWords = listOf(
            binding.hotWord1, binding.hotWord2, binding.hotWord3,
            binding.hotWord4, binding.hotWord5, binding.hotWord6
        )
        var selectedTag: android.widget.TextView? = null
        hotWords.forEach { tv ->
            tv.setOnClickListener {
                val word = tv.text.toString()
                binding.searchEdit.setText(word)
                binding.searchEdit.setSelection(word.length)
                // 选中态常驻：金色实心 + 火焰
                selectedTag?.let { prev ->
                    prev.setBackgroundResource(R.drawable.bg_tag_idle)
                    prev.setTextColor(prev.context.getColor(R.color.text_secondary))
                    prev.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                }
                tv.setBackgroundResource(R.drawable.bg_sidebar_focus)
                tv.setTextColor(tv.context.getColor(R.color.bg_primary))
                tv.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_fire, 0, 0, 0)
                tv.compoundDrawablePadding = 6
                selectedTag = tv
                doSearch()
            }
            tv.setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) { v.performClick(); true } else false
            }
            tv.setOnFocusChangeListener { v, hasFocus ->
                if (v === selectedTag) return@setOnFocusChangeListener // 选中态不随焦点变化
                v.setBackgroundResource(if (hasFocus) R.drawable.bg_sidebar_focus else R.drawable.bg_tag_idle)
                (v as android.widget.TextView).setTextColor(
                    if (hasFocus) v.context.getColor(R.color.bg_primary)
                    else v.context.getColor(R.color.text_secondary)
                )
                v.setCompoundDrawablesWithIntrinsicBounds(if (hasFocus) R.drawable.ic_fire else 0, 0, 0, 0)
                if (hasFocus) v.compoundDrawablePadding = 6
            }
        }

        binding.searchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            ) { doSearch(); true } else false
        }
        binding.searchEdit.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) { doSearch(); true } else false
        }
        binding.searchEdit.requestFocus()
    }

    private fun loadDefaultRecommend() {
        lifecycleScope.launch {
            client.getLibraries(baseUrl, apiKey, userId).onSuccess { libs ->
                val items = mutableListOf<EmbyItem>()
                libs.forEach { lib ->
                    val typeFilter = when (lib.collectionType) {
                        "movies" -> "Movie"; "tvshows" -> "Series"; else -> null
                    }
                    client.getLatestItems(baseUrl, apiKey, userId, lib.id, 4, typeFilter).onSuccess { list ->
                        list.forEach { if (items.none { it.id == it.id }) items.add(it) }
                    }
                }
                kotlinx.coroutines.delay(300)
                // 设计文档 v1.0：大家都在看 4 张竖版海报
                val recommend = items.distinctBy { it.id }.take(4)
                if (recommend.isEmpty() || !isAdded || _binding == null) return@onSuccess
                binding.defaultTitleRow.visibility = View.VISIBLE
                binding.emptyHint.visibility = View.GONE
                binding.searchList.visibility = View.VISIBLE
                binding.searchList.adapter = SearchResultAdapter(recommend, baseUrl, apiKey, client) { openDetail(it) }
            }
        }
    }

    private fun doSearch() {
        val query = binding.searchEdit.text.toString().trim()
        if (query.isEmpty() || baseUrl.isEmpty()) return
        val imm = requireContext().getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.searchEdit.windowToken, 0)
        lastQuery = query
        binding.defaultTitleRow.visibility = View.GONE
        binding.loading.visibility = View.VISIBLE
        binding.emptyHint.visibility = View.GONE
        binding.searchList.visibility = View.GONE
        lifecycleScope.launch {
            client.search(baseUrl, apiKey, userId.ifBlank { null }, query, "Movie,Series")
                .onSuccess { results ->
                    binding.loading.visibility = View.GONE
                    if (results.isEmpty()) {
                        binding.emptyHint.text = "没有找到「$query」相关结果"
                        binding.emptyHint.visibility = View.VISIBLE
                        return@onSuccess
                    }
                    binding.searchList.visibility = View.VISIBLE
                    binding.searchList.adapter = SearchResultAdapter(results, baseUrl, apiKey, client) { openDetail(it) }
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

    private fun setupSidebar() {
        binding.libraryList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        val sidebarAdapter = SidebarAdapter { item ->
            when (item) {
                is SidebarItem.Entry -> when (item.key) {
                    "home" -> requireActivity().supportFragmentManager.popBackStack()
                    "search" -> {}
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
        binding.libraryList.adapter = sidebarAdapter
        sidebarAdapter.selectedKey = "search"
        val items = mutableListOf<SidebarItem>()
        items.add(SidebarItem.Entry("home", getString(R.string.sidebar_home)))
        items.add(SidebarItem.Entry("search", getString(R.string.sidebar_search)))
        if (baseUrl.isNotEmpty()) {
            lifecycleScope.launch {
                client.getLibraries(baseUrl, apiKey, userId).onSuccess { libs ->
                    if (!isAdded || _binding == null) return@onSuccess
                    libs.forEach { items.add(SidebarItem.Lib(it)) }
                    items.add(SidebarItem.Entry("settings", getString(R.string.sidebar_settings)))
                    sidebarAdapter.submitList(items)
                }
            }
        } else {
            items.add(SidebarItem.Entry("settings", getString(R.string.sidebar_settings)))
            sidebarAdapter.submitList(items)
        }
    }

    private fun openDetail(item: EmbyItem) {
        DetailFragment.open(requireActivity().supportFragmentManager, item)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}