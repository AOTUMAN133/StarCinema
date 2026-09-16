package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.starcinema.R
import com.starcinema.databinding.FragmentSettingsBinding

/**
 * 设置页（C 组网盘/账户不移植、不做假功能）：服务器 / 焦点样式 / 关于
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val act = activity as? com.starcinema.MainActivity
        binding.serverEntry.setOnClickListener { act?.openFragment(ServerListFragment()) }
        binding.focusEntry.setOnClickListener { act?.openFragment(FocusSettingsFragment()) }
        binding.aboutEntry.setOnClickListener { act?.openFragment(AboutFragment()) }
        listOf(binding.serverEntry, binding.focusEntry, binding.aboutEntry).forEach { v ->
            v.setOnKeyListener { vv, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                     keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) { vv.performClick(); true } else false
            }
            v.setOnFocusChangeListener { vv, hasFocus ->
                vv.animate().scaleX(if (hasFocus) 1.03f else 1f).scaleY(if (hasFocus) 1.03f else 1f)
                    .setDuration(200).start()
                vv.setBackgroundResource(if (hasFocus) R.drawable.bg_btn_gold else R.color.bg_card)
            }
        }
        binding.serverEntry.requestFocus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}