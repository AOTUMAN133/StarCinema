package com.starcinema.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.R
import com.starcinema.model.VideoType

/**
 * 首页行类型适配器（参考 AfuseKtV VideoTypeRecyclerAdapterDiff）
 * 当前 Layer 1 只处理普通行（标题 + DpadRecyclerView），后续扩展 Banner/详情行
 */
class VideoTypeRecyclerAdapterDiff : ListAdapter<VideoType, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<VideoType>() {
            override fun areItemsTheSame(oldItem: VideoType, newItem: VideoType) =
                oldItem.id == newItem.id && oldItem.typeText == newItem.typeText

            override fun areContentsTheSame(oldItem: VideoType, newItem: VideoType) =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_movie_type_recycler, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val videoType = currentList[position]
        val rh = holder as RowViewHolder
        rh.title.text = videoType.typeText
        rh.box.visibility = if (videoType.see) View.VISIBLE else View.GONE
        // 行内横向列表用 DpadRecyclerView — 自动处理 TV 焦点，无需 EdgeLockLayoutManager
        rh.videoList.setAdapter(videoType.adapter)
        // 🔴 DpadRecyclerView 1.5.0 必调用 setSelectedPosition 才能把焦点交给 item（否则按方向键不响应）
        rh.videoList.setSelectedPosition(0)
        // 标题点击回调（如"查看全部"）：显示"更多 >"并可聚焦点击
        videoType.onTitleClick?.let { click ->
            rh.title.isFocusable = true
            rh.title.setOnClickListener { click() }
            rh.moreText.visibility = View.VISIBLE
            rh.moreText.isFocusable = true
            rh.moreText.setOnClickListener { click() }
            // 焦点链：行标题 → 更多> → 海报行（防止几何搜索乱跳到 Hero）
            rh.moreText.nextFocusRightId = R.id.videoList
            rh.moreText.nextFocusLeftId = R.id.titleText
            // 标题/更多 视觉聚焦反馈
            rh.title.setOnFocusChangeListener { v, hasFocus ->
                rh.title.setTextColor(v.context.getColor(if (hasFocus) com.starcinema.R.color.star_gold else android.R.color.white))
            }
            rh.moreText.setOnFocusChangeListener { v, hasFocus ->
                rh.moreText.setTextColor(v.context.getColor(if (hasFocus) com.starcinema.R.color.star_gold else com.starcinema.R.color.star_text_secondary))
            }
        } ?: run {
            rh.moreText.visibility = View.GONE
        }
    }

    /** 普通行：标题 + 横向 DpadRecyclerView */
    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val box: View = view.findViewById(R.id.box)
        val title: TextView = view.findViewById(R.id.typeText)
        val moreText: TextView = view.findViewById(R.id.moreText)
        val videoList: com.rubensousa.dpadrecyclerview.DpadRecyclerView =
            view.findViewById(R.id.videoList)
    }
}