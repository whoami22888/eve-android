package com.eve.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** Activity-scoped source of truth for dashboard, pipeline and history state. */
class EveViewModel(application: Application) : AndroidViewModel(application) {
    private val pipelineStore = PipelineStore(application)

    private val _status = MutableLiveData("Connecting…")
    val status: LiveData<String> = _status
    private val _agentStatus = MutableLiveData("Agents: initialising…")
    val agentStatus: LiveData<String> = _agentStatus
    private val _logLines = MutableLiveData<List<String>>(emptyList())
    val logLines: LiveData<List<String>> = _logLines
    private val _taskHistory = MutableLiveData<List<HistoryItem>>(emptyList())
    val taskHistory: LiveData<List<HistoryItem>> = _taskHistory
    private val _pipelineRuns = MutableLiveData<List<PipelineStore.Run>>(pipelineStore.load())
    val pipelineRuns: LiveData<List<PipelineStore.Run>> = _pipelineRuns

    init {
        // A process death cannot safely resume a running Python request. Mark it
        // interrupted rather than falsely showing it as active.
        val recovered = pipelineStore.load().map {
            if (it.status == "running" || it.status == "queued" || it.status == "paused") {
                it.copy(status = "interrupted", message = "Runtime restarted; retry is available", updatedAt = System.currentTimeMillis())
            } else it
        }
        recovered.forEach(pipelineStore::upsert)
        _pipelineRuns.value = pipelineStore.load()

        viewModelScope.launch {
            EveEventBus.events.collect { event ->
                when (event) {
                    is EveEvent.StatusChanged -> _status.postValue(event.status)
                    is EveEvent.LogLine -> {
                        val line = "[${event.level}] ${event.message}"
                        val current = _logLines.value ?: emptyList()
                        _logLines.postValue((current + line).takeLast(200))
                    }
                    is EveEvent.PipelineStage -> {
                        val existing = pipelineStore.load().firstOrNull { it.taskId == event.taskId }
                        pipelineStore.upsert(PipelineStore.Run(
                            taskId = event.taskId,
                            task = existing?.task ?: "Agent Hub pipeline",
                            project = existing?.project ?: "default",
                            stage = event.stage,
                            status = event.status,
                            progress = event.progress,
                            message = event.message,
                            output = existing?.output.orEmpty(),
                            error = if (event.status == "failed") event.message else existing?.error.orEmpty(),
                            startedAt = existing?.startedAt ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        ))
                        _pipelineRuns.postValue(pipelineStore.load())
                    }
                    is EveEvent.TaskCompleted -> {
                        val item = HistoryItem(event.taskId, event.action, event.result, event.failed)
                        val current = _taskHistory.value ?: emptyList()
                        _taskHistory.postValue((listOf(item) + current).take(500))
                        _agentStatus.postValue(if (event.failed) "Last task failed: ${event.action}" else "Last task done: ${event.action}")
                        val existing = pipelineStore.load().firstOrNull { it.taskId == event.taskId }
                        if (existing != null) {
                            pipelineStore.upsert(existing.copy(
                                status = if (event.failed) "failed" else "completed",
                                progress = if (event.failed) existing.progress else 100,
                                message = if (event.failed) event.result else "Pipeline completed",
                                output = if (event.failed) existing.output else event.result,
                                error = if (event.failed) event.result else "",
                                updatedAt = System.currentTimeMillis()
                            ))
                            _pipelineRuns.postValue(pipelineStore.load())
                        }
                    }
                }
            }
        }
    }

    fun registerPipeline(taskId: String, task: String, project: String = "default") {
        pipelineStore.upsert(PipelineStore.Run(taskId, task, project, "Plan", "queued", 0, "Pipeline queued"))
        _pipelineRuns.value = pipelineStore.load()
    }
}
