package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.app.EmbyServerConfig
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentServerEditBinding
import kotlinx.coroutines.launch

/**
 * 服务器编辑页（添加/编辑）：地址 + 账号 + 密码 → 认证 → 保存
 */
class ServerEditFragment : Fragment() {

    private var _binding: FragmentServerEditBinding? = null
    private val binding get() = _binding!!
    private val client = EmbyClient()
    private var editId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        editId = arguments?.getString("serverId")
        if (editId != null) {
            val s = PreferencesHelper(requireContext()).embyServers.firstOrNull { it.id == editId }
            if (s != null) {
                binding.urlInput.setText(s.baseUrl)
                binding.userInput.setText(s.userName)
            }
        }
        binding.saveBtn.setOnClickListener { doSave() }
        binding.cancelBtn.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }
        listOf(binding.saveBtn, binding.cancelBtn).forEach { tv ->
            tv.setOnKeyListener { v, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) { v.performClick(); true } else false
            }
        }
        binding.saveBtn.requestFocus()
    }

    private fun doSave() {
        val url = binding.urlInput.text.toString().trim().removeSuffix("/")
        val user = binding.userInput.text.toString().trim()
        val pass = binding.passInput.text.toString()
        if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            binding.statusText.text = "请填写服务器地址、账号、密码"
            return
        }
        binding.statusText.text = "连接中…"
        lifecycleScope.launch {
            client.authenticateByPassword(url, user, pass).onSuccess { auth ->
                if (auth.accessToken.isNullOrBlank() || auth.userId.isNullOrBlank()) {
                    binding.statusText.text = "认证失败：服务器返回异常"
                    return@onSuccess
                }
                val prefs = PreferencesHelper(requireContext())
                val server = EmbyServerConfig(
                    id = editId ?: java.util.UUID.randomUUID().toString(),
                    name = auth.userName ?: user,
                    baseUrl = url,
                    accessToken = auth.accessToken,
                    userId = auth.userId,
                    userName = auth.userName ?: user
                )
                prefs.upsertEmbyServer(server)
                prefs.activeEmbyServerId = server.id
                binding.statusText.text = "✓ 连接成功"
                (activity as? com.starcinema.MainActivity)?.showRoot(EmbyFragment())
            }.onFailure { e ->
                binding.statusText.text = "连接失败：${e.message ?: "未知错误"}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}