package com.embytv.view

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.embytv.R
import com.embytv.api.AlistClient
import com.embytv.api.AlistConfig
import com.embytv.api.AlistFileItem
import com.embytv.app.PreferencesHelper
import kotlinx.coroutines.launch

/**
 * AList 网盘文件浏览页：目录导航 + 视频播放
 * 端点/播放链路从 AfuseKtV 3.0.6.3 反编译确认：
 *   list → POST {host}/api/fs/list {path,password,page,per_page,refresh}
 *   播放 → POST {host}/api/fs/get {path} → data.raw_url → PlayerActivity(source)
 */
class AlistBrowserFragment : Fragment() {

    private var _binding: com.embytv.databinding.FragmentAlistBrowserBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesHelper
    private val client = AlistClient()
    private var config: AlistConfig? = null
    private val pathStack = mutableListOf<String>()
    private var currentPath = "/"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = com.embytv.databinding.FragmentAlistBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesHelper(requireContext())

        val configId = arguments?.getString("configId") ?: prefs.alistServers.firstOrNull()?.id
        config = prefs.alistServers.firstOrNull { it.id == configId }

        if (config == null) {
            binding.pathText.text = "未配置 AList 服务器"
            return
        }
        binding.pathText.text = "/"

        binding.backBtn.setOnClickListener { goUp() }
        binding.rootBtn.setOnClickListener { navigateTo("/") }

        loadPath("/")
        view.post { binding.fileList.requestFocus() }
    }

    private fun loadPath(path: String) {
        val cfg = config ?: return
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.pathText.text = path
        lifecycleScope.launch {
            client.listFiles(cfg, path).onSuccess { items ->
                binding.loadingIndicator.visibility = View.GONE
                binding.fileList.layoutManager = LinearLayoutManager(requireContext())
                binding.fileList.adapter = AlistFileAdapter(items) { item ->
                    if (item.isDir) navigateTo(item.path) else playFile(item)
                }
            }.onFailure { e ->
                binding.loadingIndicator.visibility = View.GONE
                binding.pathText.text = "加载失败: ${e.message ?: "未知错误"}"
            }
        }
    }

    private fun navigateTo(path: String) {
        pathStack.add(currentPath)
        currentPath = path
        loadPath(path)
    }

    private fun goUp() {
        if (pathStack.isEmpty()) return
        currentPath = pathStack.removeAt(pathStack.size - 1)
        loadPath(currentPath)
    }

    private fun playFile(item: AlistFileItem) {
        val cfg = config ?: return
        binding.loadingIndicator.visibility = View.VISIBLE
        lifecycleScope.launch {
            client.getPlayUrl(cfg, item.path).onSuccess { url ->
                binding.loadingIndicator.visibility = View.GONE
                val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra("source", url)
                    putExtra("title", item.name)
                }
                startActivity(intent)
            }.onFailure { e ->
                binding.loadingIndicator.visibility = View.GONE
                binding.pathText.text = "播放链接获取失败: ${e.message ?: "未知错误"}"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** AList 文件列表适配器 */
class AlistFileAdapter(
    private val items: List<AlistFileItem>,
    private val onClick: (AlistFileItem) -> Unit
) : RecyclerView.Adapter<AlistFileAdapter.ViewHolder>() {

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_alist_file, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(h: ViewHolder, pos: Int) {
        val item = items[pos]
        h.name.text = if (item.isDir) "📁 ${item.name}" else "🎬 ${item.name}"
        val sizeText = when {
            item.isDir -> "目录"
            item.size >= 1024L * 1024 * 1024 -> String.format("%.2f GB", item.size / (1024.0 * 1024 * 1024))
            item.size >= 1024 * 1024 -> String.format("%.1f MB", item.size / (1024.0 * 1024))
            else -> "${item.size / 1024} KB"
        }
        h.subtitle.text = sizeText
        h.itemView.setOnClickListener { onClick(item) }
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