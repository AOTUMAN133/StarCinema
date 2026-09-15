package com.embytv.view

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.embytv.R
import com.embytv.api.EmbyClient
import com.embytv.app.EmbyServerConfig
import com.embytv.app.PreferencesHelper
import com.embytv.databinding.FragmentServerEditBinding
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServerEditFragment : Fragment() {
    private var _binding: FragmentServerEditBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesHelper
    private val client = EmbyClient()

    // 认证成功后缓存
    private var authAccessToken = ""
    private var authUserId = ""
    private var authUserName = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesHelper(requireContext())

        // 编辑模式：加载已有配置
        val serverJson = arguments?.getString("serverJson")
        if (serverJson != null) {
            val server = Gson().fromJson(serverJson, EmbyServerConfig::class.java)
            binding.serverName.setText(server.name)
            binding.serverUrl.setText(server.baseUrl)
            authAccessToken = server.accessToken
            authUserId = server.userId
            authUserName = server.userName
        }

        binding.testBtn.setOnClickListener { testLogin() }
        binding.saveBtn.setOnClickListener { save() }

        view.post { binding.serverName.requestFocus() }
    }

    private fun testLogin() {
        val url = binding.serverUrl.text?.toString()?.trim()?.trimEnd('/') ?: run { showStatus("请输入服务器地址", false); return }
        val username = binding.username.text?.toString()?.trim() ?: run { showStatus("请输入用户名", false); return }
        val password = binding.password.text?.toString() ?: run { showStatus("请输入密码", false); return }

        if (url.isBlank()) { showStatus("请输入服务器地址", false); return }
        if (username.isBlank()) { showStatus("请输入用户名", false); return }

        showStatus("正在登录...", true)

        lifecycleScope.launch {
            // 先 ping 获取服务器名称
            withContext(Dispatchers.IO) {
                try { client.ping(url) } catch (e: Exception) { Result.failure(e) }
            }.onSuccess { serverName ->
                // 始终使用服务器返回的名称
                binding.serverName.setText(serverName)
            }.onFailure {
                // ping 失败但仍尝试登录
            }

            // 账号密码登录
            val authResult = withContext(Dispatchers.IO) {
                try { client.authenticateByPassword(url, username, password) }
                catch (e: Exception) { Result.failure(e) }
            }
            authResult.onSuccess { result ->
                authAccessToken = result.accessToken ?: ""
                authUserId = result.userId ?: ""
                authUserName = result.userName ?: username
                if (authAccessToken.isNotBlank()) {
                    showStatus("✅ 登录成功 — ${authUserName}", true)
                } else {
                    showStatus("❌ 获取令牌失败", false)
                }
            }.onFailure { e ->
                showStatus("❌ 登录失败: ${e.message ?: "未知错误"}", false)
            }
        }
    }

    private fun showStatus(msg: String, isSuccess: Boolean) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = msg
        binding.statusText.setTextColor(
            ContextCompat.getColor(requireContext(), if (isSuccess) R.color.apple_green else R.color.apple_red)
        )
    }

    private fun save() {
        val url = binding.serverUrl.text?.toString()?.trim()?.trimEnd('/') ?: return
        if (url.isBlank()) {
            showStatus("请填写服务器地址", false)
            return
        }
        val name = binding.serverName.text?.toString()?.trim()?.ifBlank { null }
            ?: url.substringAfter("://").substringBefore(":").substringBefore("/")

        // 如果还没认证过，先自动登录一次再保存

        // 如果还没认证过，先自动登录一次再保存
        if (authAccessToken.isBlank()) {
            val username = binding.username.text?.toString()?.trim()
            val password = binding.password.text?.toString()
            if (username.isNullOrBlank() || password.isNullOrBlank()) {
                showStatus("请填写用户名和密码", false)
                return
            }
            showStatus("正在登录并保存...", true)
            lifecycleScope.launch {
                val authResult = withContext(Dispatchers.IO) {
                    try { client.authenticateByPassword(url, username, password) }
                    catch (e: Exception) { Result.failure(e) }
                }
                authResult.onSuccess { result ->
                    authAccessToken = result.accessToken ?: ""
                    authUserId = result.userId ?: ""
                    authUserName = result.userName ?: username
                    if (authAccessToken.isNotBlank()) {
                        doSave(name, url)
                    } else {
                        showStatus("❌ 获取令牌失败", false)
                    }
                }.onFailure { e ->
                    showStatus("❌ 登录失败: ${e.message ?: "未知错误"}", false)
                }
            }
        } else {
            // 已认证，直接保存
            doSave(name, url)
        }
    }

    private fun doSave(name: String, url: String) {
        val serverJson = arguments?.getString("serverJson")
        val existingId = if (serverJson != null) {
            Gson().fromJson(serverJson, EmbyServerConfig::class.java).id
        } else ""

        val server = EmbyServerConfig(
            id = existingId.ifBlank { java.util.UUID.randomUUID().toString() },
            name = name,
            baseUrl = url,
            accessToken = authAccessToken,
            userId = authUserId,
            userName = authUserName
        )
        prefs.upsertEmbyServer(server)
        prefs.activeEmbyServerId = server.id
        prefs.saveEmbyConfig(url, authAccessToken, authUserId, authUserName)

        requireActivity().supportFragmentManager.popBackStack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}