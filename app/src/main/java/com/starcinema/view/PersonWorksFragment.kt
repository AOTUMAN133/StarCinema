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
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentPersonWorksBinding
import kotlinx.coroutines.launch

/** 演员作品页（仿 AfuseKtV activity_actor_query_view） */
class PersonWorksFragment : Fragment() {

    private var _binding: FragmentPersonWorksBinding? = null
    private val binding get() = _binding!!
    private val client = EmbyClient()
    private var baseUrl = ""
    private var apiKey = ""
    private var userId = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPersonWorksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val personId = arguments?.getString("personId") ?: return
        val personName = arguments?.getString("personName") ?: ""
        val personTag = arguments?.getString("personImageTag")

        val server = PreferencesHelper(requireContext()).activeEmbyServer() ?: return
        baseUrl = server.baseUrl
        apiKey = server.accessToken
        userId = server.userId
        client.serverType = server.serverType

        binding.personName.text = personName.ifBlank { "演员" }

        // 头像
        val headerUrl = client.getImageUrl(baseUrl, personId, personTag, apiKey, 160)
        if (headerUrl != null) {
            EmbyImageLoader.load(binding.headerImage, headerUrl)
        }

        // 背景：演员作品首部的 Backdrop
        val bgUrl = client.getBackdropUrl(baseUrl, personId, null, apiKey, 800)
        if (bgUrl != null) EmbyImageLoader.load(binding.background, bgUrl)

        binding.loadingIndicator.visibility = View.VISIBLE
        // 星光影院：作品年表=横向 carousel（设计稿规格）
        binding.videoList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)

        // 星光影院：更多作品 → 滚动到列表末尾
        binding.moreWorksBtn.setOnClickListener {
            binding.videoList.smoothScrollToPosition(binding.videoList.adapter?.itemCount ?: 0)
        }

        lifecycleScope.launch {
            client.getPersonWorks(baseUrl, apiKey, userId, personId).onSuccess { works ->
                binding.loadingIndicator.visibility = View.GONE
                if (works.isEmpty()) return@onSuccess
                // 设计文档 v1.0：作品年表按年份倒序（2023→2015）
                val sorted = works.sortedByDescending { it.productionYear ?: 0 }
                binding.videoList.adapter = HorizontalItemAdapter(
                    sorted, baseUrl, apiKey, client,
                    onClick = { item ->
                        DetailFragment.open(requireActivity().supportFragmentManager, item)
                    },
                    landscape = false
                )
                // 设计文档 v1.0：默认焦点第一张（最新作品）
                binding.videoList.post { retryFocusFirstWork(0) }
                // 设计文档 v1.0：代表作 = 年份最新作品（真实数据，不造假）
                val latest = sorted.firstOrNull()
                if (latest != null) {
                    val meta = StringBuilder()
                    meta.append("代表作：${latest.name}")
                    loadPersonMeta(personId, meta)
                }
            }.onFailure {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    /** 请求作品行第一张聚焦（带重试：RecyclerView 布局前 ViewHolder 不存在） */
    private fun retryFocusFirstWork(attempt: Int) {
        if (!isAdded || _binding == null) return
        val vh = binding.videoList.findViewHolderForAdapterPosition(0)
        if (vh != null) { vh.itemView.requestFocus(); return }
        if (attempt >= 8) return
        binding.videoList.postDelayed({ retryFocusFirstWork(attempt + 1) }, 250)
    }

    /** 加载演员详情补全生日（Emby Person PremiereDate=出生日期） */
    private fun loadPersonMeta(personId: String, meta: StringBuilder) {
        lifecycleScope.launch {
            client.getPersonDetail(baseUrl, apiKey, userId, personId).onSuccess { detail ->
                if (!isAdded || _binding == null) return@onSuccess
                val born = (detail["PremiereDate"] as? String)?.take(10)
                if (born != null && born.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    val year = born.take(4)
                    val month = born.substring(5, 7).toIntOrNull()
                    val day = born.substring(8, 10).toIntOrNull()
                    if (month != null && day != null) {
                        meta.append("\n生日：${year} 年 ${month} 月 ${day} 日")
                    } else {
                        meta.append("\n生日：$year")
                    }
                }
                if (meta.isNotBlank()) binding.personMeta.text = meta.toString()
            }
        }
    }

    private fun openDetail(id: String) {
        DetailFragment.open(requireActivity().supportFragmentManager, EmbyItem(id = id, name = "", type = "", isFolder = false, isVideo = true))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}