package com.eve.agent

/**
 * Formal state machine for Agent Hub pipeline tasks.
 *
 * States are durable and user-visible. Transitions are guarded by
 * preconditions; invalid transitions are rejected with [IllegalStateException].
 *
 * See TASK_STATE_MACHINE.md for the full specification.
 */
enum class TaskState {
    /** Task accepted, waiting for worker thread. */
    QUEUED,

    /** Worker thread active, executing pipeline stages. */
    RUNNING,

    /** User requested pause; worker is blocked at next checkpoint. */
    PAUSED,

    /** User requested cancel; worker is unwinding. */
    CANCELLING,

    /** Pipeline finished successfully. */
    COMPLETED,

    /** Pipeline failed with an error. */
    FAILED,

    /** Process death or runtime restart interrupted the task. */
    INTERRUPTED,

    /** Task was cancelled before it could start. */
    CANCELLED;

    companion object {
        /** Valid transitions with their guard descriptions. */
        private val VALID_TRANSITIONS: Map<TaskState, Set<TaskState>> = mapOf(
            QUEUED to setOf(RUNNING, CANCELLED, INTERRUPTED),
            RUNNING to setOf(PAUSED, CANCELLING, COMPLETED, FAILED, INTERRUPTED),
            PAUSED to setOf(RUNNING, CANCELLING, INTERRUPTED),
            CANCELLING to setOf(CANCELLED, FAILED, INTERRUPTED),
            COMPLETED to emptySet(),
            FAILED to setOf(QUEUED),  // retry
            INTERRUPTED to setOf(QUEUED),  // retry
            CANCELLED to emptySet()
        )

        /** Human-readable guard for each transition. */
        private val TRANSITION_GUARDS: Map<Pair<TaskState, TaskState>, String> = mapOf(
            (QUEUED to RUNNING) to "Worker thread available and task dequeued",
            (QUEUED to CANCELLED) to "Cancel requested before worker started",
            (QUEUED to INTERRUPTED) to "Process death before worker started",
            (RUNNING to PAUSED) to "Pause requested; worker at checkpoint",
            (RUNNING to CANCELLING) to "Cancel requested; worker unwinding",
            (RUNNING to COMPLETED) to "All stages finished without error",
            (RUNNING to FAILED) to "Unrecoverable error in any stage",
            (RUNNING to INTERRUPTED) to "Process death during execution",
            (PAUSED to RUNNING) to "Resume requested; worker unblocked",
            (PAUSED to CANCELLING) to "Cancel requested while paused",
            (PAUSED to INTERRUPTED) to "Process death while paused",
            (CANCELLING to CANCELLED) to "Worker finished unwinding",
            (CANCELLING to FAILED) to "Error during cancellation unwind",
            (CANCELLING to INTERRUPTED) to "Process death during cancellation",
            (FAILED to QUEUED) to "Retry requested with valid task spec",
            (INTERRUPTED to QUEUED) to "Retry requested after process restart"
        )

        /**
         * Check whether a transition is valid.
         *
         * @param from current state
         * @param to desired state
         * @return true if the transition is allowed
         */
        fun isValidTransition(from: TaskState, to: TaskState): Boolean =
            VALID_TRANSITIONS[from]?.contains(to) == true

        /**
         * Get the guard description for a transition.
         *
         * @param from current state
         * @param to desired state
         * @return guard description, or null if transition is invalid
         */
        fun transitionGuard(from: TaskState, to: TaskState): String? =
            TRANSITION_GUARDS[from to to]

        /**
         * Assert that a transition is valid, throwing [IllegalStateException] if not.
         *
         * @param from current state
         * @param to desired state
         * @throws IllegalStateException if the transition is invalid
         */
        fun requireValidTransition(from: TaskState, to: TaskState) {
            if (!isValidTransition(from, to)) {
                throw IllegalStateException(
                    "Invalid task state transition: $from -> $to. " +
                    "Valid transitions from $from: ${VALID_TRANSITIONS[from] ?: "none"}"
                )
            }
        }

        /**
         * Get all valid next states from a given state.
         *
         * @param from current state
         * @return set of valid next states (empty if terminal)
         */
        fun validNextStates(from: TaskState): Set<TaskState> =
            VALID_TRANSITIONS[from] ?: emptySet()

        /**
         * Check whether a state is terminal (no outgoing transitions).
         *
         * @param state the state to check
         * @return true if the state is terminal
         */
        fun isTerminal(state: TaskState): Boolean =
            VALID_TRANSITIONS[state].isNullOrEmpty()

        /**
         * Check whether a state is active (task is still being processed).
         *
         * @param state the state to check
         * @return true if the state is active
         */
        fun isActive(state: TaskState): Boolean =
            state in setOf(QUEUED, RUNNING, PAUSED, CANCELLING)

        /**
         * Check whether a state represents a failure.
         *
         * @param state the state to check
         * @return true if the state is a failure state
         */
        fun isFailure(state: TaskState): Boolean =
            state in setOf(FAILED, INTERRUPTED, CANCELLED)

        /**
         * Parse a state from its string representation (case-insensitive).
         *
         * @param value string representation
         * @return the parsed state, or null if invalid
         */
        fun fromString(value: String): TaskState? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
