package com.embytv.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.embytv.R
import com.embytv.databinding.FragmentSettingsBinding

/**
 * 主设置页（参考 AfuseKtV fragment_setting）
 * 4 个真实入口：服务器 → 通用 → 播放控制 → 关于
 * （无云同步/账户 — 本地免费应用，不做假功能）
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

        binding.serverSet.setOnClickListener { openFragment(ServerListFragment()) }
        binding.alistSet.setOnClickListener { openFragment(AlistListFragment()) }
        binding.accountSet.setOnClickListener { openFragment(AccountFragment()) }
        binding.generalSet.setOnClickListener { openFragment(GeneralSettingsFragment()) }
        binding.playControl.setOnClickListener { openFragment(PlaySettingsFragment()) }
        binding.webdavSet.setOnClickListener { openFragment(WebDavListFragment()) }
        binding.smbSet.setOnClickListener { openFragment(SmbListFragment()) }
        binding.aboutSet.setOnClickListener { openFragment(AboutFragment()) }

        view.post { binding.serverSet.requestFocus() }
    }

    private fun openFragment(frag: Fragment) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_container, frag)
            .addToBackStack("settings")
            .commitAllowingStateLoss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}