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
import com.embytv.api.AlistConfig
import com.embytv.app.PreferencesHelper
import com.embytv.databinding.FragmentAlistListBinding

/** AList 网盘列表页：配置管理 + 进入浏览 */
class AlistListFragment : Fragment() {

    private var _binding: FragmentAlistListBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAlistListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesHelper(requireContext())

        binding.addBtn.setOnClickListener { openConfig(null) }
        refreshList()
        view.post {
            if (prefs.alistServers.isEmpty()) binding.addBtn.requestFocus() else binding.alistList.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) refreshList()
    }

    private fun refreshList() {
        val servers = prefs.alistServers
        binding.alistList.layoutManager = LinearLayoutManager(requireContext())
        binding.alistList.adapter = AlistServerAdapter(
            servers,
            onBrowse = { server ->
                val frag = AlistBrowserFragment()
                frag.arguments = Bundle().apply { putString("configId", server.id) }
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_container, frag)
                    .addToBackStack("alist")
                    .commitAllowingStateLoss()
            },
            onEdit = { server -> openConfig(server) },
            onDelete = { server ->
                prefs.removeAlistServer(server.id)
                refreshList()
            }
        )
    }

    private fun openConfig(server: AlistConfig?) {
        val frag = AlistConfigFragment()
        val args = Bundle()
        if (server != null) args.putString("serverJson", com.google.gson.Gson().toJson(server))
        frag.arguments = args
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_container, frag)
            .addToBackStack("alist")
            .commitAllowingStateLoss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** AList 服务器列表适配器：点击进入浏览，长按弹菜单（编辑/删除） */
class AlistServerAdapter(
    private val servers: List<AlistConfig>,
    private val onBrowse: (AlistConfig) -> Unit,
    private val onEdit: (AlistConfig) -> Unit,
    private val onDelete: (AlistConfig) -> Unit
) : RecyclerView.Adapter<AlistServerAdapter.ViewHolder>() {

    override fun getItemCount() = servers.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_alist_file, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(h: ViewHolder, pos: Int) {
        val server = servers[pos]
        h.name.text = server.name.ifBlank { server.host }
        h.subtitle.text = server.host
        h.itemView.setOnClickListener { onBrowse(server) }
        h.itemView.setOnLongClickListener {
            val options = arrayOf("编辑", "删除", "取消")
            android.app.AlertDialog.Builder(h.itemView.context)
                .setTitle(server.name.ifBlank { server.host })
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> onEdit(server)
                        1 -> onDelete(server)
                    }
                }
                .show()
            true
        }
        val scale = FocusStyleHelper.scaleMultiplier(h.itemView.context)
        val hidden = FocusStyleHelper.hidden(h.itemView.context)
        h.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (!hidden) v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                .setDuration(120).start()
            v.setBackgroundResource(if (hasFocus) R.drawable.video_select_focused else R.drawable.video_select_focused_no)
        }
    }

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.fileName)
        val subtitle: TextView = v.findViewById(R.id.fileSubtitle)
    }
}