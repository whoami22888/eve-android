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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "EveService"

/**
 * EveService is a long-running foreground service that:
 *  - Starts the Chaquopy Python runtime
 *  - Instantiates the EVE orchestrator and its agents (Hermes, Hacxgent)
 *  - Exposes a [LocalBinder] so MainActivity / Fragments can submit tasks
 *    and query status without going through the network
 */
class EveService : Service() {

    private lateinit var eveInstance: PyObject
    private lateinit var crashReporter: CrashReporter

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        crashReporter = CrashReporter(applicationContext)
        installUncaughtExceptionHandler()

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

        // Expose the app's private files directory to Python so agents can
        // store data without hardcoding paths (filesDir differs across users
        // and profile IDs on multi-user Android devices).
        val osModule = py.getModule("os")
        osModule.callAttr("environ").__setitem__(
            "EVE_DATA_DIR", filesDir.absolutePath
        )

        // Instantiate EVE orchestrator
        val eveModule = py.getModule("eve.orchestrator")
        eveInstance = eveModule.callAttr("EVE")

        // Instantiate agents — pass the data dir so Hermes can persist its token
        val hermes   = py.getModule("eve.hermes_agent").callAttr("HermesAgent")
            .call(filesDir.absolutePath)
        val hacxgent = py.getModule("eve.hacxgent_agent").callAttr("HacxgentAgent").call()

        eveInstance.callAttr("register_agent", "hermes", hermes)
        eveInstance.callAttr("register_agent", "hacxgent", hacxgent)

        // Log lines flow: Python orchestrator → EveKotlinBridge.onLogLine()
        //                  → EveEventBus → EveViewModel → DashboardFragment
        // (No extra wiring needed here — EveKotlinBridge is called directly
        //  from orchestrator.py via Chaquopy's jclass bridge.)

        // Run the orchestrator loop on a background thread
        Thread({ eveInstance.callAttr("run") }, "eve-orchestrator").apply {
            isDaemon = true
            start()
        }

        // Broadcast initial status so the Dashboard can show "running"
        EveEventBus.emit(EveEvent.StatusChanged("EVE is running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        try { eveInstance.callAttr("stop") } catch (_: Exception) {}
        EveEventBus.emit(EveEvent.StatusChanged("EVE stopped"))
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): EveService = this@EveService
    }

    // ── Task submission ───────────────────────────────────────────────────────

    /**
     * Submit a task to the EVE pipeline via the local Hermes HTTP gateway.
     *
     * This is the same path that external tools use (adb-forwarded curl), but
     * called directly from Kotlin so Fragments don't need adb.
     *
     * Must be called on a background thread (performs network I/O).
     */
    fun submitTask(action: String, params: Map<String, String> = emptyMap()) {
        val tokenFile = File(filesDir, "hermes_token.txt")
        if (!tokenFile.exists()) {
            Log.w(TAG, "submitTask: token file not found — Hermes not started yet")
            EveEventBus.emit(EveEvent.LogLine("Cannot submit task: EVE not ready yet", "WARN"))
            return
        }
        val token = tokenFile.readText().trim()
        val body = JSONObject().apply {
            put("action", action)
            put("params", JSONObject(params as Map<*, *>))
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("http://127.0.0.1:5001/command")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        okHttp.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                val msg = "Task submit failed (${action}): ${e.message}"
                Log.e(TAG, msg)
                crashReporter.logException(e, "submitTask")
                EveEventBus.emit(EveEvent.LogLine(msg, "ERROR"))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    EveEventBus.emit(EveEvent.LogLine(
                        "Task submitted: $action → ${response.code}", "INFO"
                    ))
                }
            }
        })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    /**
     * Forward any uncaught exceptions to [CrashReporter] and the event bus
     * before the process dies, so the user sees something useful in the log.
     */
    private fun installUncaughtExceptionHandler() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashReporter.logException(throwable, "UncaughtException[${thread.name}]")
            EveEventBus.emit(EveEvent.LogLine(
                "CRASH in ${thread.name}: ${throwable.message}", "ERROR"
            ))
            existing?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
