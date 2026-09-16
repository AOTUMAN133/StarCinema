package com.starcinema.danmu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 弹弹Play (dandanplay) API 客户端：
 * 1. match：按文件名匹配视频 → 得到 episodeId
 * 2. comment：按 episodeId 下载弹幕 XML → 解析成 [DanmuItem]
 *
 * API 文档: https://github.com/kaedei/dandanplay-libraryindex
 *
 * 🔴 base 走 AfuseKtV 反编译得到的中转代理 (danmaku.868161.xyz):
 *    直连 api.dandanplay.net + 公开 AppId 会被全局限流 (errorCode:429 配额上限, 实测返空)
 *    代理在服务端代转, 用服务商自己的配额 → 搜索/匹配/弹幕全通
 */
class DanmuApiClient {

    private val base = "https://danmaku.868161.xyz/KwOlfILcdh9KsktZI4z5yOG9H8Ts4e6wTuiQfKkjovSwrI1jXH4iaWSaY5sljctl"

    // 🔴 伪装 AfuseKtV 的用户代理 (反编译 Api.f16870a 同款) — 代理按 UA 区分客户端, 伪装可降低被风控/封源概率
    private val UA = "AfuseKt/(Linux;Android Release)Player-TV"

    companion object {
        // 弹弹play AppId/AppSecret（从 AfuseKtV 反编译获取，签名验证模式）
        private const val APP_ID = "gxzpsjh8ik"
        private const val APP_SECRET = "4SvO1A7Ot3eULHAUWzgGbP4j9ZewGDXy"
    }

