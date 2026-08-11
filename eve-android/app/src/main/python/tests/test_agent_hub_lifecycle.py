"""Lifecycle tests for Issue #2 hardening: persistence, recovery, cancellation,
tester-failure gating, and Hacxgent async dispatch."""

import json
import os
import tempfile
import time
import unittest
from unittest.mock import patch

from eve.agent_hub_agent import AgentHubAgent
from eve.hacxgent_agent import HacxgentAgent
from eve.task_queue import Task


def _write_config(directory):
    with open(os.path.join(directory, "model_config.json"), "w", encoding="utf-8") as fh:
        json.dump({"provider": "openai", "model": "auto", "api_key": "test"}, fh)


class _FakeProvider:
    def __init__(self, payload=None):
        self.config = type("C", (), {"base_url": "https://example.test", "api_key": "test"})()
        self.payload = payload or {"summary": "ok"}

    def complete(self, system, user, stage=""):
        return json.dumps(self.payload)


def _fake_build_provider(payload=None):
    provider = _FakeProvider(payload)
    return lambda data_dir: provider


class AgentHubLifecycleTests(unittest.TestCase):
    def test_persist_and_recover_interrupted_tasks(self):
        with tempfile.TemporaryDirectory() as directory:
            _write_config(directory)
            agent = AgentHubAgent(data_dir=directory)
            task = Task("agent_hub", "agent_hub", {"task": "do something", "project": "demo"})
            agent.assign_task(task)
            # Simulate process death: persisted record still says queued.
            store = os.path.join(directory, "agent_hub_tasks.json")
            self.assertTrue(os.path.exists(store))
            agent2 = AgentHubAgent(data_dir=directory)
            recovered = agent2.recover_interrupted_tasks()
            self.assertEqual(recovered, 1)
            restored = agent2._task_objects[task.id]
            self.assertEqual(restored.status, "interrupted")
            self.assertIn("restarted", restored.error.lower())
            # Shut down both agents' executors before the tempdir is removed so
            # no background thread writes into it during cleanup.
            agent._executor.shutdown(wait=True)
            agent._executor = None
            agent2._executor.shutdown(wait=True)
            agent2._executor = None

    def test_recovery_ignores_terminal_tasks(self):
        with tempfile.TemporaryDirectory() as directory:
            _write_config(directory)
            store = os.path.join(directory, "agent_hub_tasks.json")
            with open(store, "w", encoding="utf-8") as fh:
                json.dump([
                    {"id": "a" * 32, "status": "completed", "stage": "reviewer", "spec": {"prompt": "x", "project": "demo"}},
                    {"id": "b" * 32, "status": "running", "stage": "coder", "spec": {"prompt": "y", "project": "demo"}},
                ], fh)
            agent = AgentHubAgent(data_dir=directory)
            self.assertEqual(agent.recover_interrupted_tasks(), 1)
            self.assertEqual(agent._task_objects["b" * 32].status, "interrupted")
            self.assertNotIn("a" * 32, agent._task_objects)

    def test_recovery_tolerates_corrupt_store(self):
        with tempfile.TemporaryDirectory() as directory:
            _write_config(directory)
            with open(os.path.join(directory, "agent_hub_tasks.json"), "w", encoding="utf-8") as fh:
                fh.write("{not json")
            agent = AgentHubAgent(data_dir=directory)
            self.assertEqual(agent.recover_interrupted_tasks(), 0)

    def test_cancelled_task_gets_cancelled_status_not_failed(self):
        with tempfile.TemporaryDirectory() as directory:
            _write_config(directory)
            import threading
            with patch("eve.agent_hub_agent.build_provider", _fake_build_provider()):
                agent = AgentHubAgent(data_dir=directory)
                task = Task("agent_hub", "agent_hub", {"task": "x", "project": "demo"})
                task.status = "queued"
                cancel = threading.Event(); cancel.set()
                with agent._state_lock:
                    agent._cancel_events[task.id] = cancel
                    agent._pause_events[task.id] = threading.Event()
                    agent._task_objects[task.id] = task
                    agent._task_specs[task.id] = {"prompt": "x", "project": "demo"}
                agent._execute_pipeline(task, {"prompt": "x", "project": "demo"}, "planner")
            self.assertEqual(task.status, "cancelled")
            self.assertIn("cancelled", task.error.lower())

    def test_tester_failure_fails_pipeline_before_reviewer(self):
        with tempfile.TemporaryDirectory() as directory:
            _write_config(directory)
            import threading
            with patch("eve.agent_hub_agent.build_provider", _fake_build_provider({"summary": "ok", "commands": ["pytest"], "files": []})):
                agent = AgentHubAgent(data_dir=directory)
                task = Task("agent_hub", "agent_hub", {"task": "x", "project": "demo"})
                task.status = "queued"
                with agent._state_lock:
                    agent._cancel_events[task.id] = threading.Event()
                    agent._pause_events[task.id] = threading.Event()
                    agent._task_objects[task.id] = task
                    agent._task_specs[task.id] = {"prompt": "x", "project": "demo"}
                with patch.object(agent, "_run_tests", return_value=[{"command": "pytest", "status": "failed", "exit_code": 1}]):
                    agent._execute_pipeline(task, {"prompt": "x", "project": "demo"}, "tester")
            self.assertEqual(task.status, "failed")
            self.assertIn("tester stage failed", task.error.lower())

    def test_retry_requeues_failed_task(self):
        with tempfile.TemporaryDirectory() as directory:
            _write_config(directory)
            import threading
            with patch("eve.agent_hub_agent.build_provider", _fake_build_provider()):
                agent = AgentHubAgent(data_dir=directory)
                task = Task("agent_hub", "agent_hub", {"task": "x", "project": "demo"})
                task.status = "failed"
                with agent._state_lock:
                    agent._cancel_events[task.id] = threading.Event()
                    agent._pause_events[task.id] = threading.Event()
                    agent._task_objects[task.id] = task
                    agent._task_specs[task.id] = {"prompt": "x", "project": "demo"}
                control = Task("agent_hub", "agent_hub_control", {"task_id": task.id, "command": "retry"})
                agent._control(control)
                self.assertEqual(control.status, "completed")
                # The retried pipeline runs asynchronously on the executor and
                # re-resolves the provider via _refresh_provider(), so the patch
                # must stay active until the task reaches a terminal state.
                for _ in range(100):
                    if task.status in {"completed", "failed", "cancelled"}:
                        break
                    time.sleep(0.05)
                self.assertEqual(task.status, "completed")
                agent._executor.shutdown(wait=True)
                agent._executor = None

    def test_invalid_transition_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            _write_config(directory)
            agent = AgentHubAgent(data_dir=directory)
            task = Task("agent_hub", "agent_hub", {"task": "x", "project": "demo"})
            task.status = "completed"
            with self.assertRaises(ValueError):
                agent._set_status(task, "running")


