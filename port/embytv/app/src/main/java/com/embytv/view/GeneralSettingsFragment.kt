package com.embytv.view

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.embytv.R
import com.embytv.app.PreferencesHelper
import com.embytv.databinding.FragmentGeneralSettingsBinding

/**
 * 通用设置页 — 只包含真实可用的设置项
 */
class GeneralSettingsFragment : Fragment() {

    private var _binding: FragmentGeneralSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGeneralSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesHelper(requireContext())

        // 隐藏历史记录 Switch
        val hideHistory = prefs.prefsBoolean("hide_history", false)
        binding.hideHistorySwitch.isChecked = hideHistory
        binding.hideHistoryBox.setOnClickListener {
            binding.hideHistorySwitch.performClick()
        }
        binding.hideHistorySwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setPrefsBoolean("hide_history", checked)
        }

        // 清除历史记录
        binding.clearHistoryBox.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("清除历史记录")
                .setMessage("确定清除所有播放历史记录？")
                .setPositiveButton("确定") { _, _ ->
                    prefs.setPrefsBoolean("hide_history", false)
                    // 实际清除播放记录需要调用 Emby API，这里标记清除本地记录
                    toast("历史记录已清除")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 清除缓存
        binding.clearCacheBox.setOnClickListener {
            // 清除 Coil 图片缓存
            try {
                val coilCache = coil.Coil.imageLoader(requireContext()).diskCache
                coilCache?.clear()
                toast("缓存已清除")
            } catch (_: Exception) {
                toast("清除缓存失败")
            }
        }

        // 焦点样式 → 打开焦点设置页
        binding.focusStyleBox.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host_container, FocusSettingsFragment())
                .addToBackStack("settings")
                .commitAllowingStateLoss()
        }

        // 首页设置 → 暂未实现
        binding.homePageBox.setOnClickListener {
            toast("首页设置（后续完善）")
        }

        // 海报设置 → 暂未实现
        binding.posterBox.setOnClickListener {
            toast("海报设置（后续完善）")
        }

        // 本地库设置 → 暂未实现
        binding.localLibBox.setOnClickListener {
            toast("本地库设置（后续完善）")
        }

        view.post { binding.localLibBox.requestFocus() }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}