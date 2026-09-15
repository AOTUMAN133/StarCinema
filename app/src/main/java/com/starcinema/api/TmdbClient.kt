package com.starcinema.api

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom

/** TMDB 详情补全信息 */
data class TmdbDetail(
    val tmdbId: String,
    /** 认证分级（releases 中美国/中国优先） */
    val certification: String? = null,
    /** 评分影视（vote_average 0-10） */
    val voteAverage: Double? = null,
    val voteCount: Int? = null,
    /** 发行日期 */
    val releaseDate: String? = null,
    /** 演员列表（cast，按顺序） */
    val cast: List<TmdbCast> = emptyList(),
    /** 制片公司 */
    val companies: List<String> = emptyList(),
    /** 简介兜底 */
    val overview: String? = null,
    /** 海报路径（TMDB URL 后段） */
    val posterPath: String? = null,
    /** 背景图路径 */
    val backdropPath: String? = null
)

/** TMDB 演员 */
data class TmdbCast(
    val id: Int? = null,
    val name: String,
    val character: String? = null,
    val profilePath: String? = null,
    val order: Int = 0
)

/**
 * TheMovieDB 补全服务：详情页演员/分级/发行日期/制片公司
 * 仅当 Emby ProviderIds 里有 Tmdb id 时才使用（Emby 没有的字段全靠这里补）
 */
class TmdbClient(
    private val apiKey: String = "50a2f49ef7814fbf42c95e92fa3f5cf5"
) {
    private val gson = Gson()

    private val client: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0])
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * 获取电影/剧集详情补全
     * @param tmdbId 来自 Emby ProviderIds.Tmdb
     * @param isMovie true=电影 /movie/{id}，false=剧集 /tv/{id}
     */
    suspend fun getDetail(tmdbId: String, isMovie: Boolean): Result<TmdbDetail> {
        return withContext(Dispatchers.IO) {
            try {
                val type = if (isMovie) "movie" else "tv"
                val url = "https://api.themoviedb.org/3/$type/$tmdbId?api_key=$apiKey&language=zh-CN&append_to_response=casts,releases,release_dates,credits"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                if (!resp.isSuccessful) return@withContext Result.failure(IOException("TMDB HTTP ${resp.code}"))
                val body = resp.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                val parsed = parseDetail(json, tmdbId, isMovie)
                Result.success(parsed)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** 搜索 TMDB 电影/剧集（按名称+年份），返回第一个结果的 tmdbId */
    suspend fun search(tmdbIdOrName: String, year: Int?, isMovie: Boolean): Result<String?> {
        return withContext(Dispatchers.IO) {
            try {
                val type = if (isMovie) "movie" else "tv"
                val query = java.net.URLEncoder.encode(tmdbIdOrName, "UTF-8")
                var url = "https://api.themoviedb.org/3/search/$type?api_key=$apiKey&language=zh-CN&query=$query"
                if (year != null) url += "&year=$year"
                val resp = client.newCall(Request.Builder().url(url).build()).execute()
                if (!resp.isSuccessful) return@withContext Result.success(null)
                val body = resp.body?.string() ?: "{}"
                @Suppress("UNCHECKED_CAST")
                val json = gson.fromJson(body, Map::class.java) as Map<String, Any>
                val results = (json["results"] as? List<Map<String, Any>>).orEmpty()
                val id = results.firstOrNull()?.get("id")?.toString()
                Result.success(id)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDetail(json: Map<String, Any>, tmdbId: String, isMovie: Boolean): TmdbDetail {
        // 分级：releases → results[].release_dates[].certification（优先 US，其次 CN）
        var certification: String? = null
        val releases = json["release_dates"] as? Map<String, Any>
        val releaseResults = releases?.get("results") as? List<Map<String, Any>>
        val us = releaseResults?.firstOrNull { it["iso_3166_1"] == "US" }
        val cn = releaseResults?.firstOrNull { it["iso_3166_1"] == "CN" }
        val certList = (us ?: cn)?.get("release_dates") as? List<Map<String, Any>>
        certification = certList?.firstNotNullOfOrNull { it["certification"] as? String }?.takeIf { it.isNotBlank() }

        // 发行日期
        val releaseDate = (json["release_date"] as? String) ?: (json["first_air_date"] as? String)

        // 评分
        val voteAverage = (json["vote_average"] as? Number)?.toDouble()
        val voteCount = (json["vote_count"] as? Number)?.toInt()

        // 制片公司
        val companies = (json["production_companies"] as? List<Map<String, Any>>)
            ?.mapNotNull { it["name"] as? String }.orEmpty()

        // 演员：credits.cast 或 casts.cast（append_to_response 两种命名都可能）
        var castList = (json["credits"] as? Map<String, Any>)?.get("cast") as? List<Map<String, Any>>
        if (castList == null) {
            castList = (json["casts"] as? Map<String, Any>)?.get("cast") as? List<Map<String, Any>>
        }
        val cast = castList.orEmpty().mapNotNull { c ->
            val name = c["name"] as? String ?: return@mapNotNull null
            TmdbCast(
                id = (c["id"] as? Number)?.toInt(),
                name = name,
                character = c["character"] as? String,
                profilePath = c["profile_path"] as? String,
                order = (c["order"] as? Number)?.toInt() ?: 0
            )
        }.sortedBy { it.order }.take(20)

        return TmdbDetail(
            tmdbId = tmdbId,
            certification = certification,
            voteAverage = voteAverage,
            voteCount = voteCount,
            releaseDate = releaseDate,
            cast = cast,
            companies = companies,
            overview = json["overview"] as? String,
            posterPath = json["poster_path"] as? String,
            backdropPath = json["backdrop_path"] as? String
        )
    }

    companion object {
        /** TMDB 图片 URL 拼接 */
        fun imageUrl(path: String?, size: String = "w342"): String? {
            if (path.isNullOrBlank()) return null
            return "https://image.tmdb.org/t/p/$size$path"
        }

        /** Emby 详情 only：从 ProviderIds 取 Tmdb id（可能同时有 Tmdb/Imdb/YoukuID） */
        fun tmdbIdFrom(providerIds: Map<String, String>?): String? {
            return providerIds?.get("Tmdb")?.takeIf { it.isNotBlank() }
        }
    }
}