package com.embytv.api

import com.embytv.app.SmbServerConfig
import jcifs.CIFSContext
import jcifs.CIFSException
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream
import java.util.Properties

data class SmbItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0
)

class SmbClient(private val config: SmbServerConfig) {
    companion object {
        /** 创建 jcifs 上下文（供 SmbHttpServer 使用） */
        fun contextFor(config: SmbServerConfig): CIFSContext {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.responseTimeout", "30000")
                setProperty("jcifs.smb.client.connTimeout", "10000")
            }
            val cfg = PropertyConfiguration(props)
            val base = BaseContext(cfg)
            return if (config.username.isNotBlank()) {
                base.withCredentials(NtlmPasswordAuthenticator(config.domain, config.username, config.password))
            } else base
        }
    }

    private val ctx: CIFSContext by lazy { contextFor(config) }

    private fun rootUrl(): String {
        val host = config.host
        val basePath = config.basePath.trimStart('/')
        return "smb://$host/$basePath"
    }

    /** 列出目录内容 */
    fun listDir(path: String): Result<List<SmbItem>> {
        return try {
            val url = if (path.startsWith("smb://")) path else {
                val clean = if (path.startsWith("/")) path else "/$path"
                rootUrl().trimEnd('/') + clean
            }
            val dir = SmbFile(url, ctx)
            if (!dir.exists()) return Result.failure(CIFSException("路径不存在: $path"))
            val items = mutableListOf<SmbItem>()
            for (f in dir.listFiles()) {
                items.add(SmbItem(
                    name = f.name,
                    path = f.path,
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0,
                    lastModified = f.lastModified()
                ))
            }
            items.sortWith(compareByDescending<SmbItem> { it.isDirectory }.thenBy { it.name })
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 获取 SMB 播放 URL */
    fun getPlayUrl(path: String): String {
        return if (path.startsWith("smb://")) path else {
            val clean = if (path.startsWith("/")) path else "/$path"
            rootUrl().trimEnd('/') + clean
        }
    }

    /** 获取文件输入流 */
    fun getInputStream(path: String): Result<InputStream> {
        return try {
            val url = getPlayUrl(path)
            val file = SmbFile(url, ctx)
            Result.success(file.inputStream)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}