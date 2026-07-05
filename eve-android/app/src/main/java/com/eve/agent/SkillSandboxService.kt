package com.eve.agent

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * SkillSandboxService runs in an isolated process (android:isolatedProcess="true")
 * to execute untrusted skill code fetched from GitHub or user input.
 *
 * Because it runs isolated, it cannot access the main process's memory or
 * singletons; all communication must go through AIDL or Messenger IPC.
 *
 * TODO: Implement AIDL interface and skill execution sandbox.
 */
class SkillSandboxService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        // Return an AIDL binder here once the IPC interface is defined
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Receive skill execution requests via intent extras (temporary approach
        // until AIDL is wired up)
        val script = intent?.getStringExtra("script") ?: return START_NOT_STICKY
        val language = intent.getStringExtra("language") ?: "python"

        // TODO: sandbox execution — currently a no-op in isolated process
        //       because VirtualComputer singleton is not available here.

        stopSelf(startId)
        return START_NOT_STICKY
    }
}
