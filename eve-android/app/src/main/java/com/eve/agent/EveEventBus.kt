package com.eve.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class EveEvent {
    data class StatusChanged(val status: String) : EveEvent()
    data class LogLine(val message: String, val level: String = "INFO") : EveEvent()
    data class TaskCompleted(
        val taskId: String,
        val action: String,
        val result: String,
        val failed: Boolean = false
    ) : EveEvent()
    data class PipelineStage(
        val taskId: String,
        val stage: String,
        val status: String,
        val progress: Int,
        val message: String
    ) : EveEvent()
}

object EveEventBus {
    private val _events = MutableSharedFlow<EveEvent>(replay = 50, extraBufferCapacity = 200)
    val events: SharedFlow<EveEvent> = _events.asSharedFlow()

    fun emit(event: EveEvent): Boolean = _events.tryEmit(event)
}
