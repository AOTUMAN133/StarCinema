package com.starcinema

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.widget.ViewPager2
import com.starcinema.view.EmbyFragment
import com.starcinema.view.MyPageAdapter
import com.starcinema.view.SearchFragment
import com.starcinema.view.SettingsFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var pageAdapter: MyPageAdapter

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            findViewById<TextView>(R.id.now_time)?.text =
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            timeHandler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 ViewPager2（首页层）
        viewPager = findViewById(R.id.viewPager)
        viewPager.setUserInputEnabled(false) // 禁用滑动，只用顶部栏切换
        pageAdapter = MyPageAdapter(supportFragmentManager, lifecycle)

        // 添加首页页面（先只加 Emby 首页）
        pageAdapter.addPage(EmbyFragment())

        viewPager.adapter = pageAdapter
        viewPager.setCurrentItem(0)

        // 星光影院：顶部导航条（首页/搜索/设置 + 时间/头像）
        findViewById<android.view.View>(R.id.nav_home).setOnClickListener { viewPager.setCurrentItem(0, true) }
        findViewById<android.view.View>(R.id.nav_search).setOnClickListener { openSearch() }
        findViewById<android.view.View>(R.id.nav_settings).setOnClickListener { openSettings() }
        // 🔴 顶部导航聚焦视觉反馈：聚焦时图标/文字变金色（无反馈=用户以为"无法聚焦"）
        fun navFocusListener(goldViews: List<Int>, grayViews: List<Int>) =
            android.view.View.OnFocusChangeListener { v, hasFocus ->
                val gold = v.context.getColor(R.color.star_gold)
                val gray = v.context.getColor(R.color.star_text_secondary)
                goldViews.forEach { id -> findViewById<android.widget.ImageView>(id)?.setColorFilter(if (hasFocus) gold else gray, android.graphics.PorterDuff.Mode.SRC_ATOP) }
                v.post {
                    if (v !is android.view.ViewGroup) return@post
                    for (i in 0 until v.childCount) {
                        val child = v.getChildAt(i)
                        if (child is android.widget.TextView) child.setTextColor(if (hasFocus) gold else gray)
                    }
                }
            }
        findViewById<android.view.View>(R.id.nav_home).setOnFocusChangeListener(
            navFocusListener(listOf(R.id.nav_home_icon), listOf(R.id.nav_search_icon, R.id.nav_settings_icon)))
        findViewById<android.view.View>(R.id.nav_search).setOnFocusChangeListener(
            navFocusListener(listOf(R.id.nav_search_icon), listOf(R.id.nav_home_icon, R.id.nav_settings_icon)))
        findViewById<android.view.View>(R.id.nav_settings).setOnFocusChangeListener(
            navFocusListener(listOf(R.id.nav_settings_icon), listOf(R.id.nav_home_icon, R.id.nav_search_icon)))
        // ☰ 菜单按钮 → 打开导航抽屉
        findViewById<android.view.View>(R.id.nav_menu_btn).setOnClickListener {
            val homeFrag = pageAdapter.pages.getOrNull(0)
            if (homeFrag is EmbyFragment && homeFrag.isResumed) homeFrag.onGlobalLeftKey(force = true)
        }

        // 顶部栏时间显示
        timeHandler.post(timeRunnable)

        // 子页面打开时隐藏顶部栏，返回首页时恢复
        // 星光影院定稿：媒体库页(LibraryGridFragment)保持顶部栏，但只显示右侧状态块(时间/WiFi)
        supportFragmentManager.addOnBackStackChangedListener {
            val showingSubPage = supportFragmentManager.backStackEntryCount > 0
            val topFrag = supportFragmentManager.findFragmentById(R.id.nav_host_container)
            val isLibraryGrid = topFrag is com.starcinema.view.LibraryGridFragment
            findViewById<android.view.View>(R.id.topNavBar)?.visibility =
                if (showingSubPage && !isLibraryGrid) android.view.View.GONE else android.view.View.VISIBLE
            // 媒体库页：隐藏 ☰/logo/横排导航，只留右侧时间/WiFi（对齐定稿图）
            findViewById<android.view.View>(R.id.nav_menu_btn)?.visibility =
                if (isLibraryGrid) android.view.View.INVISIBLE else android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.nav_logo_block)?.visibility =
                if (isLibraryGrid) android.view.View.INVISIBLE else android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.nav_center)?.visibility =
                if (isLibraryGrid) android.view.View.INVISIBLE else android.view.View.VISIBLE
        }

        // 标准返回处理：导航栏内返回先回内容区 → 再 pop 子页面 → 栈空才退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1. 焦点在左侧导航栏内 → 先回内容区
                val homeFrag = pageAdapter.pages.getOrNull(0)
                if (homeFrag is EmbyFragment && homeFrag.isResumed && homeFrag.isFocusInSidebar()) {
                    homeFrag.closeDrawerFromActivity()
                    return
                }
                // 2. pop 子页面
                if (!supportFragmentManager.popBackStackImmediate()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** 星光影院：全局方向键拦截——左键只在焦点最左边时开抽屉（Hero 聚焦时左键留给 Hero 翻页）；
     * 上键 → 第一行聚焦 Hero；右键 → 抽屉开着时关抽屉（返回内容区） */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val frag = pageAdapter.pages.getOrNull(0)
            if (frag is EmbyFragment && frag.isResumed) {
                // 焦点在左侧导航栏内：左键吞掉（导航栏内上下移动），右键回内容区
                if (frag.isFocusInSidebar()) {
                    when (event.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            frag.closeDrawerFromActivity()
                            return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> return true
                    }
                    return false
                }
                // Hero 有内容时：左/右键优先 Hero 翻页（用户期望"手动控制海报变动"）
                // 焦点在 Hero 上、或者焦点不在内容行内（顶部 nav/Hero 区域外）时都走 Hero
                if (frag.hasHeroItems() &&
                    (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                     event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    val focusInRow = frag.isFocusInsideContentRow()
                    if (frag.isHeroBannerFocused() || !focusInRow) {
                        when (event.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { frag.flipHeroFromActivity(-1); return true }
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { frag.flipHeroFromActivity(1); return true }
                        }
                    }
                }
                // 🔴 DPadRecyclerView 已消费 → return false 走默认（避免 MainActivity 重复处理）
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                        if (frag.onGlobalLeftKey()) return true
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        if (frag.onGlobalUpKey()) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        timeHandler.removeCallbacks(timeRunnable)
    }

    private fun openSearch() {
        val fm = supportFragmentManager
        if (fm.findFragmentById(R.id.nav_host_container) is SearchFragment) return
        fm.beginTransaction()
            .replace(R.id.nav_host_container, SearchFragment())
            .addToBackStack("search")
            .commitAllowingStateLoss()
    }

    private fun openSettings() {
        val fm = supportFragmentManager
        if (fm.findFragmentById(R.id.nav_host_container) is SettingsFragment) return
        fm.beginTransaction()
            .replace(R.id.nav_host_container, SettingsFragment())
            .addToBackStack("settings")
            .commitAllowingStateLoss()
        // 确保设置页获得焦点
        fm.executePendingTransactions()
        fm.findFragmentById(R.id.nav_host_container)?.view?.post {
            fm.findFragmentById(R.id.nav_host_container)?.view?.requestFocus()
        }
    }
}