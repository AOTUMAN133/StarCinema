package com.starcinema.view

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * ViewPager2 页面适配器（参考 AfuseKtV MyPageAdapter）
 * 管理多个首页 Fragment（Emby/TMDB/等），首页之间用 ViewPager2 切换
 */
class MyPageAdapter(fm: FragmentManager, lifecycle: Lifecycle) : FragmentStateAdapter(fm, lifecycle) {

    val pages = mutableListOf<Fragment>()

    override fun getItemCount() = pages.size

    override fun createFragment(position: Int): Fragment = pages[position]

    fun addPage(fragment: Fragment) {
        pages.add(fragment)
        notifyItemInserted(pages.size - 1)
    }
}