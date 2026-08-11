"""Formal state machine for Agent Hub pipeline tasks.

States are durable and user-visible. Transitions are guarded by
preconditions; invalid transitions raise ValueError.

See TASK_STATE_MACHINE.md for the full specification.
"""

from __future__ import annotations

from enum import Enum
from typing import Dict, Optional, Set


class TaskState(Enum):
    """Agent Hub pipeline task states."""

    QUEUED = "queued"
    RUNNING = "running"
    PAUSED = "paused"
    CANCELLING = "cancelling"
    COMPLETED = "completed"
    FAILED = "failed"
    INTERRUPTED = "interrupted"
    CANCELLED = "cancelled"


# Valid transitions with their guard descriptions.
VALID_TRANSITIONS: Dict[TaskState, Set[TaskState]] = {
    TaskState.QUEUED: {TaskState.RUNNING, TaskState.CANCELLED, TaskState.INTERRUPTED},
    TaskState.RUNNING: {TaskState.PAUSED, TaskState.CANCELLING, TaskState.COMPLETED, TaskState.FAILED, TaskState.INTERRUPTED},
    TaskState.PAUSED: {TaskState.RUNNING, TaskState.CANCELLING, TaskState.INTERRUPTED},
    TaskState.CANCELLING: {TaskState.CANCELLED, TaskState.FAILED, TaskState.INTERRUPTED},
    TaskState.COMPLETED: set(),
    TaskState.FAILED: {TaskState.QUEUED},  # retry
    TaskState.INTERRUPTED: {TaskState.QUEUED},  # retry
    TaskState.CANCELLED: set(),
}

TRANSITION_GUARDS: Dict[tuple, str] = {
    (TaskState.QUEUED, TaskState.RUNNING): "Worker thread available and task dequeued",
    (TaskState.QUEUED, TaskState.CANCELLED): "Cancel requested before worker started",
    (TaskState.QUEUED, TaskState.INTERRUPTED): "Process death before worker started",
    (TaskState.RUNNING, TaskState.PAUSED): "Pause requested; worker at checkpoint",
    (TaskState.RUNNING, TaskState.CANCELLING): "Cancel requested; worker unwinding",
    (TaskState.RUNNING, TaskState.COMPLETED): "All stages finished without error",
    (TaskState.RUNNING, TaskState.FAILED): "Unrecoverable error in any stage",
    (TaskState.RUNNING, TaskState.INTERRUPTED): "Process death during execution",
    (TaskState.PAUSED, TaskState.RUNNING): "Resume requested; worker unblocked",
    (TaskState.PAUSED, TaskState.CANCELLING): "Cancel requested while paused",
    (TaskState.PAUSED, TaskState.INTERRUPTED): "Process death while paused",
    (TaskState.CANCELLING, TaskState.CANCELLED): "Worker finished unwinding",
    (TaskState.CANCELLING, TaskState.FAILED): "Error during cancellation unwind",
    (TaskState.CANCELLING, TaskState.INTERRUPTED): "Process death during cancellation",
    (TaskState.FAILED, TaskState.QUEUED): "Retry requested with valid task spec",
    (TaskState.INTERRUPTED, TaskState.QUEUED): "Retry requested after process restart",
}


def is_valid_transition(from_state: TaskState, to_state: TaskState) -> bool:
    """Check whether a transition is valid."""
    return to_state in VALID_TRANSITIONS.get(from_state, set())


def transition_guard(from_state: TaskState, to_state: TaskState) -> Optional[str]:
    """Get the guard description for a transition."""
    return TRANSITION_GUARDS.get((from_state, to_state))


def require_valid_transition(from_state: TaskState, to_state: TaskState) -> None:
    """Assert that a transition is valid, raising ValueError if not."""
    if not is_valid_transition(from_state, to_state):
        valid = VALID_TRANSITIONS.get(from_state, set())
        raise ValueError(
            f"Invalid task state transition: {from_state.value} -> {to_state.value}. "
            f"Valid transitions from {from_state.value}: {[s.value for s in valid] or 'none'}"
        )


def valid_next_states(from_state: TaskState) -> Set[TaskState]:
    """Get all valid next states from a given state."""
    return VALID_TRANSITIONS.get(from_state, set())


def is_terminal(state: TaskState) -> bool:
    """Check whether a state is terminal (no outgoing transitions)."""
    return not VALID_TRANSITIONS.get(state)


def is_active(state: TaskState) -> bool:
    """Check whether a state is active (task is still being processed)."""
    return state in {TaskState.QUEUED, TaskState.RUNNING, TaskState.PAUSED, TaskState.CANCELLING}


def is_failure(state: TaskState) -> bool:
    """Check whether a state represents a failure."""
    return state in {TaskState.FAILED, TaskState.INTERRUPTED, TaskState.CANCELLED}


def from_string(value: str) -> Optional[TaskState]:
    """Parse a state from its string representation (case-insensitive)."""
    for state in TaskState:
        if state.value == value.lower():
            return state
    return None
