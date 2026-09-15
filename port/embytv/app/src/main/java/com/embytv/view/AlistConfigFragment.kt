package com.embytv.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.embytv.R
import com.embytv.api.AlistClient
import com.embytv.api.AlistConfig
import com.embytv.app.PreferencesHelper
import com.embytv.databinding.FragmentAlistConfigBinding
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AList 配置编辑页：地址 + 账号 + 密码 + 测试登录 + 保存（仿 ServerEditFragment） */
class AlistConfigFragment : Fragment() {

    private var _binding: FragmentAlistConfigBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesHelper
    private val client = AlistClient()
    private var savedToken = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAlistConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesHelper(requireContext())

        val serverJson = arguments?.getString("serverJson")
        if (serverJson != null) {
            val s = Gson().fromJson(serverJson, AlistConfig::class.java)
            binding.serverName.setText(s.name)
            binding.serverUrl.setText(s.host)
            binding.username.setText(s.username)
            binding.password.setText(s.password)
            savedToken = s.token
        }

        binding.testBtn.setOnClickListener { testLogin() }
        binding.saveBtn.setOnClickListener { save() }
        view.post { binding.serverUrl.requestFocus() }
    }

    private fun testLogin() {
        val cfg = buildConfig() ?: return
        showStatus("正在登录...", true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { client.login(cfg) } catch (e: Exception) { Result.failure(e) }
            }
            result.onSuccess { token ->
                savedToken = token
                showStatus("✅ 登录成功", true)
            }.onFailure { e ->
                showStatus("❌ 登录失败: ${e.message ?: "未知错误"}", false)
            }
        }
    }

    private fun buildConfig(): AlistConfig? {
        val host = binding.serverUrl.text?.toString()?.trim() ?: ""
        val username = binding.username.text?.toString()?.trim() ?: ""
        val password = binding.password.text?.toString() ?: ""
        if (host.isBlank()) { showStatus("请输入 AList 地址", false); return null }
        if (username.isBlank() || password.isBlank()) { showStatus("请输入账号和密码", false); return null }
        val name = binding.serverName.text?.toString()?.trim()?.ifBlank { null }
            ?: host.substringAfter("://").substringBefore("/").substringBefore(":")
        return AlistConfig(
            id = "",
            name = name,
            host = host,
            username = username,
            password = password,
            token = savedToken
        )
    }

    private fun save() {
        val cfg = buildConfig() ?: return
        // 未登录过 → 先登录拿 token 再保存
        if (savedToken.isBlank()) {
            showStatus("正在登录并保存...", true)
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    try { client.login(cfg) } catch (e: Exception) { Result.failure(e) }
                }
                result.onSuccess { token ->
                    savedToken = token
                    doSave(cfg)
                }.onFailure { e ->
                    showStatus("❌ 登录失败: ${e.message ?: "未知错误"}", false)
                }
            }
        } else {
            doSave(cfg)
        }
    }

    private fun doSave(cfg: AlistConfig) {
        val existingJson = arguments?.getString("serverJson")
        val existingId = if (existingJson != null) {
            Gson().fromJson(existingJson, AlistConfig::class.java).id
        } else ""
        val server = cfg.copy(
            id = existingId.ifBlank { java.util.UUID.randomUUID().toString() },
            token = savedToken
        )
        prefs.upsertAlistServer(server)
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