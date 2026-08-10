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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class EveService : Service() {
    private lateinit var eveInstance: PyObject
    private lateinit var crashReporter: CrashReporter
    private val okHttp = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    override fun onCreate() {
        super.onCreate()
        crashReporter = CrashReporter(applicationContext)
        installUncaughtExceptionHandler()
        startForeground(NOTIFICATION_ID, buildNotification())
        VirtualComputer.init(applicationContext)
        if (!isAccessibilityServiceEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        val py = Python.getInstance()
        val env = requireNotNull(py.getModule("os").get("environ")) { "Python os.environ is unavailable" }
        env.callAttr("__setitem__", "EVE_DATA_DIR", filesDir.absolutePath)
        applyModelProviderEnvironment(env)

        val eveModule = py.getModule("eve.orchestrator")
        eveInstance = eveModule.callAttr("EVE")
        val hermes = py.getModule("eve.hermes_agent").callAttr("HermesAgent").call(filesDir.absolutePath)
        val hacxgent = py.getModule("eve.hacxgent_agent").callAttr("HacxgentAgent").call()
        // The Python constructor accepts the Chaquopy positional path used here
        // and normalizes it to data_dir for compatibility with older builds.
        val agentHub = py.getModule("eve.agent_hub_agent").callAttr("AgentHubAgent").call(filesDir.absolutePath)
        eveInstance.callAttr("register_agent", "hermes", hermes)
        eveInstance.callAttr("register_agent", "agent_hub", agentHub)
        eveInstance.callAttr("register_agent", "hacxgent", hacxgent)
        Thread({ eveInstance.callAttr("run") }, "eve-orchestrator").apply { isDaemon = true; start() }
        EveEventBus.emit(EveEvent.StatusChanged("EVE is running — Agent Hub ready"))
    }

    /** Refresh the running Python provider configuration after settings change. */
    fun refreshModelProvider() {
        Thread {
            try {
                val py = if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this))
                    Python.getInstance()
                } else Python.getInstance()
                val env = requireNotNull(py.getModule("os").get("environ")) { "Python os.environ is unavailable" }
                applyModelProviderEnvironment(env)
                py.getModule("eve.agent_hub_agent").callAttr("refresh_default_provider", filesDir.absolutePath)
                EveEventBus.emit(EveEvent.StatusChanged("AI model settings applied"))
            } catch (e: Exception) {
                EveEventBus.emit(EveEvent.LogLine("Could not refresh AI model settings: ${e.message}", "ERROR"))
            }
        }.start()
    }

    private fun applyModelProviderEnvironment(env: PyObject) {
        val config = ModelProviderStore(this).load()
        env.callAttr("__setitem__", "EVE_MODEL_PROVIDER", config.provider)
        env.callAttr("__setitem__", "EVE_MODEL_BASE_URL", config.baseUrl)
        env.callAttr("__setitem__", "EVE_MODEL_NAME", config.model)
        env.callAttr("__setitem__", "EVE_MODEL_API_KEY", config.apiKey)
        env.callAttr("__setitem__", "EVE_MODEL_TIMEOUT", config.timeoutSeconds.toString())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() { try { eveInstance.callAttr("stop") } catch (_: Exception) {}; EveEventBus.emit(EveEvent.StatusChanged("EVE stopped")); super.onDestroy() }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    inner class LocalBinder : Binder() { fun getService(): EveService = this@EveService }

    fun submitTask(action: String, params: Map<String, String> = emptyMap()) {
        val tokenFile = File(filesDir, "hermes_token.txt")
        val portFile = File(filesDir, "hermes_port.txt")
        if (!tokenFile.exists()) { EveEventBus.emit(EveEvent.LogLine("EVE runtime is still starting", "WARN")); return }
        val token = tokenFile.readText().trim()
        val port = portFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 5001
        val body = JSONObject().apply { put("action", action); put("params", JSONObject(params as Map<*, *>)) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("http://127.0.0.1:$port/command").addHeader("Authorization", "Bearer $token").post(body).build()
        okHttp.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { EveEventBus.emit(EveEvent.LogLine("Task submit failed ($action): ${e.message}", "ERROR")) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val bodyText = response.body?.string().orEmpty().takeLast(1000)
                    if (response.isSuccessful) {
                        EveEventBus.emit(EveEvent.LogLine("Task accepted: $action (${response.code})", "INFO"))
                    } else {
                        EveEventBus.emit(EveEvent.LogLine("Task rejected: $action (${response.code}) ${bodyText.take(300)}", "ERROR"))
                    }
                }
            }
        })
    }

    fun cancelAgentHub() = submitTask("agent_hub_cancel")

    fun testModelProvider(callback: (String) -> Unit) {
        val py = try { if (!Python.isStarted()) Python.start(AndroidPlatform(this)); Python.getInstance() } catch (e: Exception) { callback("Python runtime unavailable: ${e.message}"); return }
        Thread {
            try {
                val env = requireNotNull(py.getModule("os").get("environ")) { "Python os.environ is unavailable" }
                applyModelProviderEnvironment(env)
                val config = ModelProviderStore(this).load()
                if (config.model.isBlank()) { callback("EVE needs a model selection. Choose Auto or a model first."); return@Thread }
                if (config.provider != "ollama" && config.apiKey.isBlank()) { callback("${config.provider} requires an API key. Add it in AI Models."); return@Thread }
                val provider = py.getModule("eve.model_provider").callAttr("build_provider", filesDir.absolutePath)
                val result = provider.callAttr("health_check").toString().trim()
                callback(if (result.equals("OK", true)) "✓ ${config.provider} connected — ${config.model}" else "✓ ${config.provider} responded: $result")
            } catch (e: Exception) { callback("Provider test failed: ${e.message ?: "unknown error"}") }
        }.start()
    }

    private fun buildNotification(): Notification {
        val channelId = "eve_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, "EVE Agent", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, channelId).setContentTitle("EVE Agent").setContentText("EVE Agent Hub running").setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build()
    }
    private fun isAccessibilityServiceEnabled(): Boolean { val value = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false; return value.contains("${packageName}/.VirtualAccessibilityService") }
    private fun installUncaughtExceptionHandler() { val existing = Thread.getDefaultUncaughtExceptionHandler(); Thread.setDefaultUncaughtExceptionHandler { thread, throwable -> crashReporter.logException(throwable, "UncaughtException[${thread.name}]"); EveEventBus.emit(EveEvent.LogLine("CRASH in ${thread.name}: ${throwable.message}" , "ERROR")); existing?.uncaughtException(thread, throwable) } }
    companion object { private const val NOTIFICATION_ID = 1 }
}
