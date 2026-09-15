package com.embytv.view

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.embytv.R
import com.embytv.api.SmbClient
import com.embytv.api.SmbHttpServer
import com.embytv.api.WebDavClient
import com.embytv.api.WebDavConfig
import com.embytv.app.PreferencesHelper
import com.embytv.databinding.FragmentNetBrowserBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * WebDAV / SMB 共用目录浏览页：
 * - type=webdav → WebDavClient 直连，播放带 Basic Auth 头的直链
 * - type=smb    → SmbClient 列目录，SmbHttpServer 本地代理播放（EXO/MPV 均支持 http+Range）
 */
class NetBrowserFragment : Fragment() {

    private var _binding: FragmentNetBrowserBinding? = null
    private val binding get() = _binding!!
    private var type = "webdav"
    private var config: Any? = null
    private var curPath = "/"

    private val webdavClient by lazy { WebDavClient() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNetBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        type = arguments?.getString("type") ?: "webdav"
        val serverId = arguments?.getString("serverId") ?: ""
        val prefs = PreferencesHelper(requireContext())
        config = if (type == "smb") prefs.smbServers.firstOrNull { it.id == serverId }
                 else prefs.webdavServers.firstOrNull { it.id == serverId }

        binding.rootBtn.setOnClickListener { navigateTo("/") }
        binding.backBtn.setOnClickListener { goUp() }

        if (config == null) {
            binding.pathText.text = "服务器配置不存在，请返回重新选择"
            return
        }
        binding.pathText.text = type.ifBlank { "webdav" } + ": /"
        if (type == "webdav") binding.pathText.text = "WebDAV: /"
        else binding.pathText.text = "SMB: /"
        loadPath(curPath)
        view.post { binding.fileList.requestFocus() }
    }

    private fun loadPath(path: String) {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.pathText.text = (if (type == "smb") "SMB: " else "WebDAV: ") + path
        lifecycleScope.launch {
            val result = when (type) {
                "smb" -> {
                    val srv = config as? com.embytv.app.SmbServerConfig
                    if (srv != null) SmbClient(srv).listDir(fullSmbPath(path)) else Result.failure(Exception("配置缺失"))
                }
                else -> {
                    val srv = config as? com.embytv.app.WebDavServerConfig
                    if (srv != null) webdavClient.listDir(srv.toClientConfig(), fullWebDavPath(path))
                    else Result.failure(Exception("配置缺失"))
                }
            }
            binding.loadingIndicator.visibility = View.GONE
            result.onSuccess { items ->
                val list = if (type == "smb") {
                    (items as List<com.embytv.api.SmbItem>).map { NetFileItem(it.name, it.path, it.isDirectory, it.size, "") }
                } else {
                    (items as List<com.embytv.api.WebDavItem>).map { NetFileItem(it.name, it.path, it.isDirectory, it.size, it.modifiedAt) }
                }
                binding.fileList.layoutManager = LinearLayoutManager(requireContext())
                binding.fileList.adapter = NetFileAdapter(list) { item -> onItemClick(item) }
            }.onFailure { e ->
                binding.pathText.text = "加载失败: ${e.message ?: "未知错误"}"
            }
        }
    }

    private fun onItemClick(item: NetFileItem) {
        if (item.isDirectory) {
            curPath = item.path
            loadPath(curPath)
        } else {
            playFile(item)
        }
    }

    private fun playFile(item: NetFileItem) {
        when (type) {
            "smb" -> {
                val srv = config as? com.embytv.app.SmbServerConfig ?: return
                val smbPath = item.path.removePrefix("smb://").substringAfter("/")
                val srvKey = SmbHttpServer.register(srv)
                val url = SmbHttpServer.playUrl(srvKey, smbPath)
                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra("source", url)
                    putExtra("title", item.name)
                })
            }
            else -> {
                val srv = config as? com.embytv.app.WebDavServerConfig ?: return
                val cfg = srv.toClientConfig()
                // 认证嵌入 URL（PlayerActivity 只传 Emby token header，网盘直链需自带 Basic Auth）
                val url = cfg.baseUrl() + fullWebDavPath(item.path)
                val authUrl = if (cfg.username.isNotBlank()) {
                    val user = java.net.URLEncoder.encode(cfg.username, "UTF-8")
                    val pass = java.net.URLEncoder.encode(cfg.password, "UTF-8")
                    "http://$user:$pass@${cfg.host}:${cfg.port}" + fullWebDavPath(item.path)
                } else url
                startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
                    putExtra("source", authUrl)
                    putExtra("title", item.name)
                })
            }
        }
    }

    private fun navigateTo(path: String) {
        curPath = path
        loadPath(curPath)
    }

    private fun goUp() {
        if (type == "smb") {
            val cur = curPath.ifBlank { baseSmbPath() }
            val parent = cur.trimEnd('/').substringBeforeLast("/").ifEmpty { baseSmbPath() }
            curPath = parent
            loadPath(curPath)
        } else {
            if (curPath == "/") return
            val parent = curPath.trimEnd('/').substringBeforeLast("/").ifEmpty { "/" }
            curPath = parent
            loadPath(curPath)
        }
    }

    private fun baseSmbPath(): String {
        val srv = config as? com.embytv.app.SmbServerConfig ?: return ""
        return "/${srv.basePath.trimStart('/')}"
    }

    private fun fullSmbPath(path: String): String {
        val srv = config as? com.embytv.app.SmbServerConfig ?: return path
        // SmbClient.listDir 需要完整 smb:// 路径；裸路径补全
        if (path.startsWith("smb://")) return path
        val root = "smb://${srv.host}/${srv.basePath.trimStart('/')}"
        val clean = path.trimStart('/')
        return root.trimEnd('/') + (if (clean.isEmpty()) "" else "/$clean")
    }

    private fun fullWebDavPath(path: String): String {
        val srv = config as? com.embytv.app.WebDavServerConfig ?: return path
        // 路径相对于 basePath 展开为服务器完整路径
        val base = srv.basePath.trimEnd('/')
        val clean = path.trimStart('/')
        return (base + "/" + clean).replace("//", "/")
    }

    private fun com.embytv.app.WebDavServerConfig.toClientConfig() =
        WebDavConfig(host, port, username, password, useHttps)

    override fun onResume() {
        super.onResume()
        // 播放返回后刷新（进度/文件可能变化）
        if (_binding != null && config != null) loadPath(curPath)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** 浏览器通用文件项 */
data class NetFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modifiedAt: String = ""
) {
    fun sizeLabel(): String =
        if (isDirectory) "文件夹"
        else when {
            size >= 1_073_741_824L -> "%.1f GB".format(size / 1_073_741_824f)
            size >= 1_048_576L -> "%.1f MB".format(size / 1_048_576f)
            else -> "%.0f KB".format(size / 1024f)
        }
}

/** 通用文件列表适配器 */
class NetFileAdapter(
    private val items: List<NetFileItem>,
    private val onClick: (NetFileItem) -> Unit
) : RecyclerView.Adapter<NetFileAdapter.ViewHolder>() {

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_alist_file, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(h: ViewHolder, pos: Int) {
        val item = items[pos]
        h.name.text = (if (item.isDirectory) "📁 " else "🎬 ") + item.name
        h.subtitle.text = if (item.isDirectory) item.modifiedAt.ifBlank { "文件夹" } else item.sizeLabel()
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