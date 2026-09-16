package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.starcinema.R
import com.starcinema.app.PreferencesHelper
import com.starcinema.databinding.FragmentFocusSettingsBinding

/**
 * 焦点样式设置：缩放倍率 / 边框宽度 / 圆角 / 隐藏指示（真实生效）
 */
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

        binding.scaleSeek.max = 30
        binding.scaleSeek.progress = prefs.focusScalePercent
        binding.scaleVal.text = "${prefs.focusScalePercent}%"
        binding.scaleSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, f: Boolean) {
                prefs.focusScalePercent = p
                binding.scaleVal.text = "$p%"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) { notifyChange() }
        })

        binding.borderSeek.max = 8
        binding.borderSeek.progress = prefs.focusBorderWidth
        binding.borderVal.text = "${prefs.focusBorderWidth}dp"
        binding.borderSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, f: Boolean) {
                prefs.focusBorderWidth = p
                binding.borderVal.text = "$p dp"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) { notifyChange() }
        })

        binding.radiusSeek.max = 30
        binding.radiusSeek.progress = prefs.focusCornerRadius
        binding.radiusVal.text = "${prefs.focusCornerRadius}dp"
        binding.radiusSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, f: Boolean) {
                prefs.focusCornerRadius = p
                binding.radiusVal.text = "$p dp"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) { notifyChange() }
        })

        binding.hideToggle.setOnClickListener {
            prefs.focusHidden = !prefs.focusHidden
            updateHideLabel()
            notifyChange()
        }
        updateHideLabel()
    }

    private fun updateHideLabel() {
        binding.hideVal.text = if (prefs.focusHidden) "隐藏（无焦点指示）" else "显示"
    }

    private fun notifyChange() {
        com.starcinema.view.FocusStyleNotifier.listener?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}