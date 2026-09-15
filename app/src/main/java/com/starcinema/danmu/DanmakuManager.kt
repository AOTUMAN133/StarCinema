package com.starcinema.danmu

import android.view.View
import com.starcinema.R
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.Duration
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.DanmakuFactory
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView

/**
 * 弹幕管理器：封装 DanmakuView 生命周期 + 弹幕加载 + 设置控制
 *
 * 弹幕类型: 1=滚动 4=底部 5=顶部
 */
class DanmakuManager(private val danmakuView: IDanmakuView) {

    private var ctx: DanmakuContext? = null
    private var isPrepared = false
    private var isEnabled = false
    private var density = 1f
    /** prepare 未完成前加载的弹幕缓存（prepared 后自动补加载） */
    private var pendingItems: List<DanmuItem>? = null
    /** 加载时记录的对齐时间（prepared 后一次性 seekTo，不再周期同步） */
    private var pendingAlignMs = -1L

    companion object {
        /** 默认单条滚动时长 ms */
        private const val DEFAULT_DURATION = 5000L
    }

    /** 初始化（DanmakuView 附着后调用一次） */
    fun init() {
        val view = danmakuView.getView()
        density = view.resources.displayMetrics.density
        ctx = DanmakuContext.create()

        danmakuView.enableDanmakuDrawingCache(true)
        danmakuView.setCallback(object : DrawHandler.Callback {
            override fun prepared() {
                isPrepared = true
                // 🔴 prepare 完成前缓存的弹幕，在这里补加载（否则静默丢弃 → "加载成功"但无弹幕）
                pendingItems?.let { items ->
                    pendingItems = null
                    if (items.isNotEmpty()) internalLoad(items)
                }
                // 🔴 一次性对齐到加载时刻的播放位置（弹幕时间轴与播放位置对齐，但
                //    不做周期 seek —— 周期 seek 会反复清屏重绘导致弹幕"只动一下就被刷新"）
                if (pendingAlignMs >= 0) {
                    val align = pendingAlignMs
                    pendingAlignMs = -1
                    try { danmakuView.seekTo(align) } catch (_: Exception) {}
                }
                startIfReady()
            }
            override fun updateTimer(timer: master.flame.danmaku.danmaku.model.DanmakuTimer?) {}
            override fun danmakuShown(danmaku: BaseDanmaku?) {}
            override fun drawingFinished() {}
        })

        // 🔴 标准 Bili parser（AfuseKtV 同款）：必须挂上真实数据源(AndroidFileSource), parser.load(source)
        //    后 prepare 才会回调 prepared() → isPrepared=true. 空 parser 会导致 prepared 永不回调,
        //    弹幕全部被缓存丢弃 → "加载成功"但永远不显示.
        try {
            val source = master.flame.danmaku.danmaku.parser.android.AndroidFileSource(
                view.resources.openRawResource(R.raw.comments)
            )
            val parser = object : BaseDanmakuParser() {
                override fun parse(): master.flame.danmaku.danmaku.model.IDanmakus {
                    // 数据通过 addDanmaku 逐个添加, parse 只返回空集合确保流程正常
                    return master.flame.danmaku.danmaku.model.android.Danmakus()
                }
            }
            parser.load(source)
            danmakuView.prepare(parser, ctx)
        } catch (e: Exception) {
            android.util.Log.e("DanmakuManager", "init parser 失败, 回退空 parser", e)
            val parser = object : BaseDanmakuParser() {
                override fun parse(): master.flame.danmaku.danmaku.model.IDanmakus {
                    return master.flame.danmaku.danmaku.model.android.Danmakus()
                }
            }
            danmakuView.prepare(parser, ctx)
        }
        // 🔴 用 INVISIBLE 而非 GONE：GONE 会让视图尺寸为 0，弹幕显示区域塌缩到角落。
        //    INVISIBLE 保留满屏尺寸但不显示，setEnabled 时切 VISIBLE
        danmakuView.getView().visibility = View.INVISIBLE
    }

    /** 加载弹幕列表（调用前自行按时间排序）；positionMs 为当前播放位置，用于时间轴对齐 */
    fun loadDanmu(newItems: List<DanmuItem>, positionMs: Long = 0L) {
        // 🔴 prepare 未完成时缓存待补（否则静默丢弃 → "加载成功"但无弹幕）
        if (!isPrepared) {
            pendingItems = newItems.sortedBy { it.timeMs }
            pendingAlignMs = positionMs
            return
        }
        internalLoad(newItems)
        // 🔴 一次性对齐到当前播放位置（不周期 seek → 弹幕不再被反复清屏）
        try { danmakuView.seekTo(positionMs) } catch (_: Exception) {}
    }

