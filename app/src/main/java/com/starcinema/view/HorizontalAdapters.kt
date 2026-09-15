package com.starcinema.view

import android.content.Context
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
import com.starcinema.api.EmbyLibrary
import com.starcinema.view.FocusStyleHelper

/**
 * 横向行 LayoutManager：行首/行尾到达边界时锁焦点，不跳转到上一行/下一行。
 * 在 focusSearch 找不到下一个焦点（即已到边界）时返回当前焦点自身，让焦点停留。
 * 只拦截左右方向，上下方向正常交给父容器跳行。
 */
class EdgeLockLinearLayoutManager(
    context: Context,
    orientation: Int,
    reverseLayout: Boolean = false
) : LinearLayoutManager(context, orientation, reverseLayout) {

    override fun onFocusSearchFailed(
        focused: View,
        direction: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
            return focused // 停留在当前卡片，不跳到其他行
        }
        return super.onFocusSearchFailed(focused, direction, recycler, state)
    }
}

/** 水平滚动海报列表适配器 */
class HorizontalItemAdapter(
    val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyItem) -> Unit,
    private val onFocus: ((EmbyItem, Boolean, Boolean) -> Unit)? = null,
    private val landscape: Boolean = false,
    private val onSeeAll: (() -> Unit)? = null
) : RecyclerView.Adapter<HorizontalItemAdapter.ViewHolder>() {

    private val imageUrls = items.map { item ->
        // 剧集显示剧集海报（seriesId 的 Primary），而非单集缩略图
        val imageId = if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) item.seriesId else item.id
        val tag = if (imageId == item.id) (item.imageTags?.get("Primary") ?: item.primaryImageTag) else null
        if (tag != null) client.getImageUrl(baseUrl, imageId, tag, apiKey, 480)
        else "${baseUrl}/emby/Items/$imageId/Images/Primary?maxWidth=480&quality=80"
    }

    override fun getItemCount() = if (onSeeAll != null) items.size + 1 else items.size

    override fun getItemViewType(position: Int) = if (onSeeAll != null && position == items.size) TYPE_SEEALL else TYPE_NORMAL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutRes = when {
            viewType == TYPE_SEEALL -> R.layout.item_poster_seeall
            landscape -> R.layout.item_poster_landscape
            else -> R.layout.item_poster_card
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        // 首页行由 DpadRecyclerView 的 itemSpacing 统一控制间距，清掉 item 自带 marginEnd
        // （否则 DpadRecyclerView 1.5.0-beta01 会把首项 marginEnd 算进宽度，导致第一张窄 20px）
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginEnd = 0; it.marginStart = 0 }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        val scale = FocusStyleHelper.scaleMultiplier(ctx)
        val hidden = FocusStyleHelper.hidden(ctx)
        if (position == items.size) {
            // "查看全部"卡片 — 布局不同，没有 posterImage/titleText
            holder.itemView.setOnClickListener { onSeeAll?.invoke() }
            holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
            }
            return
        }
        val item = items[position]
        // 标题：Episode 显示剧集名（seriesName），其他显示原名称
        holder.title?.text = if (item.type == "Episode" && !item.seriesName.isNullOrBlank()) item.seriesName else item.name
        holder.subtitle?.text = subtitleFor(item)
        // 星光影院：金色评分 ★（有评分才显示）
        val cr = item.communityRating
        if (cr != null && cr > 0) {
            holder.rating?.visibility = View.VISIBLE
            holder.rating?.text = "★ %.1f".format(cr)
        } else {
            holder.rating?.visibility = View.GONE
        }
        // 未看剧集数 badge（右上角，仅剧集且有未看时显示）
        val unplayed = if (item.type == "Series") item.userData?.unplayedItemCount else null
        if (unplayed != null && unplayed > 0) {
            holder.unwatchedBadge?.visibility = View.VISIBLE
            holder.unwatchedBadge?.text = unplayed.toString()
        } else {
            holder.unwatchedBadge?.visibility = View.GONE
        }

        EmbyImageLoader.load(holder.image!!, imageUrls[position])

        // 星光影院：继续观看信息卡进度（金色填充+百分比）
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
                (holder.progressFill?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.weight = 0f
                (holder.progressTrack?.layoutParams as? android.widget.LinearLayout.LayoutParams)?.weight = 100f
                holder.percentText?.visibility = View.INVISIBLE
            }
        } else {
            holder.percentText?.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            // AfuseKtV 风格：卡片整体缩放+边框，clipChildren=false 保证边框完整
            val card = holder.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(200).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, ctx)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
            // 播放记录行（landscape）→ hasFocus 时显示 Banner；内容行聚焦 → 隐藏 Banner
            // 首参 hasFocus，次参是否 landscape 行（isResume）
            onFocus?.invoke(item, hasFocus, landscape)
        }
    }

    /** 副标题：播放记录剩态/剧集 SxxExx/年份，前置类型标签（电影/剧集/专辑） */
    private fun subtitleFor(item: EmbyItem): String {
        return when {
            item.type == "Episode" -> {
                val ep = item.indexNumber?.let { "E%02d".format(it) } ?: ""
                val season = item.parentIndexNumber?.let { "S%02d".format(it) }
                    ?: item.seasonName?.let { sn -> Regex("\\d+").find(sn)?.value?.toIntOrNull()?.let { "S%02d".format(it) } } ?: ""
                if (season.isNotEmpty() || ep.isNotEmpty()) "剧集 $season$ep" else "剧集 ${item.seriesName ?: ""}"
            }
            item.type == "Movie" -> {
                val remain = if (item.userData?.playbackPositionTicks != null && item.userData.playbackPositionTicks!! > 0 && item.runTimeTicks != null) {
                    val remainMs = (item.runTimeTicks - item.userData.playbackPositionTicks!!) / 10_000
                    "剩余 ${formatDuration(remainMs)}"
                } else {
                    item.productionYear?.toString() ?: ""
                }
                if (remain.isNotBlank()) "电影 $remain" else "电影"
            }
            item.type == "Series" -> {
                val year = item.productionYear?.toString() ?: ""
                if (year.isNotBlank()) "剧集 $year" else "剧集"
            }
            item.type == "MusicAlbum" -> {
                val year = item.productionYear?.toString() ?: ""
                if (year.isNotBlank()) "专辑 $year" else "专辑"
            }
            item.productionYear != null -> item.productionYear.toString()
            else -> ""
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms < 0) return "00:00"
        val total = ms / 1000
        val h = total / 3600; val m = (total % 3600) / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, total % 60) else "%02d:%02d".format(m, total % 60)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView? = view.findViewById(R.id.posterImage)
        val title: TextView? = view.findViewById(R.id.titleText)
        val subtitle: TextView? = view.findViewById(R.id.subtitleText)
        val rating: TextView? = view.findViewById(R.id.ratingText)
        val unwatchedBadge: TextView? = view.findViewById(R.id.unwatchedBadge)
        val imageBox: View? = view.findViewById(R.id.imageBox)
        val progressBar: View? = view.findViewById(R.id.progressBar)
        val progressFill: View? = view.findViewById(R.id.progressFill)
        val progressTrack: View? = view.findViewById(R.id.progressTrack)
        val percentText: TextView? = view.findViewById(R.id.percentText)
    }

    companion object {
        private const val TYPE_NORMAL = 0
        private const val TYPE_SEEALL = 1
    }
}

