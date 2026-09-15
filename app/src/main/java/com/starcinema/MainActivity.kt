package com.starcinema

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * M1 骨架占位 Activity——后续 M3 按设计稿重写为 抽屉+Fragment 架构。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TextView(this).apply {
            text = "星空影院 M1 骨架"
            textSize = 32f
            setTextColor(0xFFE8B64C.toInt())
        }.let { setContentView(it) }
    }
}
