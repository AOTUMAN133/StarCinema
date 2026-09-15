package com.embytv.player.kernel

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 设备性能分级：用于内核参数自适应（高低端设备用不同配置）。
 *
 * 分级依据（综合评分）：
 *  - 总内存（ActivityManager.MemoryInfo.totalMem）
 *  - CPU 核数（Runtime.availableProcessors）
 *  - ABI（32 位 = 低端倾向；arm64 高端倾向）
 *
 * 判定为「高端」需满足：内存 ≥ 6GB 且 核数 ≥ 8（或 ABI 非 32 位且内存 ≥ 4GB）。
 * 低端盒子（小米盒子 5 等 32 位、小内存）会命中低端路径。
 */
object DeviceTier {

    /** 高端设备（骁龙 8 Elite、现代旗舰 TV 盒子） */
    fun isHighEnd(context: Context): Boolean = score(context) >= 5

    /** 综合评分 0-8 */
    fun score(context: Context): Int {
        var score = 0
        val memGb = totalMemGb(context)
        val cores = Runtime.getRuntime().availableProcessors()

        // 内存：>=6GB 记 3 分，>=4GB 记 2 分，>=2GB 记 1 分
        score += when {
            memGb >= 6.0 -> 3
            memGb >= 4.0 -> 2
            memGb >= 2.0 -> 1
            else -> 0
        }

        // CPU 核数：>=8 记 3 分，>=4 记 2 分，>=2 记 1 分
        score += when {
            cores >= 8 -> 3
            cores >= 4 -> 2
            cores >= 2 -> 1
            else -> 0
        }

        // ABI：非 32 位（arm64/x86_64）记 2 分
        if (!is32BitAbi()) score += 2

        return score
    }

    fun totalMemGb(context: Context): Double {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        } catch (_: Exception) {
            0.0
        }
    }

    /** 是否 32 位 ABI（armeabi-v7a / x86）——老盒子标识 */
    fun is32BitAbi(): Boolean {
        return Build.SUPPORTED_ABIS?.any {
            it.contains("armeabi-v7a") || it == "x86" || it.contains("mips")
        } == true && !Build.SUPPORTED_ABIS.any { it.contains("arm64") || it.contains("x86_64") }
    }

    /**
     * 设备摘要（调试日志用）。
     * 例: "high-end (mem=12.0GB cores=8 abi=arm64-v8a score=8)"
     */
    fun describe(context: Context): String {
        val high = isHighEnd(context)
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "?"
        return String.format(
            "%s (mem=%.1fGB cores=%d abi=%s score=%d)",
            if (high) "high-end" else "low-end",
            totalMemGb(context),
            Runtime.getRuntime().availableProcessors(),
            abi,
            score(context)
        )
    }
}