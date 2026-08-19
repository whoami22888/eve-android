package com.eve.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Backward-compatible runtime bridge; AgentHubFragment now uses EveViewModel directly. */
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
        _state.value = if (service == null) RuntimeState(lastMessage = "Waiting for EVE runtime…")
        else RuntimeState(connected = true, lastMessage = "Local EVE runtime connected")
    }

    fun runPipeline(task: String) {
        val service = MainActivity.currentService ?: run { _state.value = _state.value.copy(lastMessage = "EVE runtime is not connected"); return }
        if (task.isBlank()) return
        _state.value = _state.value.copy(connected = true, running = true, progress = 5, lastMessage = "Submitting Agent Hub pipeline…")
        service.submitTask("agent_hub", mapOf("task" to task))
    }

    fun stopPipeline() {
        _state.value = _state.value.copy(running = false, lastMessage = "Agent Hub cancellation requested")
    }

    fun updateFromEvent(event: EveEvent) {
        when (event) {
            is EveEvent.StatusChanged -> _state.value = _state.value.copy(lastMessage = event.status)
            is EveEvent.LogLine -> _state.value = _state.value.copy(lastMessage = event.message)
            is EveEvent.PipelineStage -> _state.value = _state.value.copy(
                running = event.status !in setOf("completed", "failed", "cancelled"),
                progress = event.progress,
                lastMessage = "${event.stage}: ${event.message}"
            )
            is EveEvent.TaskCompleted -> if (event.action == "agent_hub") _state.value = _state.value.copy(
                running = false, progress = if (event.failed) 0 else 100,
                lastMessage = if (event.failed) "Agent Hub failed: ${event.result.take(300)}" else "Agent Hub completed"
            )
        }
    }

    fun dispose() = Unit
}
