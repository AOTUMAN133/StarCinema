package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.starcinema.BuildConfig
import com.starcinema.databinding.FragmentAboutBinding

/** 关于页 — 显示真实版本信息 */
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.versionText.text = "版本 ${BuildConfig.VERSION_NAME}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}