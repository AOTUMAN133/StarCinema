package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.starcinema.api.EmbyClient
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentPersonWorksBinding
import kotlinx.coroutines.launch

/**
 * 演员作品页（设计文档 v1.0 界面06）：
 * 金框圆头像 + 代表作/生日（真实数据）+ 作品年表横向按年份倒序 + 默认焦点第一张
 */
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
        baseUrl = server.baseUrl; apiKey = server.accessToken; userId = server.userId
        client.serverType = server.serverType

        binding.personName.text = personName.ifBlank { "演员" }
        val headerUrl = client.getImageUrl(baseUrl, personId, personTag, apiKey, 160)
        if (headerUrl != null) EmbyImageLoader.load(binding.headerImage, headerUrl)
        val bgUrl = client.getBackdropUrl(baseUrl, personId, null, apiKey, 800)
        if (bgUrl != null) EmbyImageLoader.load(binding.background, bgUrl)

        binding.videoList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.moreWorksBtn.setOnClickListener {
            binding.videoList.smoothScrollToPosition(binding.videoList.adapter?.itemCount ?: 0)
        }

        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            client.getPersonWorks(baseUrl, apiKey, userId, personId).onSuccess { works ->
                binding.loadingIndicator.visibility = View.GONE
                if (works.isEmpty()) return@onSuccess
                val sorted = works.sortedByDescending { it.productionYear ?: 0 }
                binding.videoList.adapter = HorizontalItemAdapter(
                    sorted, baseUrl, apiKey, client,
                    onClick = { item -> DetailFragment.open(requireActivity().supportFragmentManager, item) }
                )
                // 默认焦点第一张（最新作品）
                binding.videoList.post { retryFocusFirstWork(0) }
                // 代表作 = 年份最新作品（真实数据）
                val latest = sorted.firstOrNull()
                if (latest != null) {
                    val meta = StringBuilder("代表作：${latest.name}")
                    loadPersonMeta(personId, meta)
                }
            }.onFailure {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun retryFocusFirstWork(attempt: Int) {
        if (!isAdded || _binding == null) return
        val vh = binding.videoList.findViewHolderForAdapterPosition(0)
        if (vh != null) { vh.itemView.requestFocus(); return }
        if (attempt >= 8) return
        binding.videoList.postDelayed({ retryFocusFirstWork(attempt + 1) }, 250)
    }

    private fun loadPersonMeta(personId: String, meta: StringBuilder) {
        lifecycleScope.launch {
            client.getPersonDetail(baseUrl, apiKey, userId, personId).onSuccess { detail ->
                if (!isAdded || _binding == null) return@onSuccess
                val born = (detail["PremiereDate"] as? String)?.take(10)
                if (born != null && born.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    val year = born.take(4)
                    val month = born.substring(5, 7).toIntOrNull()
                    val day = born.substring(8, 10).toIntOrNull()
                    meta.append("\n生日：${year} 年 ${month ?: "?"} 月 ${day ?: "?"} 日")
                }
                if (meta.isNotBlank()) binding.personMeta.text = meta.toString()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}