    /**
     * 弹弹play v2 API 签名头。
     * X-Signature = Base64_NO_WRAP( SHA-256(appId + 秒时间戳 + path + appSecret) )
     * path 为 API 路径（不含域名与 query），如 /api/v2/match
     */
    private fun headers(path: String): Map<String, String> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val raw = APP_ID + ts + path + APP_SECRET
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val sign = java.util.Base64.getEncoder().encodeToString(digest)
        return mapOf(
            "X-AppId" to APP_ID,
            "X-Timestamp" to ts,
            "X-Signature" to sign
        )
    }

    /** 从 Emby 服务器 Danmu 插件拉弹幕（优先；接口同 RodelQt 已验证：/api/danmu/{itemId}?option=GetJsonById） */
    suspend fun fetchServerDanmu(baseUrl: String, apiKey: String, itemId: String): Result<List<DanmuItem>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/api/danmu/$itemId?option=GetJsonById"
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("X-Emby-Token", apiKey)
                    .header("User-Agent", UA)
                    .build()
                val resp = okhttp3.OkHttpClient().newCall(req).execute()
                val bodyStr = resp.body?.string() ?: return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
                val json = JSONObject(bodyStr)
                val dataArr = json.optJSONArray("data") ?: return@withContext Result.success(emptyList())
                val items = mutableListOf<DanmuItem>()
                for (i in 0 until dataArr.length()) {
                    val src = dataArr.getJSONObject(i)
                    val evs = src.optJSONArray("danmuEvents") ?: continue
                    for (j in 0 until evs.length()) {
                        val e = evs.getJSONObject(j)
                        val text = e.optString("m", "")
                        if (text.isBlank()) continue
                        val p = e.optString("p", "")
                        // 服务器 Danmu 插件 p 格式: time,type,size,color,... (实测 "0.00000,1,25,16777215,...")
                        // 🔴 颜色在 index=3! 之前误取 index=2(size=25) → 近黑色弹幕看不见 = "已加载但不显示"的根因
                        val parts = p.split(",")
                        val timeMs = (parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0) * 1000
                        val type = parts.getOrNull(1)?.toIntOrNull() ?: 1
                        var color = 0xFFFFFFFF.toInt()
                        try {
                            val c = parts.getOrNull(3)?.toIntOrNull()
                            if (c != null && c in 0..0xFFFFFF) color = 0xFF000000.toInt() or c
                        } catch (_: Exception) {}
                        items += DanmuItem(timeMs.toLong(), text, color, type)
                    }
                }
                Result.success(items)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 匹配视频：fileName 如 "my.mp4" / "my.S01E02.mp4" */
    suspend fun match(fileName: String): Result<List<DanmuMatchResult>> = withContext(Dispatchers.IO) {
        try {
            val url = "$base/api/v2/match"
            val body = JSONObject().apply {
                put("fileName", fileName)
                put("fileHash", "")
                put("fileSize", 0)
                put("videoDuration", 0)
                put("matchMode", "hashAndFileName")
            }.toString()
            val req = okhttp3.Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), body))
                .header("User-Agent", UA)
                .apply { headers("/api/v2/match").forEach { (k, v) -> header(k, v) } }
                .build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            val json = JSONObject(bodyStr)
            val matches = json.getJSONArray("matches")
            val list = mutableListOf<DanmuMatchResult>()
            for (i in 0 until matches.length()) {
                val m = matches.getJSONObject(i)
                list += DanmuMatchResult(
                    episodeId = m.getString("episodeId"),
                    animeTitle = m.optString("animeTitle"),
                    episodeTitle = m.optString("episodeTitle")
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 下载弹幕 XML 并解析 */
    suspend fun downloadComments(episodeId: String): Result<List<DanmuItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "$base/api/v2/comment/$episodeId"
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .apply { headers("/api/v2/comment/$episodeId").forEach { (k, v) -> header(k, v) } }
                .build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val bodyStr = resp.body?.string() ?: return@withContext Result.failure(Exception("HTTP ${resp.code}: 空响应"))
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            val json = JSONObject(bodyStr)
            val arr = json.getJSONArray("comments")
            val items = mutableListOf<DanmuItem>()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val p = c.optString("p", "") // "时间,类型,颜色,uid" 弹幕样式字段
                val text = c.optString("m", "")
                if (text.isBlank()) continue
                val parts = p.split(",")
                val timeMs = (parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0) * 1000
                val type = parts.getOrNull(1)?.toIntOrNull() ?: 1
                var color = 0xFFFFFFFF.toInt()
                try {
                    val colorDecimal = parts.getOrNull(2)?.toIntOrNull()
                    if (colorDecimal != null && colorDecimal in 0..0xFFFFFF) {
                        color = 0xFF000000.toInt() or colorDecimal
                    }
                } catch (_: Exception) {}
                items += DanmuItem(timeMs.toLong(), text, color, type)
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 搜索番剧：keyword 如 "进击的巨人" */
    suspend fun searchAnime(keyword: String): Result<List<DanmuAnime>> = withContext(Dispatchers.IO) {
        try {
            val url = "$base/api/v2/search/anime?keyword=${enc(keyword)}&type="
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA)
                .apply { headers("/api/v2/search/anime").forEach { (k, v) -> header(k, v) } }
                .build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            val json = JSONObject(bodyStr)
            val arr = json.getJSONArray("animes")
            val list = mutableListOf<DanmuAnime>()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                list += DanmuAnime(
                    animeId = a.getString("animeId"),
                    title = a.optString("animeTitle"),
                    episodes = a.optInt("episodeCount", 0)
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 获取番剧剧集列表（代理不支持 /anime/{id}/episodes(404)，AfuseKtV 用 /bangumi/{id}） */
    suspend fun getEpisodes(animeId: String): Result<List<DanmuEpisode>> = withContext(Dispatchers.IO) {
        try {
            val url = "$base/api/v2/bangumi/$animeId"
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA)
                .apply { headers("/api/v2/bangumi/$animeId").forEach { (k, v) -> header(k, v) } }
                .build()
            val resp = okhttp3.OkHttpClient().newCall(req).execute()
            val bodyStr = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            val json = JSONObject(bodyStr)
            val bg = json.optJSONObject("bangumi") ?: return@withContext Result.success(emptyList())
            val arr = bg.optJSONArray("episodes") ?: return@withContext Result.success(emptyList())
            val list = mutableListOf<DanmuEpisode>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                list += DanmuEpisode(
                    episodeId = e.getString("episodeId"),
                    title = e.optString("episodeTitle")
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** URL 编码 */
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}