class HacxgentLifecycleTests(unittest.TestCase):
    def test_assign_task_is_asynchronous(self):
        agent = HacxgentAgent()
        task = Task("hacxgent", "scan", {"target": "device"})
        start = time.monotonic()
        agent.assign_task(task)
        elapsed = time.monotonic() - start
        self.assertLess(elapsed, 0.4, "assign_task must not block the dispatcher")
        for _ in range(100):
            if task.status in {"completed", "failed"}:
                break
            time.sleep(0.05)
        self.assertEqual(task.status, "completed")
        self.assertIn("simulated", task.result)
        agent.stop()

    def test_stop_shuts_down(self):
        agent = HacxgentAgent()
        agent.stop()
        self.assertFalse(agent._running)
        self.assertTrue(agent._executor._shutdown)

    def test_failure_marks_task_failed(self):
        agent = HacxgentAgent()
        task = Task("hacxgent", "scan", {})
        with patch.object(agent, "_execute", side_effect=RuntimeError("boom")):
            agent.assign_task(task)
            for _ in range(100):
                if task.status == "failed":
                    break
                time.sleep(0.05)
        self.assertEqual(task.status, "failed")
        self.assertEqual(task.error, "boom")
        agent.stop()


if __name__ == "__main__":
    unittest.main()
