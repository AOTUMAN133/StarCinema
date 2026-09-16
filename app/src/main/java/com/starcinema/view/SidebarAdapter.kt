package com.starcinema.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.R
import com.starcinema.api.EmbyLibrary

/** 左侧媒体库栏条目：固定入口 or Emby 媒体库 */
sealed class SidebarItem {
    data class Entry(val key: String, val label: String) : SidebarItem()
    data class Lib(val lib: EmbyLibrary) : SidebarItem()
}

/** 左侧导航栏适配器（设计文档 v1.0：金色渐变胶囊选中态 + 图标 + 发光射线） */
class SidebarAdapter(
    private val onClick: (SidebarItem) -> Unit
) : ListAdapter<SidebarItem, SidebarAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<SidebarItem>() {
        override fun areItemsTheSame(a: SidebarItem, b: SidebarItem): Boolean = when {
            a is SidebarItem.Entry && b is SidebarItem.Entry -> a.key == b.key
            a is SidebarItem.Lib && b is SidebarItem.Lib -> a.lib.id == b.lib.id
            else -> false
        }
        override fun areContentsTheSame(a: SidebarItem, b: SidebarItem): Boolean = when {
            a is SidebarItem.Entry && b is SidebarItem.Entry -> a.label == b.label
            a is SidebarItem.Lib && b is SidebarItem.Lib -> a.lib.name == b.lib.name
            else -> false
        }
    }

    /** 媒体库名 → 分类图标（关键词匹配） */
    private fun libIcon(name: String): Int = when {
        name.contains("电影") || name.contains("Movie") -> R.drawable.ic_sidebar_movie
        name.contains("剧集") || name.contains("电视剧") || name.contains("Series") -> R.drawable.ic_sidebar_tv
        name.contains("综艺") || name.contains("真人秀") || name.contains("Variety") -> R.drawable.ic_sidebar_stage
        name.contains("动漫") || name.contains("动画") || name.contains("Anime") -> R.drawable.ic_sidebar_anime
        name.contains("纪录片") || name.contains("Documentary") -> R.drawable.ic_sidebar_doc
        name.contains("4K") || name.contains("4k") -> R.drawable.ic_sidebar_4k
        name.contains("合集") || name.contains("Collection") || name.contains("Box") -> R.drawable.ic_sidebar_collection
        name.contains("音乐") || name.contains("演唱会") || name.contains("Music") -> R.drawable.ic_sidebar_music
        else -> R.drawable.ic_sidebar_library
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.sidebar_item_label)
        val icon: ImageView = view.findViewById(R.id.sidebar_item_icon)
        val ray: View? = view.findViewById(R.id.sidebar_item_ray)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sidebar_entry, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.label.text = when (item) {
            is SidebarItem.Entry -> item.label
            is SidebarItem.Lib -> item.lib.name
        }
        holder.icon.setImageResource(
            when (item) {
                is SidebarItem.Entry -> when (item.key) {
                    "home" -> R.drawable.ic_sidebar_home
                    "search" -> R.drawable.ic_sidebar_search
                    "settings" -> R.drawable.ic_sidebar_settings
                    else -> R.drawable.ic_sidebar_library
                }
                is SidebarItem.Lib -> libIcon(item.lib.name)
            }
        )
        // 默认态：浅灰图标+浅灰文字
        holder.icon.setColorFilter(
            holder.itemView.context.getColor(R.color.text_secondary),
            android.graphics.PorterDuff.Mode.SRC_ATOP
        )
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_sidebar_focus else R.drawable.bg_sidebar_normal)
            val c = if (hasFocus) v.context.getColor(R.color.bg_primary) else v.context.getColor(R.color.text_secondary)
            holder.label.setTextColor(c)
            holder.icon.setColorFilter(c, android.graphics.PorterDuff.Mode.SRC_ATOP)
            holder.ray?.visibility = if (hasFocus) View.VISIBLE else View.GONE
        }
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            onClick(if (pos != RecyclerView.NO_POSITION) getItem(pos) else item)
        }
        // OK/ENTER 兜底（TV 焦点点击）
        holder.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) { v.performClick(); true } else false
        }
    }
}