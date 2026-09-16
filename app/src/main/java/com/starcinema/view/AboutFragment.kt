package com.starcinema.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.starcinema.R

/**
 * 关于页：版本信息
 */
class AboutFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_about, container, false)
        v.findViewById<TextView>(R.id.versionText)?.text = "星空影院 v${BuildConfig.VERSION_NAME}"
        return v
    }
}