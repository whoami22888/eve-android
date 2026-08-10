package com.eve.agent

/**
 * Static JVM bridge used by Chaquopy Python code to publish runtime events.
 */
object EveKotlinBridge {
    @JvmStatic
    fun onLogLine(message: String, level: String) {
        EveEventBus.emit(EveEvent.LogLine(message, level))
    }

    @JvmStatic
    fun onTaskCompleted(taskId: String, action: String, result: String, failed: Boolean) {
        EveEventBus.emit(EveEvent.TaskCompleted(taskId, action, result, failed))
    }

    @JvmStatic
    fun onStatusChanged(status: String) {
        EveEventBus.emit(EveEvent.StatusChanged(status))
    }

    @JvmStatic
    fun onPipelineStage(taskId: String, stage: String, status: String, progress: Int, message: String) {
        EveEventBus.emit(EveEvent.PipelineStage(taskId, stage, status, progress.coerceIn(0, 100), message))
    }
}
