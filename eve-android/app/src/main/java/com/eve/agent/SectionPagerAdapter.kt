package com.eve.agent

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * SectionPagerAdapter backs the ViewPager2 in MainActivity.
 * Each entry in [sections] is a (tab title, fragment instance) pair.
 */
class SectionPagerAdapter(
    activity: FragmentActivity,
    private val sections: List<Pair<String, Fragment>>
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = sections.size

    override fun createFragment(position: Int): Fragment = sections[position].second
}
