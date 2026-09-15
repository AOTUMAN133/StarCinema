package com.starcinema.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// ==================== 数据模型 ====================

data class EmbyServerConfig(
    val host: String,
    val port: Int,
    val apiKey: String,
    val useHttps: Boolean = false,
    val displayName: String = ""
) {
    fun baseUrl(): String {
        val protocol = if (useHttps) "https" else "http"
        return "$protocol://$host:$port"
    }
}

data class EmbyAuthResult(
    val userId: String?,
    val accessToken: String?,
    val userName: String?
)

data class EmbyLibrary(
    val id: String,
    val name: String,
    val type: String,
    val collectionType: String? = null,
    val imageTags: Map<String, String>? = null,
    val primaryImageTag: String? = null
)
data class EmbyItem(
    val id: String,
    val name: String,
    val type: String,
    val isFolder: Boolean,
    val isVideo: Boolean,
    val runTimeTicks: Long? = null,
    val parentIndexNumber: Int? = null,
    val imageTags: Map<String, String>? = null,
    val parentId: String? = null,
    val primaryImageTag: String? = null,
    val overview: String? = null,
    val productionYear: Int? = null,
    val communityRating: Float? = null,
    val userData: EmbyUserData? = null,
    val indexNumber: Int? = null,
    val seriesName: String? = null,
    val seriesId: String? = null,
    val seasonName: String? = null,
    val seasonId: String? = null,
    val genres: List<String>? = null,
    val studios: List<String>? = null,
    val officialRating: String? = null,
    val people: List<EmbyPerson>? = null,
    val externalUrls: List<Pair<String, String>>? = null,
    val backdropImageTags: List<String>? = null,
    /** ProviderIds（Tmdb/Imdb 等，详情页 TMDB 补全用） */
    val providerIds: Map<String, String>? = null,
    /** 详情页媒体源信息（MediaSources 字段） */
    val mediaSources: List<EmbyMediaSource>? = null,
    /** 详情页媒体流信息（视频/音频/字幕轨道，取第一个视频流宽度/高度/编码） */
    val videoStreamInfo: EmbyStreamInfo? = null
)

data class EmbyPerson(
    val id: String? = null,
    val name: String,
    val role: String? = null,
    val type: String? = null,
    val primaryImageTag: String? = null,
    /** 直接头像 URL（TMDB 补全用，优先于 primaryImageTag） */
    val imageUrl: String? = null
)

data class EmbyUserData(
    val unwatchedCount: Int? = null,
    val unplayedItemCount: Int? = null,
    val played: Boolean? = null,
    val playbackPositionTicks: Long? = null,
    val isFavorite: Boolean? = null,
    val playedPercentage: Double? = null,
    val lastPlayedDate: String? = null
)

/** 分页查询结果 */
data class EmbyItemsPage(
    val items: List<EmbyItem>,
    val totalRecordCount: Int,
    val startIndex: Int
) {
    val hasMore: Boolean get() = startIndex + items.size < totalRecordCount
}

/** PlaybackInfo 返回：播放源列表 + 播放会话 ID */
data class EmbyPlaybackInfo(
    val mediaSources: List<EmbyMediaSource>,
    val playSessionId: String
)

/** 单个播放源 */
data class EmbyMediaSource(
    val id: String,
    val container: String,
    val path: String?,
    val isInfiniteStream: Boolean = false,
    val supportsTranscoding: Boolean = true,
    val supportsDirectPlay: Boolean = true,
    val supportsDirectStream: Boolean = true,
    val defaultAudioStreamIndex: Int? = null,
    val defaultSubtitleStreamIndex: Int? = null,
    /** 多版本名称（如 1080p/4K 或用户自定义版本名） */
    val name: String? = null,
    /** 视频分辨率 */
    val height: Int? = null,
    val width: Int? = null,
    /** 总码率 bps */
    val bitRate: Long? = null,
    /** 文件大小字节 */
    val size: Long? = null,
    /** 视频流编码信息（取第一个视频流） */
    val videoCodec: String? = null,
    /** 媒体流列表（含字幕/音轨/视频流） */
    val mediaStreams: List<Map<String, Any>>? = null
)

/** 流的轨道信息（音频/视频/字幕） */
data class EmbyMediaStream(
    val type: String?,
    val codec: String?,
    val index: Int?,
    val displayTitle: String?,
    val language: String?,
    val isDefault: Boolean = false,
    val isExternal: Boolean = false,
    val isTextSubtitleStream: Boolean = false,
    /** 视频流宽高/码率/帧率（详情页媒体信息用） */
    val width: Int? = null,
    val height: Int? = null,
    val bitRate: Long? = null,
    val frameRate: Float? = null,
    val profile: String? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null
)

/** 详情页视频流展示信息（首个视频流的概要） */
data class EmbyStreamInfo(
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitRate: Long? = null,
    val frameRate: Float? = null,
    val profile: String? = null,
    val audioStreams: List<String> = emptyList(),
    val subtitleStreams: Int = 0
)

private fun toInt(value: Any?): Int? {
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toDoubleOrNull()?.toInt()
        else -> null
    }
}

private fun toLong(value: Any?): Long? {
    return when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

private fun toFloat(value: Any?): Float? {
    return when (value) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull()
        else -> null
    }
}

// ==================== Emby 客户端 ====================

class EmbyClient {
    /** 服务器类型：emby / jellyfin */
    var serverType: String = "emby"

    private val gson = Gson()

    companion object {
        private const val CLIENT_NAME = "DidiTV"
        private const val DEVICE_NAME = "Android"
        private const val DEVICE_ID = "diditv-android"
        private const val VERSION = "0.14.0"
    }

    private fun apiUrl(baseUrl: String, path: String): String {
        // 根据服务器类型选择 API 前缀：Emby 用 /emby/，Jellyfin 用 /jellyfin/
        val prefix = if (serverType == "jellyfin") "jellyfin" else "emby"
        return "$baseUrl/$prefix/$path"
    }

