package com.starcinema.view

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.R
import com.starcinema.player.model.KernelTrackInfo
import com.starcinema.player.model.TrackType

/** 轨道选择面板适配器（分组：视频/音频/字幕） */
class TrackSelectionAdapter(
    private val tracks: List<KernelTrackInfo>,
    private val onSelect: (KernelTrackInfo) -> Unit
) : RecyclerView.Adapter<TrackSelectionAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_track_selection, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = tracks.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val track = tracks[position]
        val type = TrackType.fromMedia3Type(track.trackType)
        val typeLabel = when (type) {
            TrackType.VIDEO -> "视频"
            TrackType.AUDIO -> "音频"
            TrackType.SUBTITLE -> "字幕"
            else -> "其他"
        }
        // 只在分组变化时显示组标签：每组首个轨道显示标签
        val showGroup = position == 0 || trackTypeAt(position - 1) != track.trackType
        holder.groupLabel.visibility = if (showGroup) View.VISIBLE else View.GONE
        holder.groupLabel.text = typeLabel

        val name = track.label ?: track.id ?: ""
        holder.name.text = "${if (track.selected) "● " else "○ "}$name"
        holder.name.setTextColor(if (track.selected) 0xFF34C759.toInt() else 0xFFFFFFFF.toInt())
        holder.itemView.setOnClickListener { onSelect(track) }
    }

    private fun trackTypeAt(pos: Int): Int = tracks[pos].trackType

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val groupLabel: TextView = v.findViewById(R.id.trackGroupLabel)
        val name: TextView = v.findViewById(R.id.trackName)
    }
}