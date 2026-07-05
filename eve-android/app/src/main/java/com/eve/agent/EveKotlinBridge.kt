package com.eve.agent

/**
 * EveKotlinBridge exposes static JVM methods so Python agent code can push
 * events into the Kotlin UI layer via Chaquopy's jclass() bridge.
 *
 * Python usage (in any agent or orchestrator file):
 *
 *   from java import jclass
 *   Bridge = jclass("com.eve.agent.EveKotlinBridge")
 *
 *   Bridge.onLogLine("EVE started", "INFO")
 *   Bridge.onTaskCompleted(task.id, task.action, task.result or "", False)
 *
 * All methods are @JvmStatic and thread-safe (EveEventBus.emit is non-blocking).
 */
object EveKotlinBridge {

    /** Forward a log line from Python to the UI event bus. */
    @JvmStatic
    fun onLogLine(message: String, level: String) {
        EveEventBus.emit(EveEvent.LogLine(message, level))
    }

    /** Notify the UI that a task finished (or failed). */
    @JvmStatic
    fun onTaskCompleted(taskId: String, action: String, result: String, failed: Boolean) {
        EveEventBus.emit(EveEvent.TaskCompleted(taskId, action, result, failed))
    }

    /** Update the one-line status shown in the Dashboard tab. */
    @JvmStatic
    fun onStatusChanged(status: String) {
        EveEventBus.emit(EveEvent.StatusChanged(status))
    }
}
