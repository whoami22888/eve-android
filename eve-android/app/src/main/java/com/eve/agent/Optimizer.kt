package com.eve.agent

import android.app.ActivityManager
import android.content.Context

/**
 * Optimizer queries device resource metrics so the orchestrator can make
 * informed scheduling decisions (e.g. defer heavy Python tasks when memory
 * pressure is high).
 */
class Optimizer(context: Context) {

    private val am: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** Returns current device memory metrics. */
    fun getMemoryInfo(): ActivityManager.MemoryInfo {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi
    }

    /** Returns true if the system is currently under memory pressure. */
    fun isLowMemory(): Boolean = getMemoryInfo().lowMemory

    /**
     * Returns available RAM in MB.
     * Use this to gate whether a heavy Python task (e.g. spaCy NLP) should run now.
     */
    fun availableRamMb(): Long = getMemoryInfo().availMem / (1024 * 1024)
}
