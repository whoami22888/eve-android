package com.eve.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class EveService : Service() {
    private lateinit var eveInstance: PyObject
    private lateinit var crashReporter: CrashReporter
    @Volatile private var isStopping = false
    private val okHttp = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val startupHandler = Handler(Looper.getMainLooper())
    private val uncaughtExceptionHandlerLease = UncaughtExceptionHandlerLease()

    override fun onCreate() {
        super.onCreate(); isStopping = false; crashReporter = CrashReporter(applicationContext); installUncaughtExceptionHandler(); startForeground(NOTIFICATION_ID, buildNotification())
        VirtualComputer.init(applicationContext)
        if (!isAccessibilityServiceEnabled()) startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        val py = Python.getInstance(); val env = requireNotNull(py.getModule("os").get("environ")) { "Python os.environ is unavailable" }
        env.callAttr("__setitem__", "EVE_DATA_DIR", filesDir.absolutePath); applyModelProviderEnvironment(env)
        val eveModule = py.getModule("eve.orchestrator"); eveInstance = eveModule.callAttr("EVE")
        val hermes = py.getModule("eve.hermes_agent").callAttr("HermesAgent").call(filesDir.absolutePath)
        val hacxgent = py.getModule("eve.hacxgent_agent").callAttr("HacxgentAgent").call()
        val agentHub = py.getModule("eve.agent_hub_agent").callAttr("AgentHubAgent").call(filesDir.absolutePath)
        eveInstance.callAttr("register_agent", "hermes", hermes); eveInstance.callAttr("register_agent", "agent_hub", agentHub); eveInstance.callAttr("register_agent", "hacxgent", hacxgent)
        Thread({ eveInstance.callAttr("run") }, "eve-orchestrator").apply { isDaemon = true; start() }
        EveEventBus.emit(EveEvent.StatusChanged("EVE is running — Agent Hub ready"))
    }

    fun refreshModelProvider() {
        Thread {
            try {
                val py = if (!Python.isStarted()) { Python.start(AndroidPlatform(this)); Python.getInstance() } else Python.getInstance()
                val env = requireNotNull(py.getModule("os").get("environ")) { "Python os.environ is unavailable" }; applyModelProviderEnvironment(env)
                py.getModule("eve.agent_hub_agent").callAttr("refresh_default_provider", filesDir.absolutePath); EveEventBus.emit(EveEvent.StatusChanged("AI model settings applied"))
            } catch (e: Exception) { EveEventBus.emit(EveEvent.LogLine("Could not refresh AI model settings: ${e.message}", "ERROR")) }
        }.start()
    }

    private fun applyModelProviderEnvironment(env: PyObject) {
        val config = ModelProviderStore(this).load()
        env.callAttr("__setitem__", "EVE_MODEL_PROVIDER", config.provider); env.callAttr("__setitem__", "EVE_MODEL_BASE_URL", config.baseUrl); env.callAttr("__setitem__", "EVE_MODEL_NAME", config.model); env.callAttr("__setitem__", "EVE_MODEL_API_KEY", config.apiKey); env.callAttr("__setitem__", "EVE_MODEL_TIMEOUT", config.timeoutSeconds.toString())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() {
        isStopping = true
        startupHandler.removeCallbacksAndMessages(null)
        okHttp.dispatcher.cancelAll()
        try { eveInstance.callAttr("stop") } catch (_: Exception) {}
        uncaughtExceptionHandlerLease.restore()
        EveEventBus.emit(EveEvent.StatusChanged("EVE stopped"))
        super.onDestroy()
    }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    inner class LocalBinder : Binder() { fun getService(): EveService = this@EveService }

    fun submitTask(action: String, params: Map<String, String> = emptyMap(), taskId: String = UUID.randomUUID().toString().replace("-", "")) {
        if (isStopping) {
            EveEventBus.emit(EveEvent.LogLine("EVE service is stopping; task submit cancelled", "WARN"))
            return
        }
        submitTaskWhenHermesReady(action, params, taskId, 0)
    }

    private fun submitTaskWhenHermesReady(action: String, params: Map<String, String>, taskId: String, attempt: Int) {
        if (isStopping) return
        val tokenFile = File(filesDir, "hermes_token.txt"); val portFile = File(filesDir, "hermes_port.txt")
        val token = tokenFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val port = portFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        if (token.isBlank() || port == null) {
            retryHermesSubmit(action, params, taskId, attempt, "EVE runtime is still starting")
            return
        }
        val safeParams = params.toMutableMap().apply { put("task_id", taskId) }
        val body = JSONObject().apply { put("action", action); put("params", JSONObject(safeParams as Map<*, *>)) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("http://127.0.0.1:$port/command").addHeader("Authorization", "Bearer $token").post(body).build()
        okHttp.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { retryHermesSubmit(action, params, taskId, attempt, "Task submit failed ($action): ${e.message}") }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.use { val detail = response.body?.string().orEmpty().take(300); EveEventBus.emit(EveEvent.LogLine(if (response.isSuccessful) "Task accepted: $action (${response.code})" else "Task rejected: $action (${response.code}) $detail", if (response.isSuccessful) "INFO" else "ERROR")) } }
        })
    }

    private fun retryHermesSubmit(action: String, params: Map<String, String>, taskId: String, attempt: Int, message: String) {
        if (isStopping) return
        if (attempt >= MAX_HERMES_SUBMIT_ATTEMPTS) {
            EveEventBus.emit(EveEvent.LogLine("$message; giving up after ${attempt + 1} attempts", "ERROR"))
            return
        }
        val delayMs = HERMES_SUBMIT_RETRY_DELAYS_MS[attempt.coerceAtMost(HERMES_SUBMIT_RETRY_DELAYS_MS.lastIndex)]
        EveEventBus.emit(EveEvent.LogLine("$message; retrying task submit in ${delayMs}ms", "WARN"))
        startupHandler.postDelayed({ submitTaskWhenHermesReady(action, params, taskId, attempt + 1) }, delayMs)
    }

    fun controlPipeline(taskId: String, command: String, task: String = "", project: String = "default", stage: String = "Plan") =
        submitTask("agent_hub_control", mapOf("task_id" to taskId, "command" to command, "task" to task, "project" to project, "stage" to stage))

    fun cancelAgentHub() = submitTask("agent_hub_control", mapOf("command" to "cancel", "task_id" to "all"))

    fun testModelProvider(callback: (String) -> Unit) {
        val py = try { if (!Python.isStarted()) Python.start(AndroidPlatform(this)); Python.getInstance() } catch (e: Exception) { callback("Python runtime unavailable: ${e.message}"); return }
        Thread {
            try {
                val env = requireNotNull(py.getModule("os").get("environ")) { "Python os.environ is unavailable" }; applyModelProviderEnvironment(env); val config = ModelProviderStore(this).load()
                if (config.model.isBlank()) { callback("EVE needs a model selection. Choose Auto or a model first."); return@Thread }
                if (config.provider != "ollama" && config.apiKey.isBlank()) { callback("${config.provider} requires an API key. Add it in AI Models."); return@Thread }
                val provider = py.getModule("eve.model_provider").callAttr("build_provider", filesDir.absolutePath); val result = provider.callAttr("health_check").toString().trim()
                callback(if (result.equals("OK", true)) "✓ ${config.provider} connected — ${config.model}" else "✓ ${config.provider} responded: $result")
            } catch (e: Exception) { callback("Provider test failed: ${e.message ?: "unknown error"}") }
        }.start()
    }

    private fun buildNotification(): Notification { val channelId = "eve_channel"; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, "EVE Agent", NotificationManager.IMPORTANCE_LOW)); return NotificationCompat.Builder(this, channelId).setContentTitle("EVE Agent").setContentText("EVE Agent Hub running").setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build() }
    private fun isAccessibilityServiceEnabled(): Boolean { val value = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false; return value.contains("${packageName}/.VirtualAccessibilityService") }
    private fun installUncaughtExceptionHandler() {
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        uncaughtExceptionHandlerLease.install(Thread.UncaughtExceptionHandler { thread, throwable ->
            crashReporter.logException(throwable, "UncaughtException[${thread.name}]")
            EveEventBus.emit(EveEvent.LogLine("CRASH in ${thread.name}: ${throwable.message}", "ERROR"))
            existing?.uncaughtException(thread, throwable)
        })
    }
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val MAX_HERMES_SUBMIT_ATTEMPTS = 6
        private val HERMES_SUBMIT_RETRY_DELAYS_MS = longArrayOf(250L, 500L, 1_000L, 2_000L, 3_000L, 5_000L)
    }
}
