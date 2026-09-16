package com.starcinema

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.starcinema.app.PreferencesHelper
import com.starcinema.view.EmbyFragment
import com.starcinema.view.ServerListFragment

/**
 * 星光影院主容器（设计文档 v1.0 架构：单 Activity + Fragment 栈）
 * 启动路由：有服务器 → 首页；无服务器 → 服务器添加页
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            val server = PreferencesHelper(this).activeEmbyServer()
            showRoot(if (server != null) EmbyFragment() else ServerListFragment())
        }
    }

    /** 展示根页面（清空返回栈，避免栈深） */
    fun showRoot(fragment: Fragment) {
        val fm = supportFragmentManager
        fm.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        fm.beginTransaction()
            .replace(R.id.container, fragment)
            .commitAllowingStateLoss()
    }

    /** 压栈打开子页面（详情/设置/播放等，返回回上级） */
    fun openFragment(fragment: Fragment, tag: String = "page") {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(tag)
            .commitAllowingStateLoss()
    }
}