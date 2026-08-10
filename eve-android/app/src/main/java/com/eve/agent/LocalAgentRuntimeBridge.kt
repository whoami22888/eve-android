package com.eve.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Android-facing runtime bridge for the Agent Hub. */
class LocalAgentRuntimeBridge(context: Context) {
    data class RuntimeState(
        val connected: Boolean = false,
        val running: Boolean = false,
        val progress: Int = 0,
        val lastMessage: String = "Starting local agent runtime…"
    )

    @Suppress("UNUSED_VARIABLE")
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state

    fun attach(service: EveService?) {
        if (service == null) {
            _state.value = RuntimeState(lastMessage = "Waiting for EVE runtime…")
            return
        }
        _state.value = RuntimeState(connected = true, running = false, progress = 0, lastMessage = "Local EVE runtime connected")
    }

    fun runPipeline(task: String) {
        val service = MainActivity.currentService ?: run {
            _state.value = _state.value.copy(connected = false, lastMessage = "EVE runtime is not connected")
            return
        }
        if (task.isBlank() || _state.value.running) return
        _state.value = _state.value.copy(connected = true, running = true, progress = 5, lastMessage = "Submitting Agent Hub pipeline…")
        service.submitTask("agent_hub", mapOf("task" to task))
    }

    fun stopPipeline() {
        MainActivity.currentService?.cancelAgentHub()
        _state.value = _state.value.copy(running = false, progress = 0, lastMessage = "Agent Hub cancellation requested")
    }

    fun updateFromEvent(event: EveEvent) {
        when (event) {
            is EveEvent.StatusChanged -> _state.value = _state.value.copy(lastMessage = event.status)
            is EveEvent.LogLine -> {
                val message = event.message
                val stageProgress = when {
                    message.contains("planner", true) -> 20
                    message.contains("coder", true) -> 40
                    message.contains("reviewer", true) -> 60
                    message.contains("tester", true) -> 80
                    message.contains("security", true) -> 90
                    else -> _state.value.progress
                }
                _state.value = _state.value.copy(lastMessage = message, progress = stageProgress)
            }
            is EveEvent.TaskCompleted -> if (event.action == "agent_hub") {
                _state.value = _state.value.copy(
                    running = false,
                    progress = if (event.failed) 0 else 100,
                    lastMessage = if (event.failed) "Agent Hub failed: ${event.result.take(300)}" else "Agent Hub completed"
                )
            }
        }
    }

    fun dispose() {
        // No view-owned scope exists; the bridge remains valid after recreation.
    }
}
