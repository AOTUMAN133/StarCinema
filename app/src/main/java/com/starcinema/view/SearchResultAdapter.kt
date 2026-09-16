package com.starcinema.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.R
import com.starcinema.api.EmbyClient
import com.starcinema.api.EmbyItem

/** 搜索结果网格适配器（复用网格卡） */
class SearchResultAdapter(
    private val items: List<EmbyItem>,
    private val baseUrl: String,
    private val apiKey: String,
    private val client: EmbyClient,
    private val onClick: (EmbyItem) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

    private val urls = items.map { client.getImageUrl(baseUrl, it.id, it.imageTags?.get("Primary") ?: it.primaryImageTag, apiKey, 300) }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_grid_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        val scale = FocusStyleHelper.scaleMultiplier(h.itemView.context)
        val hidden = FocusStyleHelper.hidden(h.itemView.context)
        h.title.text = item.name
        val cr = item.communityRating
        if (cr != null && cr > 0) {
            h.rating.visibility = View.VISIBLE
            h.rating.text = "%.1f".format(cr)
        } else h.rating.visibility = View.GONE
        EmbyImageLoader.load(h.image, urls[pos])
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                 keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) { v.performClick(); true } else false
        }
        h.itemView.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val card = h.imageBox as? com.google.android.material.card.MaterialCardView
            if (card != null) {
                if (!hidden) card.animate().scaleX(if (hasFocus) scale else 1f).scaleY(if (hasFocus) scale else 1f)
                    .setDuration(200).start()
                FocusStyleHelper.applyCardFocusBorder(card, hasFocus, h.itemView.context)
            }
            if (!hidden) v.animate().translationZ(if (hasFocus) 8f else 0f).setDuration(120).start()
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.posterImage)
        val title: TextView = v.findViewById(R.id.titleText)
        val rating: TextView = v.findViewById(R.id.ratingText)
        val imageBox: View? = v.findViewById(R.id.imageBox)
    }
}