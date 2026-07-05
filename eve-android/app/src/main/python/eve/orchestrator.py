"""
eve/orchestrator.py
===================
Central orchestrator for the EVE agent system.

Architecture
------------
EVE receives tasks from the queue (populated by HermesAgent's Flask server)
and delegates them to the registered agent whose ``can_handle()`` returns True.

Threading model:
  - Each agent runs its own daemon thread via ``agent.run()``.
  - EVE's main loop runs on a dedicated thread started by EveService.kt.
  - Tasks flow: HTTP POST → HermesAgent → TaskQueue → EVE.delegate() → agent

Kotlin bridge
-------------
Log lines and task completion events are forwarded to the Android UI via
``EveKotlinBridge`` (Chaquopy jclass). This is gracefully skipped when the
class is not available (e.g. in unit tests outside Android).
"""

import logging
import threading
import queue

from .task_queue import TaskQueue

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("EVE")

# ── Kotlin bridge (optional; absent in pure-Python test environments) ─────────
try:
    from java import jclass as _jclass
    _Bridge = _jclass("com.eve.agent.EveKotlinBridge")
except Exception:
    _Bridge = None


def _bridge_log(msg: str, level: str) -> None:
    if _Bridge is not None:
        try:
            _Bridge.onLogLine(msg, level)
        except Exception:
            pass


def _bridge_task_done(task_id: str, action: str, result: str, failed: bool) -> None:
    if _Bridge is not None:
        try:
            _Bridge.onTaskCompleted(task_id, action, result, failed)
        except Exception:
            pass


# ─────────────────────────────────────────────────────────────────────────────

class EVE:
    def __init__(self):
        self.task_queue = TaskQueue()
        self.agents: dict = {}
        self.running = False
        self._lock = threading.Lock()

    # ── Agent registry ────────────────────────────────────────────────────────

    def register_agent(self, name: str, agent) -> None:
        """Register an agent. The agent must implement:
            set_task_queue(q), can_handle(task) -> bool, assign_task(task), run()
        """
        with self._lock:
            self.agents[name] = agent
            agent.set_task_queue(self.task_queue)

    # ── Logging ───────────────────────────────────────────────────────────────

    def log(self, msg: str, level: str = "INFO") -> None:
        lvl = getattr(logging, level, logging.INFO)
        logger.log(lvl, msg)
        _bridge_log(msg, level)

    # ── Main loop ─────────────────────────────────────────────────────────────

    def run(self) -> None:
        """Run the orchestrator loop (blocking). Call from a daemon thread."""
        self.running = True
        self.log("EVE started")

        with self._lock:
            agents_snapshot = list(self.agents.items())

        for name, agent in agents_snapshot:
            t = threading.Thread(
                target=agent.run,
                name=f"eve-agent-{name}",
                daemon=True
            )
            t.start()
            self.log(f"Agent '{name}' started")

        while self.running:
            try:
                task = self.task_queue.get(timeout=1)
                if task is None:
                    continue
                self.log(f"Received task {task.id} (action={task.action})")
                self.delegate(task)
            except queue.Empty:
                continue
            except Exception as exc:
                self.log(f"Orchestrator error: {exc}", "ERROR")

        self.log("EVE stopped")

    def stop(self) -> None:
        """Signal the run loop to exit cleanly."""
        self.running = False

    # ── Delegation ────────────────────────────────────────────────────────────

    def delegate(self, task) -> None:
        with self._lock:
            agents_snapshot = list(self.agents.items())

        for name, agent in agents_snapshot:
            try:
                if agent.can_handle(task):
                    agent.assign_task(task)
                    self.log(f"Task {task.id} delegated to '{name}'")
                    # Forward result to Kotlin UI
                    failed = task.status == "failed"
                    result = task.error if failed else (task.result or "")
                    _bridge_task_done(task.id, task.action, str(result), failed)
                    return
            except Exception as exc:
                self.log(
                    f"Agent '{name}' raised during can_handle/assign_task: {exc}",
                    "ERROR"
                )
                _bridge_task_done(task.id, task.action, str(exc), True)
                return

        self.log(
            f"No agent can handle task {task.id} (action={task.action})",
            "WARNING"
        )
        _bridge_task_done(task.id, task.action, "No handler found", True)
