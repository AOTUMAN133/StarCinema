package com.embytv.player.kernel

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object VideoLog {
    var isPrintLog: Boolean = true  // 默认开启，方便调试
    private const val TAG = "SynoPlayer"
    
    // 内存日志缓冲区
    private val buffer = mutableListOf<String>()
    private const val MAX_BUFFER = 1000
    
    private fun log(level: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val entry = "$time [$level] $msg"
        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size > MAX_BUFFER) buffer.removeAt(0)
        }
    }

    fun i(msg: String) {
        if (isPrintLog) Log.i(TAG, msg)
        log("I", msg)
    }

    fun d(msg: String) {
        if (isPrintLog) Log.d(TAG, msg)
        log("D", msg)
    }

    fun e(msg: String) {
        Log.e(TAG, msg)
        log("E", msg)
    }

    fun e(msg: String, tr: Throwable) {
        Log.e(TAG, msg, tr)
        log("E", "$msg: ${tr.message}")
    }

    /** 导出日志到文件，返回文件路径 */
    fun exportToFile(dir: File): String? {
        val file = File(dir, "syno_player_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt")
        val content = synchronized(buffer) { buffer.joinToString("\n") }
        return try {
            file.writeText("SynoPlayer Log Export\n${"=".repeat(40)}\n$content")
            file.absolutePath
        } catch (e: Exception) { null }
    }
}