    private val client: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun authHeader(token: String?): String {
        var header = "MediaBrowser Client=\"DidiTV\", Device=\"Android\", " +
            "DeviceId=\"diditv-android\", Version=\"$VERSION\""
        if (!token.isNullOrEmpty()) {
            header += ", Token=\"$token\""
        }
        return header
    }

    suspend fun ping(baseUrl: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val response = client.newCall(
                    Request.Builder().url(apiUrl(baseUrl, "System/Info/Public")).build()
                ).execute()
                val body = response.body?.string() ?: "{}"
                Log.d("EmbyClient", "ping response: HTTP ${response.code}, body=$body")
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                // 优先返回服务器名称，其次版本号
                val serverName = json["ServerName"] as? String
                val version = json["Version"] as? String ?: "未知"
                Result.success(serverName ?: version)
            } catch (e: Exception) {
                Result.failure(IOException("连接失败: ${e.message}", e))
            }
        }
    }

    suspend fun authenticateWithApiKey(baseUrl: String, apiKey: String): Result<EmbyAuthResult> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val response = client.newCall(
                    Request.Builder()
                        .url(apiUrl(baseUrl, "Users/Public"))
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(null))
                        .build()
                ).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("API Key 无效 (HTTP ${response.code})"))
                }
                val body = response.body?.string() ?: "[]"
                val users = gson.fromJson(body, Array::class.java)
                val userId = if (users.isNotEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    (users[0] as Map<String, Any>)["Id"] as? String
                } else null
                Result.success(EmbyAuthResult(userId, apiKey, null))
            } catch (e: Exception) {
                Result.failure(IOException("认证失败: ${e.message}", e))
            }
        }
    }

    suspend fun authenticateByPassword(
        baseUrl: String,
        username: String,
        password: String
    ): Result<EmbyAuthResult> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val jsonBody = com.google.gson.JsonObject().apply {
                                    addProperty("Username", username)
                                    addProperty("Pw", password)
                                }.toString()
                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                val response = client.newCall(
                    Request.Builder()
                        .url(apiUrl(baseUrl, "Users/AuthenticateByName"))
                        .header("Authorization", authHeader(null))
                        .post(requestBody)
                        .build()
                ).execute()
                val body = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    val msg = try {
                        @Suppress("UNCHECKED_CAST")
                        (gson.fromJson(body, Map::class.java) as Map<String, Any>)["Message"] as? String
                    } catch (_: Exception) { null }
                    return@withContext Result.failure(IOException(msg ?: "登录失败 (HTTP ${response.code})"))
                }
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                val userId = (json["User"] as? Map<String, Any>)?.get("Id") as? String
                val accessToken = json["AccessToken"] as? String
                val userName = (json["User"] as? Map<String, Any>)?.get("Name") as? String
                Result.success(EmbyAuthResult(userId, accessToken, userName))
            } catch (e: Exception) {
                Result.failure(IOException("登录失败: ${e.message}", e))
            }
        }
    }

    suspend fun getLibraries(baseUrl: String, apiKey: String, userId: String?): Result<List<EmbyLibrary>> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val url = if (!userId.isNullOrBlank()) apiUrl(baseUrl, "Users/$userId/Views") else apiUrl(baseUrl, "Library/MediaFolders")
                val response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(apiKey))
                        .header("X-Emby-Authorization", authHeader(apiKey))
                        .build()
                ).execute()
                val body = response.body?.string() ?: "{}"
                Log.d("EmbyClient", "getLibraries url=$url HTTP ${response.code}")
                if (!response.isSuccessful) {
                    Log.e("EmbyClient", "getLibraries failed: HTTP ${response.code}: ${body.take(500)}")
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${body.take(200)}"))
                }
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val items = json["Items"] as? List<Map<String, Any>> ?: emptyList()
                val libraries = items.mapNotNull { item ->
                    val id = item["Id"] as? String ?: return@mapNotNull null
                    EmbyLibrary(
                        id = id,
                        name = item["Name"] as? String ?: "未命名",
                        type = item["Type"] as? String ?: "",
                        collectionType = item["CollectionType"] as? String,
                        imageTags = @Suppress("UNCHECKED_CAST") item["ImageTags"] as? Map<String, String>,
                        primaryImageTag = item["PrimaryImageTag"] as? String
                    )
                }
                Result.success(libraries)
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                val userMsg = when {
                    msg.contains("connection closed") || msg.contains("eof") || msg.contains("reset") ->
                        "服务器连接被断开，请检查：\n1. Emby 服务器是否运行\n2. 地址和端口是否正确\n3. 是否开启了 HTTPS（如果服务器用 HTTP 请关闭 HTTPS 选项）"
                    msg.contains("timeout") ->
                        "连接超时，请检查服务器地址和网络"
                    msg.contains("unable to resolve host") || msg.contains("unknown host") ->
                        "无法解析服务器地址，请检查 IP/域名是否正确"
                    else -> msg
                }
                Log.e("EmbyClient", "getLibraries exception: ${e.message}", e)
                Result.failure(IOException(userMsg, e))
            }
        }
    }

    suspend fun getItems(
        baseUrl: String,
        apiKey: String,
        userId: String?,
        parentId: String,
        includeItemTypes: String? = null,
        limit: Int = 60,
        sortBy: String = "DateCreated",
        sortOrder: String = "Descending",
        startIndex: Int = 0,
        years: String? = null,
        genres: String? = null
    ): Result<EmbyItemsPage> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val typesParam = if (includeItemTypes != null) "&IncludeItemTypes=$includeItemTypes" else ""
                val yearsParam = if (!years.isNullOrBlank()) "&Years=${java.net.URLEncoder.encode(years, "UTF-8")}" else ""
                val genresParam = if (!genres.isNullOrBlank()) "&Genres=${java.net.URLEncoder.encode(genres, "UTF-8")}" else ""
                val fields = "PrimaryImageAspectRatio,BasicSyncInfo,Path,UserData,Overview,ProductionYear,CommunityRating,Genres,Studios,People"
                val url = if (!userId.isNullOrBlank()) {
                    apiUrl(baseUrl, "Users/$userId/Items?ParentId=${java.net.URLEncoder.encode(parentId, "UTF-8")}&SortBy=$sortBy&SortOrder=$sortOrder&Recursive=true&Fields=$fields$typesParam$yearsParam$genresParam&Limit=$limit&StartIndex=$startIndex")
                } else {
                    apiUrl(baseUrl, "Items?ParentId=${java.net.URLEncoder.encode(parentId, "UTF-8")}&SortBy=$sortBy&SortOrder=$sortOrder&Recursive=true$typesParam$yearsParam$genresParam&Limit=$limit&StartIndex=$startIndex")
                }
                val response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(apiKey))
                        .header("X-Emby-Authorization", authHeader(apiKey))
                        .build()
                ).execute()
                val body = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${body.take(200)}"))
                }
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val items = json["Items"] as? List<Map<String, Any>> ?: emptyList()
                val totalRecordCount = (json["TotalRecordCount"] as? Number)?.toInt() ?: (startIndex + items.size)
                val result = items.mapNotNull { item -> parseItem(item) }
                Result.success(EmbyItemsPage(items = result, totalRecordCount = totalRecordCount, startIndex = startIndex))
            } catch (e: Exception) {
                Result.failure(IOException("获取项目失败: ${e.message}", e))
            }
        }
    }

    suspend fun getVideoStreamUrl(baseUrl: String, apiKey: String, itemId: String, mediaSourceId: String? = null): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val msParam = if (mediaSourceId != null) "&MediaSourceId=$mediaSourceId" else ""
                val url = apiUrl(baseUrl, "Videos/$itemId/stream?static=true&api_key=$apiKey$msParam")
                Result.success(url)
            } catch (e: Exception) {
                Result.failure(IOException("构建播放地址失败: ${e.message}", e))
            }
        }
    }

    /** 获取 PlaybackInfo（MediaSources + PlaySessionId） */
    suspend fun getPlaybackInfo(baseUrl: String, apiKey: String, userId: String, itemId: String): Result<EmbyPlaybackInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val url = apiUrl(baseUrl, "Items/$itemId/PlaybackInfo?UserId=$userId")
                val response = client.newCall(
                    Request.Builder().url(url).header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION).header("X-Emby-Authorization", authHeader(apiKey)).build()
                ).execute()
                val body = response.body?.string() ?: "{}"
                if (!response.isSuccessful) return@withContext Result.failure(IOException("PlaybackInfo HTTP ${response.code}"))
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                val mediaSourcesJson = json["MediaSources"] as? List<Map<String, Any>> ?: emptyList()
                val mediaSources = mediaSourcesJson.mapNotNull { parseMediaSource(it) }
                val playSessionId = json["PlaySessionId"] as? String ?: ""
                if (mediaSources.isEmpty()) return@withContext Result.failure(IOException("无可用播放源"))
                Result.success(EmbyPlaybackInfo(mediaSources, playSessionId))
            } catch (e: Exception) {
                Result.failure(IOException("获取播放信息失败: ${e.message}", e))
            }
        }
    }

    private fun parseMediaSource(item: Map<String, Any>): EmbyMediaSource? {
        val id = item["Id"] as? String ?: return null
        val container = item["Container"] as? String ?: "mp4"
        // 视频流信息（取第一个 Video 类型流）
        var videoCodec: String? = null
        (item["MediaStreams"] as? List<Map<String, Any>>)?.forEach { stream ->
            if ((stream["Type"] as? String) == "Video" && videoCodec == null) {
                videoCodec = stream["Codec"] as? String
            }
        }
        // 顶层码率字段名是 Bitrate（Emby/Jellyfin 都小写 r），MediaStreams 子字段是大写 R
        var bitRate = toLong(item["Bitrate"]) ?: toLong(item["BitRate"])
        // Bitrate 有时在 MediaStreams 视频流里
        if (bitRate == null && videoCodec != null) {
            (item["MediaStreams"] as? List<Map<String, Any>>)?.firstOrNull { (it["Type"] as? String) == "Video" }
                ?.let { bitRate = toLong(it["BitRate"]) ?: toLong(it["Bitrate"]) }
        }
        return EmbyMediaSource(
            id = id, container = container,
            path = item["Path"] as? String,
            supportsDirectPlay = item["SupportsDirectPlay"] as? Boolean ?: true,
            supportsDirectStream = item["SupportsDirectStream"] as? Boolean ?: true,
            supportsTranscoding = item["SupportsTranscoding"] as? Boolean ?: true,
            name = item["Name"] as? String,
            height = toInt(item["Height"]),
            width = toInt(item["Width"]),
            bitRate = bitRate,
            size = toLong(item["Size"]),
            videoCodec = videoCodec,
            mediaStreams = item["MediaStreams"] as? List<Map<String, Any>>
        )
    }

    suspend fun getResumeItems(baseUrl: String, apiKey: String, userId: String): Result<List<EmbyItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = apiUrl(baseUrl, "Users/$userId/Items/Resume?Limit=20&MediaTypes=Video&Fields=PrimaryImageAspectRatio,ProductionYear,Overview")
                val result = doGet(url, apiKey, ::parseItemsList)
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(IOException("获取继续观看失败: ${e.message}", e))
            }
        }
    }

    suspend fun getLatestItems(baseUrl: String, apiKey: String, userId: String, parentId: String? = null, limit: Int = 20, includeItemTypes: String? = null): Result<List<EmbyItem>> {
        return withContext(Dispatchers.IO) {
            try {
                // Emby 首页"最新"语义：按最近添加的内容聚合（剧集新增单集 → 剧集排前），
                // 而非 Items?SortBy=DateCreated 的按项目本身入库时间。
                val parentParam = if (!parentId.isNullOrBlank()) "&ParentId=${java.net.URLEncoder.encode(parentId, "UTF-8")}" else ""
                val typesParam = if (!includeItemTypes.isNullOrBlank()) "&IncludeItemTypes=$includeItemTypes" else ""
                val url = apiUrl(baseUrl, "Users/$userId/Items/Latest?Limit=$limit$parentParam$typesParam&Fields=PrimaryImageAspectRatio,ProductionYear,Overview,UserData,CommunityRating")
                val result = doGetDirect(url, apiKey, ::parseItemsListDirect)
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(IOException("获取最新内容失败: ${e.message}", e))
            }
        }
    }

    suspend fun getSeasonList(baseUrl: String, apiKey: String, userId: String, seriesId: String): Result<List<EmbyItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = apiUrl(baseUrl, "Shows/$seriesId/Seasons?UserId=$userId&Fields=PrimaryImageAspectRatio,Overview,ProductionYear&Limit=9999&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb")
                val result = doGet(url, apiKey, ::parseItemsList)
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(IOException("获取季列表失败: ${e.message}", e))
            }
        }
    }

    suspend fun getEpisodesInSeason(baseUrl: String, apiKey: String, userId: String, seasonId: String): Result<List<EmbyItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val fields = "PrimaryImageAspectRatio,Overview,RunTimeTicks,MediaSources,MediaStreams,People,ProviderIds,Width,Height,BitRate,UserData,SeriesInfo,IndexNumber,ParentIndexNumber"
                val url = apiUrl(baseUrl, "Users/$userId/Items?ParentId=$seasonId&Fields=$fields&SortBy=IndexNumber&SortOrder=Ascending&Limit=9999&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb")
                val result = doGet(url, apiKey, ::parseItemsList)
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(IOException("获取剧集列表失败: ${e.message}", e))
            }
        }
    }

    private fun doGet(url: String, apiKey: String, parser: (String) -> List<EmbyItem>): List<EmbyItem> {
        // 复用单例 OkHttpClient（连接池复用）
        client.newCall(
            Request.Builder().url(url)
                .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                .header("Authorization", authHeader(apiKey))
                .build()
        ).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            Log.d("EmbyClient", "doGet ${resp.code} ${url.take(100)}")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return parser(body)
        }
    }

    private fun doGetDirect(url: String, apiKey: String, parser: (String) -> List<EmbyItem>): List<EmbyItem> {
        // 复用单例 OkHttpClient（连接池复用）
        client.newCall(
            Request.Builder().url(url)
                .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                .header("Authorization", authHeader(apiKey))
                .build()
        ).execute().use { resp ->
            val body = resp.body?.string() ?: "[]"
            Log.d("EmbyClient", "doGetDirect ${resp.code} ${url.take(100)}")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return parser(body)
        }
    }


    fun getImageUrl(baseUrl: String, itemId: String, tag: String?, apiKey: String? = null, maxWidth: Int = 400, type: String = "Primary"): String? {
        if (tag.isNullOrBlank() || itemId.isNullOrBlank()) return null
        // Emby 图片 API: Items/{id}/Images/{type} (Primary, Backdrop, Logo, Banner, Thumb, etc.)
        return apiUrl(baseUrl, "Items/$itemId/Images/$type?maxWidth=$maxWidth&tag=$tag&quality=80")
    }

    fun getImageUrl(item: EmbyItem, baseUrl: String, apiKey: String? = null, maxWidth: Int = 400): String? {
        // 优先用 ImageTags，其次用 primaryImageTag
        val tag = item.imageTags?.get("Primary") ?: item.primaryImageTag
        return getImageUrl(baseUrl, item.id, tag, apiKey, maxWidth)
    }

    /** 获取背景图 URL（Backdrop）：不依赖 BackdropImageTags 的 tag，没有 tag 也可获取默认图 */
    fun getBackdropUrl(baseUrl: String, itemId: String, tag: String?, apiKey: String? = null, maxWidth: Int = 1200, index: Int = 0): String? {
        if (itemId.isNullOrBlank()) return null
        return if (tag.isNullOrBlank()) {
            // 没有 tag 时直接用不带 tag 参数的 URL，Emby 会返回第一张默认背景图
            apiUrl(baseUrl, "Items/$itemId/Images/Backdrop/$index?maxWidth=$maxWidth&quality=90")
        } else {
            apiUrl(baseUrl, "Items/$itemId/Images/Backdrop/$index?maxWidth=$maxWidth&tag=$tag&quality=90")
        }
    }

    suspend fun downloadSubtitle(
        baseUrl: String,
        apiKey: String,
        itemId: String,
        mediaSourceId: String,
        subtitleIndex: Int,
        format: String = "ass"
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val url = apiUrl(baseUrl, "Videos/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.$format?api_key=$apiKey")
                val resp = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(apiKey))
                        .build()
                ).execute()
                if (resp.isSuccessful) resp.body?.string() else null
            } catch (_: Exception) { null }
        }
    }

    suspend fun getSubtitleUrl(
        baseUrl: String,
        apiKey: String,
        itemId: String,
        mediaSourceId: String,
        subtitleIndex: Int,
        format: String = "srt"
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = apiUrl(baseUrl, "Videos/$itemId/$mediaSourceId/Subtitles/$subtitleIndex/Stream.$format?api_key=$apiKey")
                url
            } catch (_: Exception) { null }
        }
    }

    suspend fun getMediaInfo(
        baseUrl: String,
        apiKey: String,
        itemId: String,
        userId: String? = null
    ): Result<Map<String, Any>> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                // 先尝试 POST PlaybackInfo
                try {
                    val url = apiUrl(baseUrl, "Items/$itemId/PlaybackInfo?UserId=$userId&IsPlayback=true&AutoOpenLiveStream=true&reqformat=json")
                    val body = JsonObject().apply {
                        add("DeviceProfile", JsonObject().apply {
                            addProperty("MaxStreamingBitrate", 200000000)
                            add("DirectPlayProfiles", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("Type", "Video")
                                    addProperty("Container", "mp4,mkv,mov")
                                    addProperty("VideoCodec", "h264,hevc,mpeg4,mpeg2video,vp8,vp9")
                                    addProperty("AudioCodec", "aac,mp3,ac3,eac3,flac,dts,dca,opus,vorbis")
                                })
                            })
                            add("SubtitleProfiles", JsonArray().apply {
                                add(JsonObject().apply { addProperty("Format", "srt"); addProperty("Method", "External") })
                                add(JsonObject().apply { addProperty("Format", "vtt"); addProperty("Method", "External") })
                                add(JsonObject().apply { addProperty("Format", "ass"); addProperty("Method", "External") })
                            })
                        })
                    }
                    val resp = client.newCall(
                        Request.Builder()
                            .url(url)
                            .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                            .header("Authorization", authHeader(apiKey))
                            .post(body.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                    val bodyStr = resp.body?.string() ?: "{}"
                    if (resp.isSuccessful && bodyStr.isNotBlank()) {
                        @Suppress("UNCHECKED_CAST")
                        val json = gson.fromJson(bodyStr, Map::class.java) as Map<String, Any>
                        if (json.containsKey("MediaSources")) {
                            return@withContext Result.success(json)
                        }
                    }
                } catch (_: Exception) {}

                // 降级：尝试 GET Items/{itemId} 
                val fallbackUrl = if (userId != null) {
                    apiUrl(baseUrl, "Users/$userId/Items/$itemId?AddMediaSources=true")
                } else {
                    apiUrl(baseUrl, "Items/$itemId?AddMediaSources=true")
                }
                val resp = client.newCall(
                    Request.Builder()
                        .url(fallbackUrl)
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(apiKey))
                        .build()
                ).execute()
                val bodyStr = resp.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(bodyStr, Map::class.java) as Map<String, Any>
                Result.success(json)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
         * 详情页专用：真实调 PlaybackInfo 拿 MediaSources/MediaStreams 全量
         * 返回 EmbyItem（只是媒体源/流信息，调用方用它覆盖详情的 mediaSources/videoStreamInfo）
         */
        suspend fun getPlaybackInfoDetail(
            baseUrl: String,
            apiKey: String,
            userId: String,
            itemId: String
        ): Result<EmbyItem> {
            return withContext(Dispatchers.IO) {
                try {
                    val url = apiUrl(baseUrl, "Items/$itemId/PlaybackInfo?UserId=$userId&IsPlayback=true&AutoOpenLiveStream=true")
                    val resp = client.newCall(
                        Request.Builder()
                            .url(url)
                            .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                            .header("Authorization", authHeader(apiKey))
                            .build()
                    ).execute()
                    val body = resp.body?.string() ?: "{}"
                    if (!resp.isSuccessful) return@withContext Result.failure(IOException("PlaybackInfo HTTP ${resp.code}"))
                    @Suppress("UNCHECKED_CAST")
                    val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                    // 从 PlaybackInfo 结构提取媒体源信息（顶层 MediaSources）
                    val mediaSources = (json["MediaSources"] as? List<Map<String, Any>>)?.mapNotNull { parseMediaSource(it) }
                    // 提取第一个视频流的流信息（mediaStreams 已是 List<Map>，不是 Map）
                    val videoStreamInfo = mediaSources?.firstOrNull()?.let { ms ->
                        val streams = ms.mediaStreams ?: emptyList()
                        val videoStream = streams.firstOrNull { it["Type"] == "Video" }
                        if (videoStream == null) null
                        else EmbyStreamInfo(
                            codec = videoStream["Codec"] as? String,
                            width = toInt(videoStream["Width"]),
                            height = toInt(videoStream["Height"]),
                            bitRate = toLong(videoStream["BitRate"]) ?: toLong(videoStream["Bitrate"]),
                            frameRate = toFloat(videoStream["RealFrameRate"]) ?: toFloat(videoStream["AverageFrameRate"]),
                            profile = videoStream["Profile"] as? String,
                            audioStreams = streams.filter { it["Type"] == "Audio" }.mapNotNull { it["Codec"] as? String }.distinct(),
                            subtitleStreams = streams.count { it["Type"] == "Subtitle" }
                        )
                    }
                    // 组装：只填媒体源字段，详情基础字段不动
                    val item = EmbyItem(
                        id = itemId, name = "", type = "",
                        isFolder = false, isVideo = true,
                        mediaSources = mediaSources,
                        videoStreamInfo = videoStreamInfo
                    )
                    Result.success(item)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

        /**
         * 获取片头标记区间（Emby/Jellyfin API: Users/{userId}/Items/{itemId}/Intros）
         * 返回 (introStartMs, introEndMs) 列表，空列表表示无片头标记
         */
        suspend fun getIntroMarkers(
            baseUrl: String,
            apiKey: String,
            userId: String,
            itemId: String
        ): Result<List<Pair<Long, Long>>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = apiUrl(baseUrl, "Users/$userId/Items/$itemId/Intros")
                val resp = client.newCall(
                    Request.Builder().url(url)
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(apiKey))
                        .build()
                ).execute()
                val bodyStr = resp.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(bodyStr, Map::class.java) as? Map<String, Any> ?: emptyMap()
                val items = (json["Items"] as? List<Map<String, Any>>) ?: emptyList()
                val markers = items.mapNotNull { item ->
                    val startTicks = (item["IntroStart"] as? Number)?.toLong()
                    val endTicks = (item["IntroEnd"] as? Number)?.toLong()
                    if (startTicks != null && endTicks != null) {
                        (startTicks / 10_000) to (endTicks / 10_000) // ticks→ms
                    } else null
                }
                Result.success(markers)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun reportPlaybackStart(
        baseUrl: String,
        apiKey: String,
        itemId: String,
        playSessionId: String,
        mediaSourceId: String = itemId,
        positionTicks: Long = 0L
    ) {
        withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val url = apiUrl(baseUrl, "Sessions/Playing")
                val body = JsonObject().apply {
                    addProperty("ItemId", itemId)
                    addProperty("MediaSourceId", mediaSourceId)
                    addProperty("CanSeek", true)
                    add("QueueableMediaTypes", JsonArray().apply { add("Video") })
                    addProperty("PositionTicks", positionTicks)
                    addProperty("IsPaused", false)
                    addProperty("IsMuted", false)
                    addProperty("PlaybackMethod", "DirectPlay")
                    addProperty("PlaySessionId", playSessionId)
                }
                val response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(apiKey))
                        .post(body.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute()
                Log.d("EmbyClient", "reportPlaybackStart: HTTP ${response.code} $itemId $playSessionId")
                response.close()
            } catch (e: Exception) {
                Log.e("EmbyClient", "reportPlaybackStart failed: ${e.message}")
            }
        }
    }

    suspend fun reportPlaybackProgress(
            baseUrl: String,
            apiKey: String,
            itemId: String,
            playSessionId: String,
            positionTicks: Long,
            isPaused: Boolean = false,
            mediaSourceId: String = itemId
        ) {
            withContext(Dispatchers.IO) {
                try {
                    // 复用单例 OkHttpClient（连接池复用）
                    val url = apiUrl(baseUrl, "Sessions/Playing/Progress")
                    val body = JsonObject().apply {
                        addProperty("ItemId", itemId)
                        addProperty("MediaSourceId", mediaSourceId)
                        addProperty("PositionTicks", positionTicks)
                        addProperty("IsPaused", isPaused)
                        addProperty("IsMuted", false)
                        addProperty("CanSeek", true)
                        addProperty("PlaybackMethod", "DirectPlay")
                        addProperty("PlaySessionId", playSessionId)
                    }
                    val response = client.newCall(
                        Request.Builder()
                            .url(url)
                            .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                            .header("Authorization", authHeader(apiKey))
                            .post(body.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                    Log.d("EmbyClient", "reportPlaybackProgress: HTTP ${response.code} $positionTicks")
                    response.close()
                } catch (e: Exception) {
                    Log.e("EmbyClient", "reportPlaybackProgress failed: ${e.message}")
                }
            }
        }

    suspend fun reportPlaybackStopped(
        baseUrl: String,
        apiKey: String,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        mediaSourceId: String = itemId
    ) {
        withContext(Dispatchers.IO) {
        try {
            // 复用单例 OkHttpClient（连接池复用）
            val url = apiUrl(baseUrl, "Sessions/Playing/Stopped")
            val body = JsonObject().apply {
                addProperty("ItemId", itemId)
                addProperty("MediaSourceId", mediaSourceId)
                addProperty("PositionTicks", positionTicks)
                addProperty("PlaySessionId", playSessionId)
            }
            val response = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                    .header("Authorization", authHeader(apiKey))
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            Log.d("EmbyClient", "reportPlaybackStopped: HTTP ${response.code} $positionTicks")
            response.close()
        } catch (e: Exception) {
            Log.e("EmbyClient", "reportPlaybackStopped failed: ${e.message}")
        }
        }
    }

    suspend fun markPlayed(baseUrl: String, apiKey: String, userId: String, itemId: String): Result<Unit> {
    return withContext(Dispatchers.IO) {
        try {
        // 复用单例 OkHttpClient（连接池复用）
        val url = apiUrl(baseUrl, "Users/$userId/PlayedItems/$itemId")
        val resp = client.newCall(Request.Builder().url(url).post(okhttp3.RequestBody.create(null, "")).header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION).build()).execute()
        if (resp.isSuccessful) Result.success(Unit) else Result.failure(IOException("HTTP ${resp.code}"))
        } catch (e: Exception) { Result.failure(e) }
    }
    }

    /** 获取简介（旧版 getItemDetail 的兼容名） */
    suspend fun getOverview(baseUrl: String, apiKey: String, itemId: String, userId: String? = null): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val url = if (userId != null) {
                    apiUrl(baseUrl, "Users/$userId/Items/$itemId/Overview")
                } else {
                    apiUrl(baseUrl, "Items/$itemId/Overview")
                }
                val response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(apiKey))
                        .build()
                ).execute()
                val body = response.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val json = com.google.gson.Gson().fromJson(body, Map::class.java) as Map<String, Any>
                val overview = json["Overview"] as? String ?: ""
                Result.success(overview)
            } catch (e: Exception) {
                Result.failure(IOException("获取详情失败: ${e.message}", e))
            }
        }
    }

    /** 搜索 */
    suspend fun search(
        baseUrl: String,
        apiKey: String,
        userId: String?,
        query: String,
        typeFilter: String? = null
    ): Result<List<EmbyItem>> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val includeTypes = if (typeFilter != null) typeFilter else "Movie,Series,Episode,Video"
                val url = if (userId != null) {
                    apiUrl(baseUrl, "Users/$userId/Items?SearchTerm=$encodedQuery&Limit=200&Recursive=true&IncludeItemTypes=$includeTypes&Fields=PrimaryImageAspectRatio,Overview,UserData,ProductionYear,CommunityRating")
                } else {
                    apiUrl(baseUrl, "Items?SearchTerm=$encodedQuery&Limit=200&Recursive=true")
                }
                val response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(apiKey))
                        .build()
                ).execute()
                val body = response.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val json = com.google.gson.Gson().fromJson(body, Map::class.java) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val items = json["Items"] as? List<Map<String, Any>> ?: emptyList()
                val result = items.mapNotNull { item -> parseItem(item) }
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(IOException("搜索失败: ${e.message}", e))
            }
        }
    }

    /** 标记已看 */
    suspend fun markUnplayed(baseUrl: String, apiKey: String, userId: String, itemId: String): Result<Unit> {
    return withContext(Dispatchers.IO) {
    try {
    // 复用单例 OkHttpClient（连接池复用）
    val url = apiUrl(baseUrl, "Users/$userId/PlayedItems/$itemId")
    val resp = client.newCall(Request.Builder().url(url).delete().header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION).build()).execute()
    if (resp.isSuccessful) Result.success(Unit) else Result.failure(IOException("HTTP ${resp.code}"))
    } catch (e: Exception) { Result.failure(e) }
    }
    }

    /** 收藏/取消收藏 */
    suspend fun updateFavorite(baseUrl: String, apiKey: String, userId: String, itemId: String, favorite: Boolean): Result<Unit> {
    return withContext(Dispatchers.IO) {
    try {
    // 复用单例 OkHttpClient（连接池复用）
    val url = apiUrl(baseUrl, "Users/$userId/FavoriteItems/$itemId")
    val resp = client.newCall(
        if (favorite) Request.Builder().url(url).post(okhttp3.RequestBody.create(null, "")).header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION).build()
        else Request.Builder().url(url).delete().header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION).build()
    ).execute()
    if (resp.isSuccessful) Result.success(Unit) else Result.failure(IOException("HTTP ${resp.code}"))
    } catch (e: Exception) { Result.failure(e) }
    }
    }

    /** 获取收藏列表 */
    suspend fun getFavoriteItems(
        baseUrl: String,
        apiKey: String,
        userId: String,
        limit: Int = 20,
        startIndex: Int = 0
    ): Result<EmbyItemsPage> {
        return withContext(Dispatchers.IO) {
            try {
                val fields = "PrimaryImageAspectRatio,BasicSyncInfo,Path,UserData,Overview,ProductionYear,CommunityRating,Genres"
                val url = apiUrl(baseUrl, "Users/$userId/Items?FavoritesOnly=true&SortBy=DateCreated&SortOrder=Descending&Recursive=true&Fields=$fields&Limit=$limit&StartIndex=$startIndex&IncludeItemTypes=Movie,Series,Episode")
                val response = client.newCall(
                    Request.Builder().url(url).header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION).header("Authorization", authHeader(apiKey)).build()
                ).execute()
                val body = response.body?.string() ?: "{}"
                if (!response.isSuccessful) return@withContext Result.failure(IOException("HTTP ${response.code}"))
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val items = json["Items"] as? List<Map<String, Any>> ?: emptyList()
                val totalRecordCount = (json["TotalRecordCount"] as? Number)?.toInt() ?: items.size
                val result = items.mapNotNull { parseItem(it) }
                Result.success(EmbyItemsPage(result, totalRecordCount, startIndex))
            } catch (e: Exception) {
                Result.failure(IOException("获取收藏失败: ${e.message}", e))
            }
        }
    }

    /** 获取相似推荐（Emby /Items/{id}/Similar） */
    suspend fun getSimilarItems(baseUrl: String, apiKey: String, userId: String, itemId: String, limit: Int = 12): Result<List<EmbyItem>> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val url = apiUrl(baseUrl, "Items/$itemId/Similar?userId=$userId&limit=$limit&Fields=PrimaryImageAspectRatio,Overview,UserData,ProductionYear,CommunityRating,BackdropImageTags")
                val resp = client.newCall(Request.Builder().url(url).header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION).build()).execute()
                val body = resp.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val items = json["Items"] as? List<Map<String, Any>> ?: emptyList()
                val result = items.mapNotNull { parseItem(it) }
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(IOException("推荐失败: ${e.message}", e))
            }
        }
    }

    /** 获取预告片（Emby/Jellyfin: Users/{userId}/Items/{itemId}/Trailers 返回 Trailer 类型 item，可直接播） */
    suspend fun getTrailers(baseUrl: String, apiKey: String, userId: String, itemId: String): Result<List<EmbyItem>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = apiUrl(baseUrl, "Users/$userId/Items/$itemId/Trailers?Fields=PrimaryImageAspectRatio,Overview,RunTimeTicks,ProductionYear&EnableImageTypes=Primary,Backdrop,Thumb")
                val resp = client.newCall(Request.Builder().url(url).header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION).build()).execute()
                val body = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val items = json["Items"] as? List<Map<String, Any>> ?: emptyList()
                // Emby 可能返回普通视频类型的本地预告片；过滤真正的 trailer（无 trailer 时是空数组）
                val result = items.mapNotNull { parseItem(it) }.filter { it.type == "Trailer" || it.type == "Video" || it.type == "Movie" }
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(IOException("获取预告片失败: ${e.message}", e))
            }
        }
    }

    /** 获取详情（含演员、类型等） */
    suspend fun getItemDetail(baseUrl: String, apiKey: String, userId: String, itemId: String): Result<EmbyItem> {
        return withContext(Dispatchers.IO) {
        try {
        // 复用单例 OkHttpClient（连接池复用）
        val fields = "PrimaryImageAspectRatio,Overview,ProductionYear,CommunityRating,Genres,Studios,People,UserData,SeriesInfo,ExternalUrls,BackdropImageTags,MediaSources,MediaStreams,Width,Height,BitRate,Size,Container,DateCreated,PremiereDate,ProviderIds,Taglines,OfficialRating,RunTimeTicks,Path"
        val url = apiUrl(baseUrl, "Users/$userId/Items/$itemId?Fields=$fields&EnableImageTypes=Primary,Backdrop,Thumb,Logo&ImageTypeLimit=1")
        val resp = client.newCall(Request.Builder().url(url).header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION).build()).execute()
        val body = resp.body?.string() ?: "{}"
        if (!resp.isSuccessful) return@withContext Result.failure(IOException("HTTP ${resp.code}"))
        @Suppress("UNCHECKED_CAST")
        val item = gson.fromJson(body, Map::class.java) as Map<String, Any>
        val parsed = parseItem(item)
        if (parsed != null) Result.success(parsed) else Result.failure(IOException("解析失败"))
        } catch (e: Exception) { Result.failure(e) }
        }
        }

    /** 获取演员的作品列表：按 PersonIds 过滤电影/剧集，按年份倒序 */
    suspend fun getPersonWorks(
        baseUrl: String,
        apiKey: String,
        userId: String,
        personId: String
    ): Result<List<EmbyItem>> {
        return withContext(Dispatchers.IO) {
            try {
                // 复用单例 OkHttpClient（连接池复用）
                val fields = "PrimaryImageAspectRatio,BasicSyncInfo,Path,UserData,Overview,ProductionYear,CommunityRating,Genres,Studios,People"
                val url = apiUrl(baseUrl, "Users/$userId/Items?PersonIds=${java.net.URLEncoder.encode(personId, "UTF-8")}&IncludeItemTypes=Movie,Series&Recursive=true&Fields=$fields&SortBy=ProductionYear&SortOrder=Descending&Limit=100")
                val response = client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("X-Emby-Token", apiKey).header("X-Emby-Client", CLIENT_NAME).header("X-Emby-Device-Name", DEVICE_NAME).header("X-Emby-Device-Id", DEVICE_ID).header("X-Emby-Client-Version", VERSION)
                        .header("Authorization", authHeader(apiKey))
                        .header("X-Emby-Authorization", authHeader(apiKey))
                        .build()
                ).execute()
                val body = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}: ${body.take(200)}"))
                }
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                @Suppress("UNCHECKED_CAST")
                val items = json["Items"] as? List<Map<String, Any>> ?: emptyList()
                val result = items.mapNotNull { item -> parseItem(item) }
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(IOException("获取演员作品失败: ${e.message}", e))
            }
        }
    }

    // ====== 增强的 parseItem ======

    private fun parseItemsList(json: String): List<EmbyItem> {
    @Suppress("UNCHECKED_CAST")
    val jsonObj = com.google.gson.Gson().fromJson(json, Map::class.java) as Map<String, Any>
    @Suppress("UNCHECKED_CAST")
    val items = jsonObj["Items"] as? List<Map<String, Any>> ?: emptyList()
    return items.mapNotNull { item -> parseItem(item) }
    }

    private fun parseItemsListDirect(json: String): List<EmbyItem> {
    @Suppress("UNCHECKED_CAST")
    val items = com.google.gson.Gson().fromJson(json, List::class.java) as? List<Map<String, Any>> ?: emptyList()
    return items.mapNotNull { item -> parseItem(item) }
    }

    private fun parseItem(item: Map<String, Any>): EmbyItem? {
        val id = item["Id"] as? String ?: return null
        val type = item["Type"] as? String ?: ""
        @Suppress("UNCHECKED_CAST")
        return EmbyItem(
        id = id,
        name = item["Name"] as? String ?: "未命名",
        type = type,
        isFolder = item["IsFolder"] as? Boolean ?: (type == "Series" || type == "Season" || type == "BoxSet" || type == "Folder"),
        isVideo = (item["IsVideo"] as? Boolean) ?: (type == "Video" || type == "Movie" || type == "Episode"),
        runTimeTicks = toLong(item["RunTimeTicks"]),
        imageTags = item["ImageTags"] as? Map<String, String>,
        parentId = item["ParentId"] as? String,
        primaryImageTag = item["PrimaryImageTag"] as? String,
        indexNumber = toInt(item["IndexNumber"]),
            parentIndexNumber = toInt(item["ParentIndexNumber"]),
            overview = item["Overview"] as? String,
        productionYear = toInt(item["ProductionYear"]),
        communityRating = toFloat(item["CommunityRating"]),
        seriesName = item["SeriesName"] as? String,
        seriesId = item["SeriesId"] as? String,
        seasonName = item["SeasonName"] as? String,
        seasonId = item["SeasonId"] as? String,
        genres = (item["Genres"] as? List<*>)?.mapNotNull { it?.toString() },
        studios = (item["Studios"] as? List<Map<String, Any>>)?.mapNotNull { it["Name"] as? String },
        officialRating = item["OfficialRating"] as? String,
        people = (item["People"] as? List<Map<String, Any>>)?.mapNotNull { p ->
        EmbyPerson(
            id = p["Id"] as? String,
            name = p["Name"] as? String ?: return@mapNotNull null,
            role = p["Role"] as? String,
            type = p["Type"] as? String,
            primaryImageTag = p["PrimaryImageTag"] as? String
        )
        },
        backdropImageTags = (item["BackdropImageTags"] as? List<*>)?.mapNotNull { it?.toString() },
        providerIds = (item["ProviderIds"] as? Map<String, Any>)?.mapValues { it.value.toString() },
        externalUrls = (item["ExternalUrls"] as? List<Map<String, Any>>)?.mapNotNull { u ->
            val name = u["Name"] as? String ?: return@mapNotNull null
            val url = u["Url"] as? String ?: return@mapNotNull null
            name to url
        },
        userData = (item["UserData"] as? Map<String, Any>)?.let {
            EmbyUserData(
                unwatchedCount = toInt(it["UnwatchedCount"]),
                unplayedItemCount = toInt(it["UnplayedItemCount"]),
                played = it["Played"] as? Boolean,
                playbackPositionTicks = toLong(it["PlaybackPositionTicks"]),
                isFavorite = it["IsFavorite"] as? Boolean,
                playedPercentage = (it["PlayedPercentage"] as? Number)?.toDouble(),
                lastPlayedDate = it["LastPlayedDate"] as? String
            )
        },
        // 解析 MediaSources（详情页媒体源/分辨率/编码）
        mediaSources = (item["MediaSources"] as? List<Map<String, Any>>)?.mapNotNull { parseMediaSource(it) },
        // 解析 MediaStreams 中的第一个视频流概要
        videoStreamInfo = (item["MediaStreams"] as? List<Map<String, Any>>)?.let { streams ->
            val videoStream = streams.firstOrNull { it["Type"] == "Video" }
            if (videoStream == null) null
            else EmbyStreamInfo(
                codec = videoStream["Codec"] as? String,
                width = toInt(videoStream["Width"]),
                height = toInt(videoStream["Height"]),
                bitRate = toLong(videoStream["BitRate"]) ?: toLong(videoStream["Bitrate"]),
                // 帧率字段是 AverageFrameRate/RealFrameRate（不是 FrameRate）
                frameRate = toFloat(videoStream["RealFrameRate"]) ?: toFloat(videoStream["AverageFrameRate"]),
                profile = videoStream["Profile"] as? String,
                audioStreams = streams.filter { it["Type"] == "Audio" }.mapNotNull { it["Codec"] as? String }.distinct(),
                subtitleStreams = streams.count { it["Type"] == "Subtitle" }
            )
        }
        )
    }
}