    /** 播放位置跳转时同步弹幕时间轴（仅用户主动 seek 时调用，勿周期调用） */
    fun alignTo(positionMs: Long) {
        if (!isPrepared) {
            pendingAlignMs = positionMs
            return
        }
        try { danmakuView.seekTo(positionMs) } catch (_: Exception) {}
    }

    private fun internalLoad(newItems: List<DanmuItem>) {
        // 🔴 数量采样：一条剧集弹幕可上万条，全塞主线程会 ANR（实测 6707 条卡死 10 秒）。
        //    均匀抽样到 ≤3000 条（弹幕密度视觉无感，流畅度大幅提升）
        val sorted = newItems.sortedBy { it.timeMs }
        val sampled: List<DanmuItem> = if (sorted.size > 3000) {
            val step = sorted.size.toDouble() / 3000
            (0 until 3000).map { sorted[(it * step).toInt()] }
        } else sorted

        danmakuView.removeAllDanmakus(true)
        val factory = DanmakuFactory.create()
        for (item in sampled) {
            // 1=滚动 4=底部 5=顶部；非法类型回退为滚动
            val type = if (item.type == 4 || item.type == 5) item.type else 1
            val d = factory.createDanmaku(type, ctx)
            d.text = item.text
            d.textColor = item.color
            d.priority = 1.toByte()
            d.duration = Duration(DEFAULT_DURATION)
            d.time = item.timeMs
            // 字号按 DanmakuFactory 缩放系数（默认 SPINNER 尺寸之上再乘密度）
            d.textSize = 22f * density
            danmakuView.addDanmaku(d)
        }
        startIfReady()
    }

    /** 🔴 等视图有真实尺寸再 start（onCreate 时 view=0x0，0 尺寸初始化渲染 → 弹幕挤角落 1/10） */
    private fun startIfReady() {
        if (!isEnabled || !isPrepared) return
        val v = danmakuView.getView()
        if (v.width > 0 && v.height > 0) {
            danmakuView.start()
        } else {
            v.post { startIfReady() }
        }
    }

    /** 播放位置跳转 */
    fun seekTo(positionMs: Long) {
        if (isPrepared) danmakuView.seekTo(positionMs)
    }

    /** 启用/禁用弹幕显示 */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        // 🔴 bringToFront: 防 videoFrame/mpvOsdSurface(SurfaceView) 在部分 GPU 组合下盖住弹幕层
        val v = danmakuView.getView()
        android.util.Log.i("Danmu", "setEnabled=$enabled view=${v.width}x${v.height} " +
            "layout=${v.layoutParams?.width}x${v.layoutParams?.height} prepared=$isPrepared visible=${v.visibility}")
        v.apply {
            visibility = if (enabled) View.VISIBLE else View.INVISIBLE
            if (enabled) bringToFront()
        }
        if (enabled && isPrepared) {
            // 🔴 统一走 startIfReady：必须等视图完成布局有真实尺寸再 start
            startIfReady()
        } else if (!enabled && isPrepared) danmakuView.pause()
    }

    fun isEnabled(): Boolean = isEnabled

    fun pause() { if (isEnabled && isPrepared) danmakuView.pause() }
    fun resume() { if (isEnabled && isPrepared) danmakuView.start() }

    /** 字幕大小缩放 (1.0=默认) */
    fun setTextScale(scale: Float) {
        ctx?.setScaleTextSize(scale)
    }

    /** 透明度 0~1 */
    fun setAlpha(alpha: Float) {
        ctx?.setDanmakuTransparency(alpha.coerceIn(0f, 1f))
    }

    /** 最大可视行数（滚动+固定混合） */
    fun setMaxLines(lines: Int) {
        ctx?.setMaximumLines(hashMapOf(1 to lines, 4 to lines, 5 to lines))
    }

    /** 滚动速度倍率 (<1 慢, >1 快) */
    fun setScrollSpeedFactor(factor: Float) {
        ctx?.setScrollSpeedFactor(factor)
    }

    /** 弹幕间距 */
    fun setMargin(margin: Int) {
        ctx?.setDanmakuMargin(margin)
    }

    fun release() {
        danmakuView.release()
    }
}