package com.starcinema.view

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentFocusSettingsBinding

/** 焦点样式设置页 — 全部真实生效 */
class FocusSettingsFragment : Fragment() {

    private var _binding: FragmentFocusSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFocusSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesHelper(requireContext())

        refreshValues()

        // 隐藏焦点指示：直接读写 prefs（HorizontalAdapters 等渲染层读取）
        binding.focusHiddenBox.setOnClickListener { binding.focusHiddenSwitch.performClick() }
        binding.focusHiddenSwitch.isChecked = prefs.focusHidden
        binding.focusHiddenSwitch.setOnCheckedChangeListener { _, checked -> prefs.focusHidden = checked; FocusStyleNotifier.notifyChanged() }

        // 边框宽度
        binding.borderWidthBox.setOnClickListener {
            val options = arrayOf("0dp（无边框）", "1dp", "2dp", "3dp", "4dp", "6dp", "8dp")
            val values = intArrayOf(0, 1, 2, 3, 4, 6, 8)
            val checkedIdx = values.indexOfFirst { it == prefs.focusBorderWidth }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("边框宽度")
                .setSingleChoiceItems(options, checkedIdx) { d, which ->
                    prefs.focusBorderWidth = values[which]
                    binding.borderWidthValue.text = options[which]
                    FocusStyleNotifier.notifyChanged()
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 边框颜色
        binding.borderColorBox.setOnClickListener {
            val options = arrayOf("白色", "金色", "蓝色", "红色", "绿色")
            val colorNames = arrayOf("白色", "金色", "蓝色", "红色", "绿色")
            val colorValues = intArrayOf(
                -1, // 白色（默认）
                0xFFFFD700.toInt(), // 金色
                0xFF2BA6DE.toInt(), // 蓝色
                0xFFFF5252.toInt(), // 红色
                0xFF4CAF50.toInt()  // 绿色
            )
            val cur = prefs.focusBorderColor
            val checkedIdx = colorValues.indexOfFirst { it == cur }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("边框颜色")
                .setSingleChoiceItems(options, checkedIdx) { d, which ->
                    prefs.focusBorderColor = colorValues[which]
                    binding.borderColorValue.text = colorNames[which]
                    FocusStyleNotifier.notifyChanged()
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 缩放倍率
        binding.scaleBox.setOnClickListener {
            val options = arrayOf("0%（不变）", "3%", "5%", "8%", "10%", "15%", "20%", "30%")
            val values = intArrayOf(0, 3, 5, 8, 10, 15, 20, 30)
            val checkedIdx = values.indexOfFirst { it == prefs.focusScalePercent }.coerceAtLeast(1)
            AlertDialog.Builder(requireContext())
                .setTitle("缩放倍率")
                .setSingleChoiceItems(options, checkedIdx) { d, which ->
                    prefs.focusScalePercent = values[which]
                    binding.scaleValue.text = options[which]
                    FocusStyleNotifier.notifyChanged()
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 圆角半径
        binding.radiusBox.setOnClickListener {
            val options = arrayOf("0dp", "4dp", "8dp", "12dp", "16dp", "24dp", "30dp")
            val values = intArrayOf(0, 4, 8, 12, 16, 24, 30)
            val checkedIdx = values.indexOfFirst { it == prefs.focusCornerRadius }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("圆角半径")
                .setSingleChoiceItems(options, checkedIdx) { d, which ->
                    prefs.focusCornerRadius = values[which]
                    binding.radiusValue.text = options[which]
                    FocusStyleNotifier.notifyChanged()
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        view.post { binding.focusHiddenBox.requestFocus() }
    }

    private fun refreshValues() {
        binding.borderWidthValue.text = when (prefs.focusBorderWidth) {
            0 -> "0dp（无边框）"
            else -> "${prefs.focusBorderWidth}dp"
        }
        binding.borderColorValue.text = when (prefs.focusBorderColor) {
            -1 -> "白色"
            0xFFFFD700.toInt() -> "金色"
            0xFF2BA6DE.toInt() -> "蓝色"
            0xFFFF5252.toInt() -> "红色"
            0xFF4CAF50.toInt() -> "绿色"
            else -> "自定义"
        }
        binding.scaleValue.text = "${prefs.focusScalePercent}%"
        binding.radiusValue.text = "${prefs.focusCornerRadius}dp"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}