package com.embytv.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.embytv.R
import com.embytv.app.PreferencesHelper
import com.embytv.databinding.FragmentAccountBinding

/** 账户页：显示当前登录用户信息 + 退出登录 */
class AccountFragment : Fragment() {

    private var _binding: FragmentAccountBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = PreferencesHelper(requireContext())
        val server = prefs.activeEmbyServer()

        if (server != null) {
            binding.accountName.text = server.userName.ifBlank { "未登录" }
            binding.serverName.text = server.name.ifBlank { "未知服务器" }
            binding.serverUrl.text = server.baseUrl
        } else {
            binding.accountName.text = "未登录"
            binding.serverName.text = "无服务器"
            binding.serverUrl.text = ""
        }

        binding.logoutBtn.setOnClickListener {
            // 清空当前服务器配置，跳回服务器选择页
            prefs.clearEmbyConfig()
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_container, ServerListFragment())
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}