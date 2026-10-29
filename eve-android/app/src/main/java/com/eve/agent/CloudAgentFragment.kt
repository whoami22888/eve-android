package com.eve.agent

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One remote task at a time; original local Agent Hub remains operational. */
class CloudAgentFragment : Fragment() {
    private lateinit var store: CloudAgentSettingsStore
    private lateinit var endpointInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var taskInput: EditText
    private lateinit var status: TextView
    private lateinit var result: TextView
    private var currentRun: String? = null
    private var monitor: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        store = CloudAgentSettingsStore(requireContext())
        currentRun = savedInstanceState?.getString("remote_run")
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(10, 14, 20))
        }
        fun addText(value: String) = TextView(ctx).apply {
            text = value
            setTextColor(Color.WHITE)
            textSize = 16f
        }.also(root::addView)
        fun addField(hintText: String, initial: String = "") = EditText(ctx).apply {
            hint = hintText
            setText(initial)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
        }.also(root::addView)
        fun addButton(container: LinearLayout, title: String, click: () -> Unit) =
            Button(ctx).apply { text = title; setOnClickListener { click() } }.also(container::addView)

        val saved = store.load()
        addText("Cloud Agent — Hermes")
        endpointInput = addField("HTTPS bridge URL", saved?.url.orEmpty())
        tokenInput = addField("Bridge access token", saved?.token.orEmpty()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        status = addText("Set an HTTPS endpoint, then check connection")
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(top)
        addButton(top, "SAVE") {
            try {
                store.save(endpointInput.text.toString(), tokenInput.text.toString())
                status.text = "Connection saved securely"
            } catch (error: Exception) {
                status.text = error.message ?: "Unable to save settings"
            }
        }
        addButton(top, "TEST") { withClient { ready() } }
        addButton(top, "CLEAR") {
            store.clear()
            tokenInput.setText("")
            status.text = "Connection cleared"
        }
        taskInput = addField("Task for cloud agent")
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(actions)
        addButton(actions, "RUN") { start() }
        addButton(actions, "STOP") { withClient { stopRun(currentRun ?: error("No active run")) } }
        addButton(actions, "REFRESH") { withClient { getRun(currentRun ?: error("No saved run")) } }
        val scroll = ScrollView(ctx)
        result = TextView(ctx).apply { textSize = 13f; setTextColor(Color.WHITE) }
        scroll.addView(result)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (currentRun != null) status.text = "Previous task: $currentRun — press Refresh"
        return root
    }

    private fun withClient(action: HermesCloudClient.() -> org.json.JSONObject) {
        val settings = store.load()
        if (settings == null) {
            status.text = "Save endpoint and access token first"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    HermesCloudClient(settings.url, settings.token).action()
                }
                result.text = snapshot.toString(2).take(30000)
                status.text = snapshot.optString("status", "Connected")
            } catch (error: Exception) {
                status.text = "Cloud operation failed: ${error.message ?: "unknown"}"
            }
        }
    }

    private fun start() {
        val settings = store.load()
        if (settings == null) { status.text = "Configure cloud access first"; return }
        val task = taskInput.text.toString().trim()
        if (task.isEmpty()) { status.text = "Enter a task"; return }
        if (monitor?.isActive == true) { status.text = "Task monitor is already running"; return }
        status.text = "Submitting..."
        monitor = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val client = HermesCloudClient(settings.url, settings.token)
                val id = withContext(Dispatchers.IO) { client.startRun(task) }
                currentRun = id
                status.text = "Running $id"
                while (isActive) {
                    val snapshot = withContext(Dispatchers.IO) { client.getRun(id) }
                    val state = snapshot.optString("status", "running")
                    status.text = "$state: $id"
                    result.text = snapshot.toString(2).take(30000)
                    if (state in setOf("completed", "failed", "cancelled", "stopped", "interrupted")) break
                    delay(2000)
                }
            } catch (error: Exception) {
                if (isActive) status.text = "Cloud task failed: ${error.message ?: "unknown"}"
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentRun?.let { outState.putString("remote_run", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        monitor?.cancel()
        monitor = null
        super.onDestroyView()
    }
}
