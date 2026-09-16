package com.starcinema.view

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem
import com.starcinema.model.VideoType
import com.starcinema.view.FocusStyleHelper

/**
 * 横向行布局管理器：行首/行尾锁定焦点，不跳行。
 * 只在 focusSearch 失败（已到边界）时锁住左右方向。
 */
class EdgeLockLinearLayoutManager(
    context: Context,
    orientation: Int,
    reverseLayout: Boolean = false
) : LinearLayoutManager(context, orientation, reverseLayout) {
    override fun onFocusSearchFailed(
        focused: View, direction: Int,
        recycler: RecyclerView.Recycler, state: RecyclerView.State
    ): View? {
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) return focused
        return super.onFocusSearchFailed(focused, direction, recycler, state)
    }
}

/**
 * 首页横向行适配器（设计文档 v1.0）：
 * - 竖版海报（热门/推荐）/ 横版卡（继续观看含进度）
 * - 焦点：卡片缩放 + 金色边框 + Z 提升；标题走马灯随焦点联动
 * - 首项 marginStart=20dp、末项 marginEnd=20dp、中间 12dp（对齐 40px/24px）
 */
class HorizontalItemAdapter(
    val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyItem) -> Unit,
    private val landscape: Boolean = false
) : RecyclerView.Adapter<HorizontalItemAdapter.ViewHolder>() {

    private val imageUrls = items.map { item ->
        val imageId = if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) item.seriesId else item.id
        if (landscape) {
            val fanartTag = item.imageTags?.get("Thumb") ?: item.imageTags?.get("Backdrop")
            if (fanartTag != null) client.getImageUrl(baseUrl, imageId, fanartTag, apiKey, 640, "Thumb")
            else client.getBackdropUrl(baseUrl, imageId, null, apiKey, 640)
        } else {
            val tag = item.imageTags?.get("Primary") ?: item.primaryImageTag
            if (tag != null) client.getImageUrl(baseUrl, imageId, tag, apiKey, 480)
            else "$baseUrl/emby/Items/$imageId/Images/Primary?maxWidth=480&quality=80"
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(if (landscape) R.layout.item_poster_landscape else R.layout.item_poster_card, parent, false)
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginStart = 0; it.marginEnd = 0 }
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        val scale = FocusStyleHelper.scaleMultiplier(ctx)
        val hidden = FocusStyleHelper.hidden(ctx)
        // 首项 marginStart=20dp,末项 marginEnd=20dp,中间 12dp
        val lp = holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams
        val edgePx = (20 * ctx.resources.displayMetrics.density).toInt()
        val midPx = (12 * ctx.resources.displayMetrics.density).toInt()
        when (position) {
            0 -> { lp?.marginStart = edgePx; lp?.marginEnd = midPx }
            items.size - 1 -> { lp?.marginStart = midPx; lp?.marginEnd = edgePx }
            else -> { lp?.marginStart = midPx; lp?.marginEnd = midPx }
        }
        lp?.let { holder.itemView.layoutParams = it }

        val item = items[position]
        holder.title?.text = if (item.type == "Episode" && !item.seriesName.isNullOrBlank()) item.seriesName else item.name
        holder.subtitle?.text = subtitleFor(item)
        val cr = item.communityRating
        if (cr != null && cr > 0) {
            holder.rating?.visibility = View.VISIBLE
            holder.rating?.text = "★ %.1f".format(cr)
        } else holder.rating?.visibility = View.GONE

        EmbyImageLoader.load(holder.image!!, imageUrls[position])

        // 继续观看：进度条金色填充 + 百分比
        if (landscape) {
            val pos = item.userData?.playbackPositionTicks ?: 0
            val total = item.runTimeTicks ?: 0
            if (pos > 0 && total > 0) {
                val ratio = (pos.toFloat() / total).coerceIn(0f, 1f)
                (holder.progressFill?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.weight = ratio * 100f
                (holder.progressTrack?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.weight = (1 - ratio) * 100f
                holder.percentText?.text = "${(ratio * 100).toInt()}%"
                holder.percentText?.visibility = View.VISIBLE
            } else {
                holder.percentText?.visibility = View.GONE
            }
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == KeyEvent.KEYCODE_ENTER ||
                 keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) { v.performClick(); true } else false
        }
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            holder.title?.isSelected = hasFocus
            val card = holder.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(200).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, ctx)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
        }
    }

    private fun subtitleFor(item: EmbyItem): String = when {
        item.type == "Episode" -> {
            val ep = item.indexNumber?.let { "E%02d".format(it) } ?: ""
            val season = item.parentIndexNumber?.let { "S%02d".format(it) } ?: ""
            "剧集 $season$ep"
        }
        item.type == "Movie" -> item.productionYear?.toString() ?: "电影"
        item.type == "Series" -> item.productionYear?.toString()?.let { "剧集 $it" } ?: "剧集"
        else -> item.productionYear?.toString() ?: ""
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView? = view.findViewById(R.id.posterImage)
        val title: TextView? = view.findViewById(R.id.titleText)
        val subtitle: TextView? = view.findViewById(R.id.subtitleText)
        val rating: TextView? = view.findViewById(R.id.ratingText)
        val imageBox: View? = view.findViewById(R.id.imageBox)
        val progressFill: View? = view.findViewById(R.id.progressFill)
        val progressTrack: View? = view.findViewById(R.id.progressTrack)
        val percentText: TextView? = view.findViewById(R.id.percentText)
    }
}

