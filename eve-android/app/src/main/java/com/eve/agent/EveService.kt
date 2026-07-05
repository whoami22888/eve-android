package com.eve.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform

/**
 * EveService is a long-running foreground service that:
 *  - Starts the Chaquopy Python runtime
 *  - Instantiates the EVE orchestrator and its agents (Hermes, Hacxgent)
 *  - Exposes a LocalBinder so MainActivity can query status
 */
class EveService : Service() {

    private lateinit var eveInstance: PyObject
    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        startForeground(NOTIFICATION_ID, buildNotification())

        // *** MUST happen before any Python module is loaded ***
        // android_computer.py calls VirtualComputer.getInstance() at import
        // time, so the singleton must exist before we start the Python runtime.
        VirtualComputer.init(applicationContext)

        // Prompt user to enable the accessibility service if not yet active.
        if (!isAccessibilityServiceEnabled()) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        // Boot Python runtime
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val py = Python.getInstance()

        // Instantiate EVE orchestrator
        val eveModule = py.getModule("eve.orchestrator")
        eveInstance = eveModule.callAttr("EVE")

        // Instantiate agents
        val hermes   = py.getModule("eve.hermes_agent").callAttr("HermesAgent").call()
        val hacxgent = py.getModule("eve.hacxgent_agent").callAttr("HacxgentAgent").call()

        eveInstance.callAttr("register_agent", "hermes", hermes)
        eveInstance.callAttr("register_agent", "hacxgent", hacxgent)

        // Run the orchestrator loop on a background thread
        Thread({ eveInstance.callAttr("run") }, "eve-orchestrator").apply {
            isDaemon = true
            start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        // Signal the Python side to stop
        try {
            eveInstance.callAttr("stop")
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Binding
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): EveService = this@EveService
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val channelId = "eve_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "EVE Agent", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("EVE Agent")
            .setContentText("Orchestrating tasks…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val prefString = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return prefString.contains("${packageName}/.VirtualAccessibilityService")
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
