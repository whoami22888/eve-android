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

/** Long-running local EVE runtime used by both the normal dashboard and Agent Hub. */
class EveService : Service() {
    private lateinit var eveInstance: PyObject
    private lateinit var crashReporter: CrashReporter

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        crashReporter = CrashReporter(applicationContext)
        installUncaughtExceptionHandler()
        startForeground(NOTIFICATION_ID, buildNotification())
        VirtualComputer.init(applicationContext)

        if (!isAccessibilityServiceEnabled()) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        val py = Python.getInstance()
        py.getModule("os").callAttr("environ").__setitem__("EVE_DATA_DIR", filesDir.absolutePath)

        val eveModule = py.getModule("eve.orchestrator")
        eveInstance = eveModule.callAttr("EVE")

        val hermes = py.getModule("eve.hermes_agent").callAttr("HermesAgent").call(filesDir.absolutePath)
        val hacxgent = py.getModule("eve.hacxgent_agent").callAttr("HacxgentAgent").call()
        val agentHub = py.getModule("eve.agent_hub_agent").callAttr("AgentHubAgent").call(null, filesDir.absolutePath)

        eveInstance.callAttr("register_agent", "hermes", hermes)
        eveInstance.callAttr("register_agent", "agent_hub", agentHub)
        eveInstance.callAttr("register_agent", "hacxgent", hacxgent)

        Thread({ eveInstance.callAttr("run") }, "eve-orchestrator").apply {
            isDaemon = true
            start()
        }

        EveEventBus.emit(EveEvent.StatusChanged("EVE is running — Agent Hub ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { eveInstance.callAttr("stop") } catch (_: Exception) {}
        EveEventBus.emit(EveEvent.StatusChanged("EVE stopped"))
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): EveService = this@EveService
    }

    /** Submit a task to the local Hermes gateway without exposing shell/container details to the UI. */
    fun submitTask(action: String, params: Map<String, String> = emptyMap()) {
        val tokenFile = File(filesDir, "hermes_token.txt")
        val portFile = File(filesDir, "hermes_port.txt")
        if (!tokenFile.exists()) {
            Log.w(TAG, "submitTask: Hermes token not ready")
            EveEventBus.emit(EveEvent.LogLine("EVE runtime is still starting", "WARN"))
            return
        }
        val token = tokenFile.readText().trim()
        val port = portFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 5001
        val body = JSONObject().apply {
            put("action", action)
            put("params", JSONObject(params as Map<*, *>))
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/command")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()
        okHttp.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                val msg = "Task submit failed ($action): ${e.message}"
                Log.e(TAG, msg)
                crashReporter.logException(e, "submitTask")
                EveEventBus.emit(EveEvent.LogLine(msg, "ERROR"))
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    EveEventBus.emit(EveEvent.LogLine("Task submitted: $action → ${response.code}", "INFO"))
                }
            }
        })
    }

    private fun buildNotification(): Notification {
        val channelId = "eve_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "EVE Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("EVE Agent")
            .setContentText("EVE Agent Hub running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val prefString = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return prefString.contains("${packageName}/.VirtualAccessibilityService")
    }

    private fun installUncaughtExceptionHandler() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashReporter.logException(throwable, "UncaughtException[${thread.name}]")
            EveEventBus.emit(EveEvent.LogLine("CRASH in ${thread.name}: ${throwable.message}", "ERROR"))
            existing?.uncaughtException(thread, throwable)
        }
    }

    companion object { private const val NOTIFICATION_ID = 1 }
}
