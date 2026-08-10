package com.eve.agent

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Normal Eve screen for the multi-agent workspace. */
class AgentHubFragment : Fragment() {
    private lateinit var bridge: LocalAgentRuntimeBridge
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logText: TextView

    private val agents = listOf(
        "Planner" to "Orchestrates the task",
        "Coder" to "Implements changes",
        "Reviewer" to "Reviews the work",
        "Tester" to "Runs verification",
        "Security" to "Checks security"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = LocalAgentRuntimeBridge(requireContext())
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(10, 14, 20))
        }
        root.addView(TextView(context).apply {
            text = "EVE AGENT HUB"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        statusText = TextView(context).apply {
            text = "Connecting to local runtime…"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 8, 0, 18)
        }
        root.addView(statusText)
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }
        root.addView(progress, LinearLayout.LayoutParams(-1, 12))
        root.addView(TextView(context).apply {
            text = "\nCurrent project\nEve Android"
            textSize = 16f
            setTextColor(Color.WHITE)
        })
        agents.forEachIndexed { index, (name, role) -> root.addView(agentCard(name, role, index)) }

        val taskInput = android.widget.EditText(context).apply {
            hint = "Tell Eve what to build…"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(false)
            minLines = 2
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.rgb(28, 34, 43))
        }
        root.addView(taskInput, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 18 })
        val actions = LinearLayout(context).apply { gravity = Gravity.END; orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(context).apply { text = "PAUSE"; setOnClickListener { bridge.stopPipeline() } })
        actions.addView(Button(context).apply {
            text = "RUN AGENTS"
            setOnClickListener {
                val task = taskInput.text.toString().trim()
                if (task.isNotEmpty()) bridge.runPipeline(task)
            }
        })
        root.addView(actions)
        logText = TextView(context).apply {
            text = "Agent activity will appear here."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 16, 0, 16)
        }
        root.addView(ScrollView(context).apply { addView(logText) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun agentCard(name: String, role: String, index: Int): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.rgb(20, 26, 34))
        }
        row.addView(TextView(context).apply {
            text = "$name\n$role"
            textSize = 15f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = if (index == 0) "READY" else "WAITING"
            textSize = 12f
            setTextColor(if (index == 0) Color.GREEN else Color.GRAY)
        })
        return row.apply {
            layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 }
        }
    }

    override fun onStart() {
        super.onStart()
        bridge.attach(MainActivity.currentService)
        viewLifecycleOwner.lifecycleScope.launch {
            bridge.state.collect { state ->
                statusText.text = state.lastMessage
                progress.progress = state.progress
                logText.text = state.lastMessage
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            EveEventBus.events.collect { event ->
                bridge.updateFromEvent(event)
                when (event) {
                    is EveEvent.LogLine -> logText.text = "${event.level}: ${event.message}"
                    is EveEvent.TaskCompleted -> logText.text = if (event.failed) "FAILED: ${event.result}" else "COMPLETED: ${event.result}"
                    is EveEvent.StatusChanged -> logText.text = event.status
                }
            }
        }
    }

    override fun onDestroyView() {
        // Do not cancel the bridge here. The Fragment may recreate its view
        // while retaining the same bridge instance.
        super.onDestroyView()
    }
}
