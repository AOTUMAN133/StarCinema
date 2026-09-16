package com.starcinema.view

import coil.Coil
import coil.request.ImageRequest
import android.widget.ImageView

object EmbyImageLoader {
    fun load(imageView: ImageView, url: String?) {
        if (url.isNullOrBlank()) return
        val request = ImageRequest.Builder(imageView.context)
            .data(url)
            .crossfade(200)
            .scale(coil.size.Scale.FILL)        // FILL 对应 ImageView scaleType=centerCrop
            .target(imageView)
            .build()
        Coil.imageLoader(imageView.context).enqueue(request)
    }

    /** 星光影院：小尺寸加载（用于焦点虚化背景——小图拉伸即天然模糊） */
    fun loadSmall(imageView: ImageView, url: String?) {
        if (url.isNullOrBlank()) return
        val request = ImageRequest.Builder(imageView.context)
            .data(url)
            .size(160)          // 极小尺寸 → 拉伸后自带模糊
            .crossfade(200)
            .scale(coil.size.Scale.FILL)
            .target(imageView)
            .build()
        Coil.imageLoader(imageView.context).enqueue(request)
    }
}