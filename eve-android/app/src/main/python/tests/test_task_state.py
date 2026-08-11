"""Unit tests for the Agent Hub task state machine."""

import unittest

from eve.task_state import (
    TaskState,
    from_string,
    is_active,
    is_failure,
    is_terminal,
    is_valid_transition,
    require_valid_transition,
    transition_guard,
    valid_next_states,
)


class TaskStateTests(unittest.TestCase):
    def test_all_states_defined(self):
        self.assertEqual(len(TaskState), 8)
        self.assertEqual(
            {s.value for s in TaskState},
            {"queued", "running", "paused", "cancelling", "completed", "failed", "interrupted", "cancelled"},
        )

    def test_valid_transitions_from_queued(self):
        self.assertEqual(valid_next_states(TaskState.QUEUED), {TaskState.RUNNING, TaskState.CANCELLED, TaskState.INTERRUPTED})

    def test_valid_transitions_from_running(self):
        self.assertEqual(
            valid_next_states(TaskState.RUNNING),
            {TaskState.PAUSED, TaskState.CANCELLING, TaskState.COMPLETED, TaskState.FAILED, TaskState.INTERRUPTED},
        )

    def test_valid_transitions_from_paused(self):
        self.assertEqual(valid_next_states(TaskState.PAUSED), {TaskState.RUNNING, TaskState.CANCELLING, TaskState.INTERRUPTED})

    def test_valid_transitions_from_cancelling(self):
        self.assertEqual(valid_next_states(TaskState.CANCELLING), {TaskState.CANCELLED, TaskState.FAILED, TaskState.INTERRUPTED})

    def test_terminal_states(self):
        self.assertTrue(is_terminal(TaskState.COMPLETED))
        self.assertTrue(is_terminal(TaskState.CANCELLED))
        self.assertFalse(is_terminal(TaskState.FAILED))
        self.assertFalse(is_terminal(TaskState.INTERRUPTED))

    def test_active_states(self):
        self.assertTrue(is_active(TaskState.QUEUED))
        self.assertTrue(is_active(TaskState.RUNNING))
        self.assertTrue(is_active(TaskState.PAUSED))
        self.assertTrue(is_active(TaskState.CANCELLING))
        self.assertFalse(is_active(TaskState.COMPLETED))
        self.assertFalse(is_active(TaskState.FAILED))

    def test_failure_states(self):
        self.assertTrue(is_failure(TaskState.FAILED))
        self.assertTrue(is_failure(TaskState.INTERRUPTED))
        self.assertTrue(is_failure(TaskState.CANCELLED))
        self.assertFalse(is_failure(TaskState.COMPLETED))
        self.assertFalse(is_failure(TaskState.RUNNING))

    def test_retry_transitions(self):
        self.assertTrue(is_valid_transition(TaskState.FAILED, TaskState.QUEUED))
        self.assertTrue(is_valid_transition(TaskState.INTERRUPTED, TaskState.QUEUED))
        self.assertFalse(is_valid_transition(TaskState.COMPLETED, TaskState.QUEUED))
        self.assertFalse(is_valid_transition(TaskState.CANCELLED, TaskState.QUEUED))

    def test_invalid_transitions(self):
        invalid = [
            (TaskState.QUEUED, TaskState.COMPLETED),
            (TaskState.QUEUED, TaskState.FAILED),
            (TaskState.QUEUED, TaskState.PAUSED),
            (TaskState.RUNNING, TaskState.QUEUED),
            (TaskState.RUNNING, TaskState.CANCELLED),
            (TaskState.PAUSED, TaskState.COMPLETED),
            (TaskState.PAUSED, TaskState.FAILED),
            (TaskState.CANCELLING, TaskState.RUNNING),
            (TaskState.CANCELLING, TaskState.PAUSED),
            (TaskState.COMPLETED, TaskState.RUNNING),
            (TaskState.COMPLETED, TaskState.FAILED),
            (TaskState.FAILED, TaskState.RUNNING),
            (TaskState.FAILED, TaskState.COMPLETED),
            (TaskState.INTERRUPTED, TaskState.RUNNING),
            (TaskState.INTERRUPTED, TaskState.COMPLETED),
            (TaskState.CANCELLED, TaskState.RUNNING),
            (TaskState.CANCELLED, TaskState.QUEUED),
        ]
        for from_state, to_state in invalid:
            with self.subTest(from_state=from_state, to_state=to_state):
                self.assertFalse(is_valid_transition(from_state, to_state))
                with self.assertRaises(ValueError):
                    require_valid_transition(from_state, to_state)

    def test_transition_guards(self):
        self.assertEqual(transition_guard(TaskState.QUEUED, TaskState.RUNNING), "Worker thread available and task dequeued")
        self.assertEqual(transition_guard(TaskState.RUNNING, TaskState.COMPLETED), "All stages finished without error")
        self.assertIsNone(transition_guard(TaskState.QUEUED, TaskState.COMPLETED))

    def test_from_string(self):
        self.assertEqual(from_string("queued"), TaskState.QUEUED)
        self.assertEqual(from_string("RUNNING"), TaskState.RUNNING)
        self.assertEqual(from_string("Paused"), TaskState.PAUSED)
        self.assertIsNone(from_string("unknown"))
        self.assertIsNone(from_string(""))

    def test_require_valid_transition_success(self):
        require_valid_transition(TaskState.QUEUED, TaskState.RUNNING)
        require_valid_transition(TaskState.RUNNING, TaskState.COMPLETED)
        require_valid_transition(TaskState.FAILED, TaskState.QUEUED)


if __name__ == "__main__":
    unittest.main()
