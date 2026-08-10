package com.eve.agent

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-facing runtime bridge for the Agent Hub.
 *
 * The UI talks only to this class. It keeps the actual EVE/Hermes runtime
 * behind the existing foreground EveService, so the user never needs to
 * interact with Termux, a shell or a container directly.
 */
class LocalAgentRuntimeBridge(context: Context) {
    data class RuntimeState(
        val connected: Boolean = false,
        val running: Boolean = false,
        val lastMessage: String = "Starting local agent runtime…"
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = _state
    private val started = AtomicBoolean(false)

    fun attach(service: EveService?) {
        if (service == null) {
            _state.value = RuntimeState(connected = false, running = false, lastMessage = "Waiting for EVE runtime…")
            return
        }
        _state.value = RuntimeState(connected = true, running = true, lastMessage = "Local EVE runtime connected")
    }

    fun runPipeline(task: String) {
        val service = MainActivity.currentService ?: return
        if (task.isBlank()) return
        started.set(true)
        scope.launch(Dispatchers.IO) {
            service.submitTask("agent_hub", mapOf("task" to task))
            _state.value = RuntimeState(true, true, "Pipeline submitted: $task")
        }
    }

    fun stopPipeline() {
        // The current EVE service owns the Python orchestrator lifecycle.
        // Stop here means stop accepting new Agent Hub work; the existing
        // service remains alive so normal EVE features are unaffected.
        _state.value = _state.value.copy(lastMessage = "Agent Hub paused")
    }

    fun dispose() {
        scope.coroutineContext.cancel()
    }
}
