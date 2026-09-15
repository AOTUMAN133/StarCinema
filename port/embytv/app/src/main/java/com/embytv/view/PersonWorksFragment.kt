package com.embytv.view

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
import com.embytv.R
import com.embytv.api.EmbyClient
import com.embytv.api.EmbyItem
import com.embytv.app.PreferencesHelper
import com.embytv.databinding.FragmentPersonWorksBinding
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
                binding.videoList.adapter = HorizontalItemAdapter(
                    works, baseUrl, apiKey, client,
                    onClick = { item ->
                        DetailFragment.open(requireActivity().supportFragmentManager, item)
                    },
                    landscape = false
                )
            }.onFailure {
                binding.loadingIndicator.visibility = View.GONE
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