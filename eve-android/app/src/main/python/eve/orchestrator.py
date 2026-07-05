"""
eve/orchestrator.py
===================
Central orchestrator for the EVE agent system.

EVE receives tasks from HermesAgent (via Flask HTTP) and delegates them to the
correct registered agent based on that agent's `can_handle()` response.

Threading model:
  - Each agent runs its own daemon thread via `agent.run()`.
  - EVE's main loop runs on a dedicated thread started by EveService.kt.
  - Tasks flow: external HTTP → HermesAgent → TaskQueue → EVE.delegate() → agent.assign_task()
"""

import threading
import queue
import logging

from .task_queue import TaskQueue

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("EVE")


class EVE:
    def __init__(self):
        self.task_queue = TaskQueue()
        self.agents: dict = {}
        self.running = False
        self.log_callback = None
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Agent registry
    # ------------------------------------------------------------------

    def register_agent(self, name: str, agent) -> None:
        """Register an agent under [name]. The agent must implement:
          - set_task_queue(queue)
          - can_handle(task) -> bool
          - assign_task(task)
          - run()
        """
        with self._lock:
            self.agents[name] = agent
            agent.set_task_queue(self.task_queue)

    # ------------------------------------------------------------------
    # Logging
    # ------------------------------------------------------------------

    def set_log_callback(self, cb) -> None:
        """Provide a callback(msg: str, level: str) invoked for every log line.
        The Kotlin side uses this to relay log lines to the Dashboard UI.
        """
        self.log_callback = cb

    def log(self, msg: str, level: str = "INFO") -> None:
        if self.log_callback:
            try:
                self.log_callback(msg, level)
            except Exception:
                pass  # never let a broken callback crash the orchestrator
        lvl = getattr(logging, level, logging.INFO)
        logger.log(lvl, msg)

    # ------------------------------------------------------------------
    # Main loop
    # ------------------------------------------------------------------

    def run(self) -> None:
        """Run the orchestrator loop (blocking). Call from a daemon thread."""
        self.running = True
        self.log("EVE started")

        # Start each agent on its own daemon thread
        with self._lock:
            agents_snapshot = list(self.agents.items())

        for name, agent in agents_snapshot:
            t = threading.Thread(target=agent.run, name=f"eve-agent-{name}", daemon=True)
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

    # ------------------------------------------------------------------
    # Delegation
    # ------------------------------------------------------------------

    def delegate(self, task) -> None:
        with self._lock:
            agents_snapshot = list(self.agents.items())

        for name, agent in agents_snapshot:
            try:
                if agent.can_handle(task):
                    agent.assign_task(task)
                    self.log(f"Task {task.id} delegated to '{name}'")
                    return
            except Exception as exc:
                self.log(f"Agent '{name}' raised during can_handle/assign_task: {exc}", "ERROR")

        self.log(f"No agent can handle task {task.id} (action={task.action})", "WARNING")
