package com.eve.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout
    internal var eveService: EveService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            eveService = (binder as EveService.LocalBinder).getService()
            currentService = eveService
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            eveService = null
            currentService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val sections = listOf(
            "Dashboard" to DashboardFragment(),
            "Agent Hub" to AgentHubFragment(),
            "AI Models" to ModelSettingsFragment(),
            "Agent Computer" to AgentComputerFragment(),
            "Memory Editor" to MemoryEditorFragment(),
            "History" to HistoryFragment(),
            "Setup" to SetupFragment()
        )
        viewPager.adapter = SectionPagerAdapter(this, sections)
        TabLayoutMediator(tabLayout, viewPager) { tab, position -> tab.text = sections[position].first }.attach()

        val serviceIntent = Intent(this, EveService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        currentService = null
        super.onDestroy()
    }

    companion object {
        @Volatile internal var currentService: EveService? = null
    }
}
