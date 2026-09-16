package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.BuildConfig
import com.starcinema.R
import com.starcinema.app.EmbyServerConfig
import com.starcinema.app.PreferencesHelper

/**
 * 服务器选择页（设计文档 v1.0 界面02）：
 * 左 40% 金色添加卡（默认焦点）+ 右 60% 服务器列表；确认连接进首页
 */
class ServerListFragment : Fragment() {

    private var _binding: com.starcinema.databinding.FragmentServerListBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = com.starcinema.databinding.FragmentServerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.versionTag.text = "v${BuildConfig.VERSION_NAME}"
        binding.addServerBtn.setOnClickListener { openEdit(null) }
        binding.addServerBtn.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) { v.performClick(); true } else false
        }
        binding.addServerBtn.post { binding.addServerBtn.requestFocus() } // 首次默认焦点在添加卡
        reload()
    }

    private fun reload() {
        val servers = PreferencesHelper(requireContext()).embyServers
        binding.serverList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = servers.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_server_card, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val s = servers[pos]
                val name = h.itemView.findViewById<TextView>(R.id.serverName)
                val url = h.itemView.findViewById<TextView>(R.id.serverUrl)
                name.text = s.name.ifBlank { s.baseUrl }
                url.text = s.baseUrl
                h.itemView.setOnClickListener { connect(s) }
                h.itemView.setOnKeyListener { v, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_UP &&
                        (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                         keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                         keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                    ) { v.performClick(); true } else false
                }
                h.itemView.setOnFocusChangeListener { v, hasFocus ->
                    v.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f)
                        .setDuration(200).start()
                }
            }
        }
    }

    private fun connect(server: EmbyServerConfig) {
        PreferencesHelper(requireContext()).activeEmbyServerId = server.id
        (activity as? com.starcinema.MainActivity)?.showRoot(EmbyFragment())
    }

    private fun openEdit(server: EmbyServerConfig?) {
        val frag = ServerEditFragment().apply {
            arguments = Bundle().apply { putString("serverId", server?.id) }
        }
        (activity as? com.starcinema.MainActivity)?.openFragment(frag)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}