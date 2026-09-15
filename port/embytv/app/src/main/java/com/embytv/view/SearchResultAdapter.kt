package com.embytv.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.embytv.R
import com.embytv.api.EmbyClient
import com.embytv.api.EmbyItem

/** 搜索结果网格适配器 */
class SearchResultAdapter(
    private val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.Holder>() {

    private val imageUrls = items.map { item ->
        val imageId = if (item.type == "Episode" && !item.seriesId.isNullOrBlank()) item.seriesId else item.id
        val tag = item.imageTags?.get("Primary") ?: item.primaryImageTag
        if (tag != null) client.getImageUrl(baseUrl, imageId, tag, apiKey, 400)
        else "$baseUrl/emby/Items/$imageId/Images/Primary?maxWidth=400&quality=80"
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_gird, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val scale = FocusStyleHelper.scaleMultiplier(holder.itemView.context)
        val hidden = FocusStyleHelper.hidden(holder.itemView.context)

        holder.title.text = if (item.type == "Episode" && !item.seriesName.isNullOrBlank()) item.seriesName else item.name
        holder.subtitle.text = subtitleFor(item)

        EmbyImageLoader.load(holder.image, imageUrls[position])

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            // AfuseKtV 风格：缩放 imageBox（海报卡片整体放大+边框），标题不缩放
            val card = holder.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(200).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, holder.itemView.context)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
        }
    }

    private fun subtitleFor(item: EmbyItem): String {
        return when {
            item.type == "Episode" -> {
                val ep = item.indexNumber?.let { "E%02d".format(it) } ?: ""
                val season = item.parentIndexNumber?.let { "S%02d".format(it) }
                    ?: item.seasonName?.let { sn -> Regex("\\d+").find(sn)?.value?.toIntOrNull()?.let { "S%02d".format(it) } } ?: ""
                if (season.isNotEmpty() || ep.isNotEmpty()) "$season$ep" else item.seriesName ?: ""
            }
            item.productionYear != null -> item.productionYear.toString()
            else -> ""
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.searchImage)
        val title: TextView = view.findViewById(R.id.searchTitle)
        val subtitle: TextView = view.findViewById(R.id.searchYear)
        val imageBox: View? = view.findViewById(R.id.imageBox)
    }
}