/** 水平滚动资源库适配器 */
class HorizontalLibraryAdapter(
    private val libraries: List<EmbyLibrary>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyLibrary) -> Unit,
    private val onFocus: ((EmbyLibrary, Boolean) -> Unit)? = null
) : RecyclerView.Adapter<HorizontalLibraryAdapter.ViewHolder>() {

    private val imageUrls = libraries.map { lib ->
        val tag = lib.imageTags?.get("Primary") ?: lib.primaryImageTag
        if (tag != null) client.getImageUrl(baseUrl, lib.id, tag, apiKey, 400)
        else "${baseUrl}/emby/Items/${lib.id}/Images/Primary?maxWidth=400&quality=80"
    }

    override fun getItemCount() = libraries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_library_card, parent, false)
        // 首页行由 DpadRecyclerView 的 itemSpacing 统一控制间距，清掉 item 自带 marginEnd
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.marginEnd = 0; it.marginStart = 0 }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        val scale = FocusStyleHelper.scaleMultiplier(ctx)
        val hidden = FocusStyleHelper.hidden(ctx)
        val lib = libraries[position]
        holder.name.text = lib.name
        EmbyImageLoader.load(holder.image, imageUrls[position])
        holder.itemView.setOnClickListener { onClick(lib) }
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                    // AfuseKtV 风格：缩放 itemView 整体（左边缘为锚点），默认 clipChildren=true 裁剪溢出
                    if (!hidden) {
                        v.pivotX = 0f
                        v.pivotY = v.height / 2f
                        v.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                            .setDuration(200).start()
                    }
                    val card = holder.imageBox as? com.google.android.material.card.MaterialCardView
                    if (card != null) {
                        FocusStyleHelper.applyCardFocusBorder(card, hasFocus, ctx)
                    }
                    if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
                    onFocus?.invoke(lib, hasFocus)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.libraryImage)
        val name: TextView = view.findViewById(R.id.libraryName)
        val imageBox: View? = view.findViewById(R.id.imageBox)
    }
}