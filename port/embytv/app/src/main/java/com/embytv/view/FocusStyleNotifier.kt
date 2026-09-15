package com.embytv.view

/**
 * 焦点样式变更通知器 — 设置页改 prefs 后通知首页刷新边框
 */
object FocusStyleNotifier {
    @Volatile
    var listener: (() -> Unit)? = null

    /** 设置页调用：通知首页重新生成边框 */
    fun notifyChanged() {
        listener?.invoke()
    }
}