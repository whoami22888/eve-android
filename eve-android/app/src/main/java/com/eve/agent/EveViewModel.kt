package com.eve.agent

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * EveViewModel is the single source of truth for UI state across all four tabs.
 *
 * It collects from [EveEventBus] and exposes stable [LiveData] so fragments
 * survive rotation without re-subscribing to the bus themselves.
 *
 * Scoped to the Activity lifecycle (obtained via `by activityViewModels()`
 * in each Fragment).
 */
class EveViewModel : ViewModel() {

    // ── Dashboard ────────────────────────────────────────────────────────────

    private val _status = MutableLiveData("Connecting…")
    /** One-line status displayed at the top of the Dashboard tab. */
    val status: LiveData<String> = _status

    private val _agentStatus = MutableLiveData("Agents: initialising…")
    val agentStatus: LiveData<String> = _agentStatus

    // ── Log (last 200 lines, shown in Dashboard) ─────────────────────────────

    private val _logLines = MutableLiveData<List<String>>(emptyList())
    val logLines: LiveData<List<String>> = _logLines

    // ── Task history (last 500 tasks, shown in History tab) ──────────────────

    private val _taskHistory = MutableLiveData<List<HistoryItem>>(emptyList())
    val taskHistory: LiveData<List<HistoryItem>> = _taskHistory

    // ─────────────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            EveEventBus.events.collect { event ->
                when (event) {
                    is EveEvent.StatusChanged -> {
                        _status.postValue(event.status)
                    }
                    is EveEvent.LogLine -> {
                        val line = "[${event.level}] ${event.message}"
                        val current = _logLines.value ?: emptyList()
                        _logLines.postValue((current + line).takeLast(200))
                    }
                    is EveEvent.TaskCompleted -> {
                        val item = HistoryItem(
                            taskId  = event.taskId,
                            action  = event.action,
                            result  = event.result,
                            failed  = event.failed
                        )
                        val current = _taskHistory.value ?: emptyList()
                        // Prepend newest first, cap at 500
                        _taskHistory.postValue((listOf(item) + current).take(500))
                        // Reflect agent status
                        _agentStatus.postValue(
                            if (event.failed) "Last task failed: ${event.action}"
                            else "Last task done: ${event.action}"
                        )
                    }
                }
            }
        }
    }
}
