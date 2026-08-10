package com.eve.agent

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import java.util.UUID

/** Lifecycle-aware Agent Hub pipeline control center. */
class AgentHubFragment : Fragment() {
    private val viewModel: EveViewModel by activityViewModels()
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var stageText: TextView
    private lateinit var historyText: TextView
    private var activeTaskId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24); setBackgroundColor(Color.rgb(10, 14, 20)) }
        root.addView(TextView(context).apply { text = "EVE PIPELINE CONTROL CENTER"; textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        statusText = TextView(context).apply { text = "Ready"; textSize = 14f; setTextColor(Color.LTGRAY); setPadding(0, 8, 0, 8) }; root.addView(statusText)
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0 }; root.addView(progress, LinearLayout.LayoutParams(-1, 12))
        stageText = TextView(context).apply { text = "Plan → Research → Code → Test → Review"; textSize = 14f; setTextColor(Color.WHITE); setPadding(0, 10, 0, 12) }; root.addView(stageText)
        val taskInput = EditText(context).apply { hint = "Tell EVE what to build…"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); minLines = 2; setPadding(16, 12, 16, 12); setBackgroundColor(Color.rgb(28, 34, 43)) }; root.addView(taskInput)
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun add(label: String, action: () -> Unit) { controls.addView(Button(context).apply { text = label; setOnClickListener { action() } }) }
        add("RUN") {
            val task = taskInput.text.toString().trim(); val service = MainActivity.currentService
            if (task.isEmpty() || service == null) statusText.text = if (service == null) "EVE runtime is not connected" else "Enter a task first"
            else { val id = UUID.randomUUID().toString().replace("-", ""); activeTaskId = id; viewModel.registerPipeline(id, task); service.submitTask("agent_hub", mapOf("task" to task, "project" to "default"), id) }
        }
        fun control(command: String) {
            val id = activeTaskId ?: return
            val run = viewModel.pipelineRuns.value?.firstOrNull { it.taskId == id }
            MainActivity.currentService?.controlPipeline(id, command, run?.task.orEmpty(), run?.project ?: "default", run?.stage ?: "Plan")
        }
        add("PAUSE") { control("pause") }; add("RESUME") { control("resume") }; add("CANCEL") { control("cancel") }; add("RETRY") { control("retry") }
        root.addView(controls)
        root.addView(TextView(context).apply { text = "Pipeline history"; textSize = 16f; setTextColor(Color.WHITE); setPadding(0, 16, 0, 6) })
        val scroll = ScrollView(context); historyText = TextView(context).apply { text = "No pipeline runs yet."; textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 8, 0, 16) }; scroll.addView(historyText); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    override fun onStart() {
        super.onStart()
        viewModel.pipelineRuns.observe(viewLifecycleOwner) { runs ->
            if (runs.isNotEmpty() && activeTaskId == null) activeTaskId = runs.first().taskId
            val active = runs.firstOrNull { it.taskId == activeTaskId } ?: runs.firstOrNull()
            if (active != null) { statusText.text = "${active.status.uppercase()} • ${active.message} • ${active.taskId.take(12)}"; progress.progress = active.progress; stageText.text = "Plan → Research → Code → Test → Review\nCurrent: ${active.stage}" }
            historyText.text = runs.joinToString("\n\n") { r -> "${r.taskId}\n${r.status.uppercase()} • ${r.progress}% • ${r.stage}\n${r.message}${if (r.error.isNotBlank()) "\nError: ${r.error}" else ""}" }
        }
    }
}
