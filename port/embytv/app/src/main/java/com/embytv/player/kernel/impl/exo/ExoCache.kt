package com.embytv.player.kernel.impl.exo

import android.content.Context
import androidx.media3.database.ExoDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Exo 内核磁盘缓存（移植自滴滴助手/SynoManager 的 ExoCache，方案同源）。
 *
 * 单例 SimpleCache：
 *  - 目录: cacheDir/exo_video_cache
 *  - 淘汰策略: LRU（LeastRecentlyUsedCacheEvictor），上限默认 256MB（与 MPV 缓存容量共用设置）
 *  - 开关由 applyPrefs 控制（默认开启，复用 player_cache_size_mb 设置）
 *
 * 用法（ExoMediaSourceHelper 内）：
 *  ```
 *  val factory = if (ExoCache.isEnabled()) {
 *      CacheDataSource.Factory()
 *          .setCache(ExoCache.get(context))
 *          .setUpstreamDataSourceFactory(baseFactory)
 *  } else baseFactory
 *  ```
 */
object ExoCache {

    private const val CACHE_DIR = "exo_video_cache"
    private const val DEFAULT_MAX_MB = 256

    @Volatile
    private var simpleCache: SimpleCache? = null

    private var enabled = true
    private var maxBytes = DEFAULT_MAX_MB * 1024L * 1024L

    /** 缓存是否启用 */
    fun isEnabled(): Boolean = enabled

    /** 启用/停用缓存。停用时释放句柄但不删除已缓存文件 */
    fun setEnabled(enable: Boolean) {
        enabled = enable
        if (!enable) release()
    }

    /** 缓存上限（MB），改变上限后重建 evictor（保留已缓存数据） */
    fun setMaxMb(mb: Int) {
        val newBytes = (if (mb <= 0) DEFAULT_MAX_MB else mb) * 1024L * 1024L
        if (newBytes == maxBytes) return
        maxBytes = newBytes
        val cache = simpleCache ?: return
        releaseLocked(cache)
    }

    /** 从设置同步缓存开关与容量（播放器初始化时调用） */
    @Synchronized
    fun applyPrefs(enabled: Boolean, maxMb: Int) {
        val newBytes = (if (maxMb <= 0) DEFAULT_MAX_MB else maxMb) * 1024L * 1024L
        if (maxBytes != newBytes) {
            maxBytes = newBytes
            releaseLocked(simpleCache)
        }
        if (this.enabled != enabled) {
            this.enabled = enabled
            if (!enabled) releaseLocked(simpleCache)
        }
    }

    /** 获取（或创建）SimpleCache 实例 */
    @Synchronized
    fun get(context: Context): SimpleCache {
        val existing = simpleCache
        if (existing != null) return existing
        val cache = SimpleCache(
            File(context.cacheDir, CACHE_DIR),
            LeastRecentlyUsedCacheEvictor(maxBytes),
            ExoDatabaseProvider(context.applicationContext)
        )
        simpleCache = cache
        return cache
    }

    /** 清空缓存（停止后用，否则 SimpleCache 会抛 IllegalStateException） */
    @Synchronized
    fun clear(context: Context) {
        releaseLocked(simpleCache)
        simpleCache = null
        File(context.cacheDir, CACHE_DIR).deleteRecursively()
    }

    /** 当前已缓存字节数 */
    fun cacheSizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun releaseLocked(cache: Cache?) {
        try {
            cache?.release()
        } catch (_: Exception) {}
        simpleCache = null
    }

    private fun release() {
        releaseLocked(simpleCache)
    }
}