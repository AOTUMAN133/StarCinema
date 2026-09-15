package com.embytv.danmu

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 弹幕搜索列表适配器：番剧/剧集条目，支持 TV 焦点高亮 */
class DanmuSearchListAdapter(
    private val items: List<String>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<DanmuSearchListAdapter.VH>() {

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(48, 14, 48, 14)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            isFocusable = true
            isClickable = true
            setBackgroundColor(0x00000000)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // TV 焦点：聚焦时背景亮色
        tv.setOnFocusChangeListener { _, has ->
            tv.setBackgroundColor(if (has) 0xFF3A8BFF.toInt() else 0x00000000)
        }
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = items[position]
        holder.tv.setOnClickListener { onClick(position) }
        holder.tv.setOnFocusChangeListener { _, has ->
            holder.tv.setBackgroundColor(if (has) 0xFF3A8BFF.toInt() else 0x00000000)
        }
    }

    override fun getItemCount(): Int = items.size
}