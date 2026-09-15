package com.embytv.api

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class WebDavItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modifiedAt: String = "",
    val mimeType: String = ""
)

data class WebDavConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val useHttps: Boolean = false
) {
    fun baseUrl(): String {
        val protocol = if (useHttps) "https" else "http"
        return "$protocol://$host:$port"
    }
}

class WebDavClient {
    companion object {
        /** 全局共享 OkHttpClient（连接池复用，避免每次导航建新实例） */
        private val sharedClient: OkHttpClient by lazy {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }

    private val client: OkHttpClient get() = sharedClient

    private val xmlMediaType = "application/xml".toMediaType()
    private val propfindBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
    <d:prop>
        <d:displayname/>
        <d:getcontentlength/>
        <d:getcontenttype/>
        <d:getlastmodified/>
        <d:resourcetype/>
    </d:prop>
</d:propfind>""".trimIndent()

    /**
     * 列出 WebDAV 目录下的文件
     */
    fun listDir(config: WebDavConfig, path: String): Result<List<WebDavItem>> {
        return try {
            val url = "${config.baseUrl()}$path"
            val auth = Credentials.basic(config.username, config.password)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .header("Depth", "1")
                .addHeader("Content-Type", "application/xml; charset=utf-8")
                .propfind(propfindBody.toRequestBody(xmlMediaType))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return Result.failure(IOException("HTTP ${response.code}: ${body.take(200)}"))
            }

            val items = parsePropfindResponse(body, path)
            // 去掉当前目录项（path 本身）
            val filtered = items.filter { it.path != path && it.path != "$path/" }
            Result.success(filtered)
        } catch (e: Exception) {
            Result.failure(IOException("WebDAV 列表失败: ${e.message}", e))
        }
    }

    /**
     * 获取视频文件的直接播放 URL
     */
    fun getPlayUrl(config: WebDavConfig, path: String): String {
        val auth = Credentials.basic(config.username, config.password)
        // 通过 URL 参数传递认证（适合播放器直接流式播放）
        // 实际播放时通过 headers 传递 auth 更安全
        return "${config.baseUrl()}$path"
    }

    /**
     * 获取下载认证头
     */
    fun getAuthHeaders(config: WebDavConfig): Map<String, String> {
        return mapOf("Authorization" to Credentials.basic(config.username, config.password))
    }

    private fun parsePropfindResponse(xml: String, basePath: String): List<WebDavItem> {
        val items = mutableListOf<WebDavItem>()
        try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val builder = factory.newDocumentBuilder()
            val doc: Document = builder.parse(xml.byteInputStream())
            doc.documentElement.normalize()

            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            for (i in 0 until responses.length) {
                val response = responses.item(i)
                val href = getTextContent(response, "DAV:", "href") ?: continue
                val displayName = getTextContent(response, "DAV:", "displayname")
                val contentLength = getTextContent(response, "DAV:", "getcontentlength")
                val contentType = getTextContent(response, "DAV:", "getcontenttype")
                val lastModified = getTextContent(response, "DAV:", "getlastmodified")

                val isDir = hasElement(response, "DAV:", "collection")
                val name = displayName ?: href.substringAfterLast("/").ifEmpty { href }
                val path = if (href.startsWith("http")) {
                    // 绝对 URL → 提取路径部分
                    java.net.URI(href).path?.let { p ->
                        if (p.endsWith("/") && !isDir) p.dropLast(1) else p
                    } ?: href
                } else {
                    href
                }

                items.add(WebDavItem(
                    name = name,
                    path = path,
                    isDirectory = isDir,
                    size = contentLength?.toLongOrNull() ?: 0,
                    modifiedAt = lastModified ?: "",
                    mimeType = contentType ?: ""
                ))
            }
        } catch (_: Exception) {
            // XML 解析失败时返回空列表
        }
        return items
    }

    private fun getTextContent(node: org.w3c.dom.Node, namespace: String, tagName: String): String? {
        val list = node.childNodes
        for (i in 0 until list.length) {
            val child = list.item(i)
            if (child.nodeName == "$namespace$tagName" || child.localName == tagName) {
                return child.textContent?.trim()
            }
        }
        // 递归查找
        val byTag = (node as? org.w3c.dom.Element)?.getElementsByTagNameNS(namespace, tagName)
        return if (byTag != null && byTag.length > 0) byTag.item(0).textContent?.trim() else null
    }

    private fun hasElement(node: org.w3c.dom.Node, namespace: String, tagName: String): Boolean {
        val byTag = (node as? org.w3c.dom.Element)?.getElementsByTagNameNS(namespace, tagName)
        return byTag != null && byTag.length > 0
    }

    private fun Request.Builder.propfind(body: okhttp3.RequestBody): Request.Builder {
        return this.method("PROPFIND", body)
    }
}