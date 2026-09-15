package com.embytv.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.embytv.R
import com.embytv.BuildConfig
import com.embytv.app.EmbyServerConfig
import com.embytv.app.PreferencesHelper
import com.embytv.databinding.FragmentServerListBinding

/** 服务器列表页（从旧 SettingsFragment 迁移） */
class ServerListFragment : Fragment() {

    private var _binding: FragmentServerListBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesHelper(requireContext())

        // 星光影院：右下角版本号
        binding.versionTag.text = "v${BuildConfig.VERSION_NAME}"

        binding.serverList.layoutManager = LinearLayoutManager(requireContext())
        binding.addServerBtn.setOnClickListener {
            openServerEdit(null)
        }

        refreshList()

        view.post { binding.addServerBtn.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) {
            refreshList()
        }
    }

    private fun refreshList() {
        val servers = prefs.embyServers
        binding.serverList.adapter = ServerAdapter(servers,
            onSelect = { server ->
                prefs.activeEmbyServerId = server.id
                prefs.saveEmbyConfig(server.baseUrl, server.accessToken, server.userId, server.userName)
                requireActivity().supportFragmentManager.popBackStack()
                requireActivity().supportFragmentManager.popBackStack()
            },
            onEdit = { server -> openServerEdit(server) }
        )
    }

    private fun openServerEdit(server: EmbyServerConfig?) {
        val fragment = ServerEditFragment()
        val args = Bundle()
        if (server != null) {
            args.putString("serverJson", com.google.gson.Gson().toJson(server))
        }
        fragment.arguments = args

        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_container, fragment)
            .addToBackStack("settings")
            .commitAllowingStateLoss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class ServerAdapter(
    private val servers: List<EmbyServerConfig>,
    private val onSelect: (EmbyServerConfig) -> Unit,
    private val onEdit: (EmbyServerConfig) -> Unit
) : RecyclerView.Adapter<ServerAdapter.Holder>() {

    override fun getItemCount() = servers.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_server_card, parent, false)
        return Holder(v)
    }
    override fun onBindViewHolder(h: Holder, pos: Int) {
        val server = servers[pos]
        h.name.text = server.name
        h.url.text = server.baseUrl
        // 星光影院：单击=选择进入，长按=编辑
        h.itemView.setOnClickListener { onSelect(server) }
        h.itemView.setOnLongClickListener { onEdit(server); true }
    }
    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val name: android.widget.TextView = v.findViewById(R.id.serverName)
        val url: android.widget.TextView = v.findViewById(R.id.serverUrl)
    }
}