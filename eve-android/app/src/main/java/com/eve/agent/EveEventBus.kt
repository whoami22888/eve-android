package com.eve.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// ─────────────────────────────────────────────────────────────────────────────
// Event types
// ─────────────────────────────────────────────────────────────────────────────

sealed class EveEvent {
    /** Top-level service lifecycle text for the Dashboard status row. */
    data class StatusChanged(val status: String) : EveEvent()

    /** A single log line emitted by the Python orchestrator or agents. */
    data class LogLine(val message: String, val level: String = "INFO") : EveEvent()

    /** A task completed (or failed) — forwarded to the History tab. */
    data class TaskCompleted(
        val taskId: String,
        val action: String,
        val result: String,
        val failed: Boolean = false
    ) : EveEvent()
}

// ─────────────────────────────────────────────────────────────────────────────
// Bus
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Application-scoped event bus that decouples [EveService] from the UI layer.
 *
 * Usage:
 *   Emit (from any thread):  EveEventBus.emit(EveEvent.StatusChanged("running"))
 *   Observe (from Fragment): viewLifecycleOwner.lifecycleScope.launch {
 *                                EveEventBus.events.collect { ... }
 *                            }
 *
 * [replay] = 50 means new subscribers immediately see the last 50 events,
 * so a fragment re-created after rotation catches up without needing a
 * separate persistence layer.
 */
object EveEventBus {
    private val _events = MutableSharedFlow<EveEvent>(
        replay = 50,
        extraBufferCapacity = 200
    )
    val events: SharedFlow<EveEvent> = _events.asSharedFlow()

    /**
     * Non-suspending emit — safe to call from any thread, including the Python
     * Chaquopy bridge thread.  Returns false only if the internal buffer is
     * full (extremely unlikely at 200-event capacity).
     */
    fun emit(event: EveEvent): Boolean = _events.tryEmit(event)
}
