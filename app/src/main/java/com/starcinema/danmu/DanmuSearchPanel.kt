package com.starcinema.danmu

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.starcinema.R
import kotlinx.coroutines.launch

/**
 * 弹幕搜索面板（AfuseKtV panel_danmu_search 同款右侧抽屉）：
 * 半透明遮罩 + 右侧 460dp 面板 + 输入框 + RecyclerView 结果列表
 * 输入关键词回车搜索 → 列表显示番剧 → 点击番剧显示剧集 → 点击剧集加载弹幕
 */
class DanmuSearchPanel(
    private val activity: androidx.activity.ComponentActivity,
    private val danmuApi: DanmuApiClient,
    private val onEpisodePicked: (episodeId: String, title: String) -> Unit,
    private val onClosed: () -> Unit = {}
) {
    private var isShowing: FrameLayout? = null
    val isVisible: Boolean get() = isShowing != null
    private var listView: RecyclerView? = null
    private var emptyText: TextView? = null
    private var loadingBar: ProgressBar? = null
    private var inputView: EditText? = null

    private var currentAnimeId: String? = null
    private var currentTitle: String = ""

    /** 显示面板，prefill 为预填剧名 */
    fun show(prefill: String) {
        if (isShowing != null) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // ===== 遮罩层（点击关闭） =====
        val scrim = View(activity).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { close() }
        }

        // ===== 右侧面板 =====
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.shape_dialog_search)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        // 顶部：标题 + 关闭按钮
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(activity).apply {
            text = "手动搜索弹幕"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(dp(4), 0, 0, 0)
        }
        val closeBtn = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(0xFFFFFFFF.toInt())
            isFocusable = true
            isClickable = true
            contentDescription = "关闭"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { close() }
        }
        titleRow.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(closeBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        // 输入框
        val input = EditText(activity).apply {
            setText(prefill)
            hint = "输入番剧名，如：进击的巨人"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isFocusable = true
            isFocusableInTouchMode = true
            setTextSize(15f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundResource(R.drawable.shape_dialog_input)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8E8E93.toInt())
            setOnEditorActionListener { _, _, _ ->
                doSearch()
                true
            }
        }
        inputView = input

        // 搜索 / 取消 按钮行（TV 焦点优先落到"搜索"，避免 OK 键进输入框）
        fun actBtn(text: String, onClick: () -> Unit): TextView {
            val b = TextView(activity).apply {
                this.text = text
                textSize = 15f
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = false
                setPadding(dp(24), dp(10), dp(24), dp(10))
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.shape_dialog_btn_primary)
                setOnFocusChangeListener { _, has ->
                    setBackgroundResource(if (has) R.drawable.shape_dialog_btn_primary_focused else R.drawable.shape_dialog_btn_primary)
                }
                setOnClickListener { onClick() }
            }
            return b
        }
        val searchBtn = actBtn("搜 索") { doSearch() }
        val cancelBtn = actBtn("取 消") { close() }
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        btnRow.addView(searchBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(20) })
        btnRow.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 结果列表
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            isFocusable = true
            setPadding(0, dp(6), 0, 0)
            clipToPadding = false
        }
        listView = list

        // 空提示 / 加载
        val empty = TextView(activity).apply {
            text = "输入剧名后按确定搜索"
            setTextColor(0xFF8E8E93.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.VISIBLE
        }
        emptyText = empty
        val loading = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        loadingBar = loading

        panel.addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        panel.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        panel.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        panel.addView(loading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(4) })

        // 面板放右侧 460dp 宽
        val panelWrap = FrameLayout(activity)
        val panelParams = FrameLayout.LayoutParams(
            (460 * density).toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END
        )
        panelWrap.addView(panel, panelParams)

        val rootLayout = FrameLayout(activity).apply {
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(panelWrap, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        isShowing = rootLayout

        // 挂到 Activity 的 decorView（🔴 不能强转 activity 为 ViewGroup——ComponentActivity 不是 ViewGroup）
        val decor = activity.window.decorView as ViewGroup
        decor.addView(rootLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 🔴 TV 焦点：默认落在"搜索"按钮（不进输入框），方向键可在 输入框↔搜索↔取消 间移动
        input.nextFocusDownId = searchBtn.id
        searchBtn.nextFocusUpId = input.id
        cancelBtn.nextFocusUpId = input.id
        searchBtn.nextFocusRightId = cancelBtn.id
        cancelBtn.nextFocusLeftId = searchBtn.id
        searchBtn.postDelayed({ searchBtn.requestFocus() }, 120)
    }

    fun close() {
        isShowing?.let { (activity.window.decorView as ViewGroup).removeView(it) }
        isShowing = null
        onClosed()
    }

    private fun setLoading(on: Boolean) {
        loadingBar?.visibility = if (on) View.VISIBLE else View.GONE
        listView?.visibility = if (on) View.GONE else View.VISIBLE
        emptyText?.visibility = View.GONE
    }

    private fun doSearch() {
        val kw = inputView?.text?.toString()?.trim() ?: return
        if (kw.isEmpty()) { Toast.makeText(activity, "请输入关键词", Toast.LENGTH_SHORT).show(); return }
        setLoading(true)
        activity.lifecycleScope.launch {
            val animes = try { danmuApi.searchAnime(kw).getOrNull().orEmpty() } catch (e: Exception) { emptyList() }
            setLoading(false)
            if (animes.isEmpty()) {
                emptyText?.text = "未搜到番剧（可能接口繁忙，稍后重试）"
                emptyText?.visibility = View.VISIBLE
                listView?.adapter = null
                return@launch
            }
            emptyText?.visibility = View.GONE
            currentAnimeId = null
            val adapter = DanmuSearchListAdapter(
                items = animes.map { it.title + "（${it.episodes}集）" }
            ) { pos ->
                val anime = animes[pos]
                currentAnimeId = anime.animeId
                currentTitle = anime.title
                loadEpisodes(anime.animeId)
            }
            listView?.adapter = adapter
            listView?.post { listView?.requestFocus() }
        }
    }

    private fun showDanmakuItems(eps: List<com.starcinema.danmu.DanmuEpisode>) {
        emptyText?.visibility = View.GONE
        if (eps.isEmpty()) {
            emptyText?.text = "该番暂无剧集"
            emptyText?.visibility = View.VISIBLE
            listView?.adapter = null
            return
        }
        val adapter = DanmuSearchListAdapter(
            items = eps.map { it.title }
        ) { pos ->
            val ep = eps[pos]
            activity.lifecycleScope.launch {
                onEpisodePicked(ep.episodeId, ep.title)
                close()
            }
        }
        listView?.adapter = adapter
        listView?.post { listView?.requestFocus() }
    }

    /** 点击番剧 → 加载剧集列表 */
    private fun loadEpisodes(animeId: String) {
        setLoading(true)
        activity.lifecycleScope.launch {
            val eps = try { danmuApi.getEpisodes(animeId).getOrNull().orEmpty() } catch (e: Exception) { emptyList() }
            setLoading(false)
            if (eps.isEmpty()) {
                emptyText?.text = "该番暂无剧集，按返回重新搜索"
                emptyText?.visibility = View.VISIBLE
                listView?.adapter = null
                return@launch
            }
            showDanmakuItems(eps)
        }
    }
}