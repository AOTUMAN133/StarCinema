package com.starcinema.player.kernel

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/**
 * 屏幕 HDR 能力检测：根据 Display.getHdrCapabilities() 判定当前屏幕是否支持 HDR。
 *
 * 用途：播放 HDR10/杜比视界内容时，SDR 屏必须做 HDR→SDR tone-mapping（MPV 需
 * hwdec=mediacodec-copy 让帧进 GPU 管线）；HDR 屏则保留直通不压缩动态范围。
 *
 * 权威依据：MediaCodecVideoRenderer.doesDisplaySupportDolbyVision() 就是这么检测的。
 */
object ScreenHdrDetector {

    /** 屏幕是否支持 HDR（任意 HDR 类型） */
    fun isHdr(context: Context): Boolean {
        return supportedHdrTypes(context).isNotEmpty()
    }

    /**
     * 屏幕支持的 HDR 类型列表（可能为空=完全不支持 HDR）。
     * HDR_TYPE_HDR10=1, HDR_TYPE_HDR10_PLUS=2, HDR_TYPE_DOLBY_VISION=3, HDR_TYPE_HLG=4
     */
    fun supportedHdrTypes(context: Context): IntArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return IntArray(0)
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return IntArray(0)
            if (!display.isHdr) return IntArray(0)
            display.hdrCapabilities.supportedHdrTypes ?: IntArray(0)
        } catch (_: Exception) {
            IntArray(0)
        }
    }

    /** 屏幕是否支持杜比视界 */
    fun supportsDolbyVision(context: Context): Boolean {
        return supportedHdrTypes(context).any { it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION }
    }

    /** 屏幕是否支持 HDR10 / HDR10+ */
    fun supportsHdr10(context: Context): Boolean {
        return supportedHdrTypes(context).any {
            it == Display.HdrCapabilities.HDR_TYPE_HDR10 ||
                it == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
        }
    }

    /** 人类可读描述（日志用）：如 "SDR(no HDR)" / "HDR10" / "HDR+DV" */
    fun describe(context: Context): String {
        val types = supportedHdrTypes(context)
        if (types.isEmpty()) return "SDR(no HDR)"
        val names = mutableListOf<String>()
        types.forEach {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> names.add("HDR10")
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> names.add("HDR10+")
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> names.add("DV")
                Display.HdrCapabilities.HDR_TYPE_HLG -> names.add("HLG")
                else -> names.add("type=$it")
            }
        }
        return names.joinToString("+")
    }
}