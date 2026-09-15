package com.embytv.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * AList 网盘客户端（端点从 AfuseKtV 3.0.6.3 反编译确认）：
 * - login:      POST /api/auth/login  {username,password} → data.token
 * - listFiles:  POST /api/fs/list     {path,password,page,per_page,refresh} → data.content
 * - getPlayUrl: POST /api/fs/get      {path,password} → data.raw_url
 * 请求头: Authorization: <token>（AfuseKtV 直接用 token 值，无 Bearer 前缀）
 */
data class AlistConfig(
    val id: String,
    val name: String,
    val host: String,
    val username: String = "",
    val password: String = "",
    val token: String = ""
) {
    fun baseUrl(): String {
        val h = host.trim().trimEnd('/')
        return if (h.startsWith("http")) h else "http://$h"
    }
}

data class AlistFileItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    val modified: String = "",
    val isVideo: Boolean = false
)

class AlistClient {
    companion object {
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
        private val jsonType = "application/json; charset=utf-8".toMediaType()

        private val VIDEO_EXT = listOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "ts", "m2ts", "rmvb", "rm", "3gp", "ogv"
        )
    }

    private val client: OkHttpClient get() = sharedClient

    private fun post(url: String, body: String, token: String = ""): Result<String> = try {
        val reqBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonType))
            .header("User-Agent", "EmbyTV/1.0")
        if (token.isNotBlank()) reqBuilder.header("Authorization", token)
        val resp = client.newCall(reqBuilder.build()).execute()
        val bodyStr = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) Result.failure(IOException("HTTP ${resp.code}: ${bodyStr.take(200)}"))
        else Result.success(bodyStr)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 登录获取 token */
    suspend fun login(config: AlistConfig): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("username", config.username)
            put("password", config.password)
        }.toString()
        post("${config.baseUrl()}/api/auth/login", body).map { resp ->
            val json = JSONObject(resp)
            if (json.optInt("code", -1) != 200) throw IOException(json.optString("message", "登录失败"))
            json.getJSONObject("data").getString("token")
        }
    }

    /** 列目录：path 为 AList 路径（如 / /影视 /影视/电影） */
    suspend fun listFiles(config: AlistConfig, path: String, password: String = ""): Result<List<AlistFileItem>> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("path", path)
            put("password", password)
            put("page", 1)
            put("per_page", 200)
            put("refresh", false)
        }.toString()
        post("${config.baseUrl()}/api/fs/list", body, config.token).map { resp ->
            val json = JSONObject(resp)
            if (json.optInt("code", -1) != 200) throw IOException(json.optString("message", "列表获取失败"))
            val content = json.getJSONObject("data").getJSONArray("content")
            val items = mutableListOf<AlistFileItem>()
            for (i in 0 until content.length()) {
                val f = content.getJSONObject(i)
                val name = f.optString("name", "")
                val isDir = f.optBoolean("is_dir", false)
                val full = if (path == "/") "/$name" else "${path.trimEnd('/')}/$name"
                items += AlistFileItem(
                    name = name,
                    path = full,
                    isDir = isDir,
                    size = f.optLong("size", 0),
                    modified = f.optString("modified", ""),
                    isVideo = !isDir && VIDEO_EXT.any { name.lowercase().endsWith(".$it") }
                )
            }
            // 目录在前，视频文件在后，均按名称排序
            items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        }
    }

    /** 获取播放直链：POST /api/fs/get {path} → data.raw_url */
    suspend fun getPlayUrl(config: AlistConfig, path: String): Result<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("path", path)
            put("password", "")
        }.toString()
        post("${config.baseUrl()}/api/fs/get", body, config.token).map { resp ->
            val json = JSONObject(resp)
            if (json.optInt("code", -1) != 200) throw IOException(json.optString("message", "获取直链失败"))
            val data = json.getJSONObject("data")
            val raw = data.optString("raw_url", "")
            if (raw.isBlank()) throw IOException("raw_url 为空（文件可能未就绪或需登录）")
            raw
        }
    }
}