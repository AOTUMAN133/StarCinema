package com.starcinema

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * M2 临时验证入口——启动 PlayerActivity 验证可播放。
 * M3 将按设计稿重写为 抽屉+Fragment 架构。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 80, 80, 80)
        }
        TextView(this).apply {
            text = "星空影院 M2 验证"
            textSize = 36f
            setTextColor(0xFFE8B64C.toInt())
        }.let { container.addView(it) }

        listOf(
            "▶ 播放《“大”人物》(itemId 878589)"
        ).forEach { label ->
            TextView(this).apply {
                text = label
                textSize = 28f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 60, 0, 0)
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, com.starcinema.view.PlayerActivity::class.java).apply {
                            putExtra("itemId", "878589")
                            putExtra("title", "\"大\"人物")
                            putExtra("embyBaseUrl", "http://192.168.1.33:48096")
                            putExtra("embyApiKey", "d916bdc17e6e4443ab72a9441a7a249b")
                        }
                    )
                }
            }.let { container.addView(it) }
        }
        setContentView(container)
    }
}
