package com.embytv.view

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import com.embytv.app.PreferencesHelper

/**
 * 焦点样式运行时读取器 — 让设置页的 prefs 真正生效
 * 渲染层（HorizontalAdapters / DetailFragment）通过它获取
 * 缩放倍率 / 边框宽度 / 边框颜色 / 圆角 / 是否隐藏
 */
object FocusStyleHelper {

    /** 缩放倍率乘数：5% → 1.05f，0% → 1.0f */
    fun scaleMultiplier(context: Context): Float {
        val prefs = PreferencesHelper(context.applicationContext)
        return 1f + prefs.focusScalePercent / 100f
    }

    /** 是否隐藏焦点指示 */
    fun hidden(context: Context): Boolean {
        val prefs = PreferencesHelper(context.applicationContext)
        return prefs.focusHidden
    }

    /** 焦点边框颜色（prefs 值） */
    fun focusColor(context: Context): Int {
        val prefs = PreferencesHelper(context.applicationContext)
        return prefs.focusBorderColorVal()
    }

    /** 焦点边框宽度 dp（prefs 值） */
    fun focusWidthDp(context: Context): Int {
        val prefs = PreferencesHelper(context.applicationContext)
        return prefs.focusBorderWidth
    }

    /**
     * 给 MaterialCardView 应用焦点 stroke 边框（AfuseKtV 风格）
     * 边框画在卡片上，与海报一起缩放，圆角一致，不出现角超出
     */
    fun applyCardFocusBorder(card: com.google.android.material.card.MaterialCardView, focused: Boolean, context: Context) {
        if (focused && !hidden(context)) {
            card.setStrokeColor(android.content.res.ColorStateList.valueOf(focusColor(context)))
            card.strokeWidth = dp(context, focusWidthDp(context).coerceAtLeast(2))
        } else {
            // 透明边框保布局不跳动
            card.setStrokeColor(android.content.res.ColorStateList.valueOf(0x00FFFFFF))
            card.strokeWidth = 2
        }
    }

    /** 根据 prefs 动态生成焦点边框 drawable（focused 星光金发光边框 + 未聚焦透明） */
    fun focusBorderDrawable(context: Context): StateListDrawable {
        val prefs = PreferencesHelper(context.applicationContext)
        val width = dp(context, prefs.focusBorderWidth)
        val color = prefs.focusBorderColorVal()
        val radius = dp(context, prefs.focusCornerRadius)

        // 聚焦态：双层描边模拟金色发光（外层半透明宽辉光 + 内层实金线）
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x00000000.toInt())
            setStroke(width + dp(context, 4), 0x40E8B64C.toInt()) // 外层辉光（半透明金）
            cornerRadius = radius.toFloat()
        }
        // 未聚焦态：透明
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x00000000.toInt())
            cornerRadius = radius.toFloat()
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    /** 星光金实线边框 drawable（无辉光，用于小控件/头像） */
    fun focusBorderSolidDrawable(context: Context): StateListDrawable {
        val prefs = PreferencesHelper(context.applicationContext)
        val width = dp(context, prefs.focusBorderWidth.coerceAtLeast(2))
        val color = prefs.focusBorderColorVal()
        val radius = dp(context, prefs.focusCornerRadius)

        val focused = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x00000000.toInt())
            setStroke(width, color)
            cornerRadius = radius.toFloat()
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x00000000.toInt())
            cornerRadius = radius.toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        if (value <= 0) 0 else (value * context.resources.displayMetrics.density).toInt()
}