/**
 * 首页行列表适配器：标题 + "更多" + 行内横向 DpadRecyclerView。
 * 焦点链：行标题左键 → 侧栏；标题/更多/卡片之间双向。
 */
class VideoTypeRecyclerAdapterDiff(
    private val onRowFocus: ((VideoType) -> Unit)? = null,
    private val onTitleFocus: ((VideoType) -> Unit)? = null,
    private val onMoreFocus: ((VideoType) -> Unit)? = null,
    private val onSelectedPosition: ((Int, Int) -> Unit)? = null,
    private val onChildFocus: ((VideoType, Int) -> Unit)? = null
) : RecyclerView.Adapter<VideoTypeRecyclerAdapterDiff.ViewHolder>() {

    private val rows = mutableListOf<VideoType>()
    val currentList: List<VideoType> get() = rows

    fun submitList(list: List<VideoType>) {
        rows.clear(); rows.addAll(list); notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_horizontal_row, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        holder.typeText.text = row.typeText
        holder.moreText.visibility = if (row.see) View.VISIBLE else View.GONE
        holder.videoList.layoutManager = EdgeLockLinearLayoutManager(holder.itemView.context, RecyclerView.HORIZONTAL, false)
        holder.videoList.adapter = row.adapter
        // DpadRecyclerView 必须 setSelectedPosition(0) 才能把焦点委托给 item（否则方向键焦点丢失）
        holder.videoList.setSelectedPosition(0)

        holder.typeText.setOnClickListener { row.onTitleClick?.invoke() }
        holder.typeText.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) { v.performClick(); true } else false
        }
        holder.typeText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            v.setTextColor(if (hasFocus) holder.itemView.context.getColor(R.color.gold_primary)
                else holder.itemView.context.getColor(R.color.text_primary))
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_sidebar_focus else 0)
            v.setPadding(16, 6, 16, 6)
        }
        holder.moreText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            v.setTextColor(if (hasFocus) holder.itemView.context.getColor(R.color.gold_primary)
                else holder.itemView.context.getColor(R.color.text_secondary))
            v.setBackgroundResource(if (hasFocus) R.drawable.bg_sidebar_focus else 0)
            v.setPadding(16, 6, 16, 6)
        }
        // 行标题左键 → 侧栏（由 Fragment 全局路由处理，这里只保证标题可达）
        holder.typeText.nextFocusRightId = R.id.videoList
        holder.moreText.nextFocusLeftId = R.id.videoList
        holder.videoList.nextFocusLeftId = R.id.typeText
        // 行内首卡左键 → 行标题
        holder.videoList.post {
            holder.videoList.findViewHolderForAdapterPosition(0)?.itemView?.nextFocusLeftId = R.id.typeText
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val typeText: TextView = view.findViewById(R.id.typeText)
        val moreText: TextView = view.findViewById(R.id.moreText)
        val videoList: com.rubensousa.dpadrecyclerview.DpadRecyclerView = view.findViewById(R.id.videoList)
    }
}