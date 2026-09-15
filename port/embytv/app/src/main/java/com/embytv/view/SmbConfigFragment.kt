package com.embytv.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.embytv.R
import com.embytv.api.SmbClient
import com.embytv.app.PreferencesHelper
import com.embytv.app.SmbServerConfig
import com.embytv.databinding.FragmentSmbConfigBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SMB 服务器配置页：测试连接 + 保存 */
class SmbConfigFragment : Fragment() {

    private var _binding: FragmentSmbConfigBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSmbConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesHelper(requireContext())

        val serverJson = arguments?.getString("serverJson")
        if (serverJson != null) {
            val s = com.google.gson.Gson().fromJson(serverJson, SmbServerConfig::class.java)
            binding.serverName.setText(s.name)
            binding.serverHost.setText(s.host)
            binding.username.setText(s.username)
            binding.password.setText(s.password)
            binding.domain.setText(s.domain)
            binding.basePath.setText(s.basePath)
        }

        binding.testBtn.setOnClickListener { testConnection() }
        binding.saveBtn.setOnClickListener { save() }
        view.post { binding.serverHost.requestFocus() }
    }

    private fun buildConfig(): SmbServerConfig? {
        val host = binding.serverHost.text?.toString()?.trim() ?: ""
        val username = binding.username.text?.toString()?.trim() ?: ""
        val password = binding.password.text?.toString() ?: ""
        val domain = binding.domain.text?.toString()?.trim() ?: ""
        val basePath = binding.basePath.text?.toString()?.trim()?.ifBlank { "/" } ?: "/"
        if (host.isBlank()) { showStatus("请输入主机地址", false); return null }
        val name = binding.serverName.text?.toString()?.trim()?.ifBlank { null } ?: host
        return SmbServerConfig(
            id = "",
            name = name,
            host = host,
            port = 445,
            username = username,
            password = password,
            domain = domain,
            basePath = basePath
        )
    }

    private fun testConnection() {
        val cfg = buildConfig() ?: return
        showStatus("正在连接...", true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { SmbClient(cfg).listDir(cfg.basePath) } catch (e: Exception) { Result.failure(e) }
            }
            result.onSuccess { items ->
                showStatus("✅ 连接成功，共 ${items.size} 项", true)
            }.onFailure { e ->
                showStatus("❌ 连接失败: ${e.message ?: "未知错误"}", false)
            }
        }
    }

    private fun save() {
        val cfg = buildConfig() ?: return
        val existingJson = arguments?.getString("serverJson")
        val existingId = if (existingJson != null) {
            com.google.gson.Gson().fromJson(existingJson, SmbServerConfig::class.java).id
        } else ""
        val server = cfg.copy(id = existingId.ifBlank { java.util.UUID.randomUUID().toString() })
        prefs.upsertSmbServer(server)
        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun showStatus(msg: String, isSuccess: Boolean) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = msg
        binding.statusText.setTextColor(
            ContextCompat.getColor(requireContext(), if (isSuccess) R.color.apple_green else R.color.apple_red)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}