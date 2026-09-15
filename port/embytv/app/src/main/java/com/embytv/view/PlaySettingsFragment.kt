package com.embytv.view

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.embytv.app.PreferencesHelper
import com.embytv.databinding.FragmentPlaySettingsBinding

/** 播放设置页 — 真实可交互的设置项 */
class PlaySettingsFragment : Fragment() {

    private var _binding: FragmentPlaySettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PreferencesHelper

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferencesHelper(requireContext())

        refreshValues()

        // 默认播放器选择
        binding.playerSelectBox.setOnClickListener {
            val current = prefs.embyPreferredPlayer
            val options = arrayOf("EXO", "MPV")
            val checkedIdx = options.indexOfFirst { it.equals(current, ignoreCase = true) }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("默认播放器")
                .setSingleChoiceItems(options, checkedIdx) { d, which ->
                    prefs.embyPreferredPlayer = options[which].lowercase()
                    binding.playerValue.text = options[which]
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // MPV 解码/渲染配置（自动/高端/低端）
        binding.renderConfigBox.setOnClickListener {
            val current = prefs.playerRenderConfig
            val options = arrayOf("自动", "高端", "低端")
            val values = arrayOf("auto", "high", "low")
            val labels = arrayOf("自动（设备自适应）", "高端（gpu-next + mediacodec）", "低端（gpu + 回退链）")
            val checkedIdx = values.indexOfFirst { it == current }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("MPV 解码/渲染配置")
                .setSingleChoiceItems(labels, checkedIdx) { d, which ->
                    prefs.playerRenderConfig = values[which]
                    binding.renderConfigValue.text = options[which]
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 跳过片头（弹选项列表）
        binding.skipIntroBox.setOnClickListener {            val current = prefs.playerSkipIntroSeconds
            val options = arrayOf("0（关闭）", "5秒", "10秒", "15秒", "30秒", "60秒")
            val values = intArrayOf(0, 5, 10, 15, 30, 60)
            val checkedIdx = values.indexOfFirst { it == current }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("跳过片头")
                .setSingleChoiceItems(options, checkedIdx) { d, which ->
                    prefs.playerSkipIntroSeconds = values[which]
                    binding.skipIntroValue.text = if (values[which] == 0) "0" else "${values[which]}秒"
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 缓存大小
        binding.cacheBox.setOnClickListener {
            val current = prefs.playerCacheSizeMb
            val options = arrayOf("64 MB", "128 MB", "256 MB", "512 MB", "1024 MB", "2048 MB")
            val values = intArrayOf(64, 128, 256, 512, 1024, 2048)
            val checkedIdx = values.indexOfFirst { it == current }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("缓存大小")
                .setSingleChoiceItems(options, checkedIdx) { d, which ->
                    prefs.playerCacheSizeMb = values[which]
                    binding.cacheValue.text = "${values[which]}"
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 快进/快退步长（弹选项列表，AfuseKtV fast_forward_rewind_time 同款）
        binding.seekBox.setOnClickListener {
            val current = prefs.playerSeekSeconds
            val options = arrayOf("3秒", "5秒", "10秒", "30秒", "60秒")
            val values = intArrayOf(3, 5, 10, 30, 60)
            val checkedIdx = values.indexOfFirst { it == current }.coerceAtLeast(1)
            AlertDialog.Builder(requireContext())
                .setTitle("快进/快退步长")
                .setSingleChoiceItems(options, checkedIdx) { d, which ->
                    prefs.playerSeekSeconds = values[which]
                    binding.seekValue.text = "${values[which]}秒"
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 字幕大小
        binding.subtitleSizeBox.setOnClickListener {
            val current = prefs.subtitleFontSize
            val options = arrayOf("小（30）", "中（45）", "标准（55）", "大（70）", "特大（90）", "超大（120）")
            val values = intArrayOf(30, 45, 55, 70, 90, 120)
            val checkedIdx = values.indexOfFirst { it == current }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("字幕大小")
                .setSingleChoiceItems(options, checkedIdx) { d, which ->
                    prefs.subtitleFontSize = values[which]
                    binding.subtitleSizeValue.text = "${values[which]}"
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 自动下一集
        binding.autoNextBox.setOnClickListener { binding.autoNextSwitch.performClick() }
        binding.autoNextSwitch.isChecked = prefs.prefsBoolean("auto_next", false)
        binding.autoNextSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setPrefsBoolean("auto_next", checked)
        }

        // 记住播放进度
        binding.rememberPosBox.setOnClickListener { binding.rememberPosSwitch.performClick() }
        binding.rememberPosSwitch.isChecked = prefs.prefsBoolean("remember_position", true)
        binding.rememberPosSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setPrefsBoolean("remember_position", checked)
        }

        // 音频直通（杜比/DTS 源码透传到功放，MPV 内核生效）
        binding.passthroughBox.setOnClickListener { binding.passthroughSwitch.performClick() }
        binding.passthroughSwitch.isChecked = prefs.prefsBoolean("player_audio_passthrough", false)
        binding.passthroughSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.setPrefsBoolean("player_audio_passthrough", checked)
        }

        view.post { binding.playerSelectBox.requestFocus() }
    }

    private fun refreshValues() {
        binding.playerValue.text = prefs.embyPreferredPlayer.uppercase()
        binding.renderConfigValue.text = when (prefs.playerRenderConfig) {
            "high" -> "高端"
            "low" -> "低端"
            else -> "自动"
        }
        binding.skipIntroValue.text = if (prefs.playerSkipIntroSeconds == 0) "0" else "${prefs.playerSkipIntroSeconds}秒"
        binding.cacheValue.text = "${prefs.playerCacheSizeMb}"
        binding.seekValue.text = "${prefs.playerSeekSeconds}秒"
        binding.subtitleSizeValue.text = "${prefs.subtitleFontSize}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}