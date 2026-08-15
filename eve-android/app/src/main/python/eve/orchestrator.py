"""Central EVE orchestrator for responsive multi-agent task dispatch."""

import logging
import threading
import queue
import time
from concurrent.futures import ThreadPoolExecutor

from .task_queue import TaskQueue

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("EVE")

try:
    from java import jclass as _jclass
    _Bridge = _jclass("com.eve.agent.EveKotlinBridge")
except Exception:
    _Bridge = None


def _bridge_log(msg: str, level: str) -> None:
    if _Bridge is not None:
        try: _Bridge.onLogLine(msg, level)
        except Exception: pass


def _bridge_task_done(task_id: str, action: str, result: str, failed: bool) -> None:
    if _Bridge is not None:
        try: _Bridge.onTaskCompleted(task_id, action, result, failed)
        except Exception: pass


class EVE:
    def __init__(self):
        self.task_queue = TaskQueue(); self.agents = {}; self.running = False; self._lock = threading.Lock()
        self._agent_hub_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="eve-agent-hub")

    def register_agent(self, name: str, agent) -> None:
        with self._lock:
            self.agents[name] = agent; agent.set_task_queue(self.task_queue)

    def log(self, msg: str, level: str = "INFO") -> None:
        logger.log(getattr(logging, level, logging.INFO), msg); _bridge_log(msg, level)

    def run(self) -> None:
        self.running = True; self.log("EVE started")
        with self._lock: agents_snapshot = list(self.agents.items())
        for name, agent in agents_snapshot:
            run = getattr(agent, "run", None)
            if not callable(run):
                self.log(f"Agent '{name}' is task-driven and has no background run loop")
                continue
            threading.Thread(target=run, name=f"eve-agent-{name}", daemon=True).start(); self.log(f"Agent '{name}' started")
        while self.running:
            try:
                task = self.task_queue.get(timeout=1)
                if task is None: continue
                self.log(f"Received task {task.id} (action={task.action})"); self.delegate(task)
            except queue.Empty: continue
            except Exception as exc: self.log(f"Orchestrator error: {exc}", "ERROR")
        self.log("EVE stopped")

    def stop(self) -> None:
        self.running = False
        with self._lock: agents_snapshot = list(self.agents.items())
        for name, agent in agents_snapshot:
            stop = getattr(agent, "stop", None)
            if not callable(stop):
                continue
            try:
                stop()
            except Exception as exc:
                self.log(f"Agent '{name}' stop failed: {exc}", "ERROR")
        self._agent_hub_executor.shutdown(wait=False, cancel_futures=True)

    def _complete(self, task) -> None:
        if task.status in {"completed", "failed", "cancelled", "interrupted"} and task.completed_at is None:
            task.completed_at = time.time()
        failed = task.status == "failed"; result = task.error if failed else (task.result or "")
        _bridge_task_done(task.id, task.action, str(result), failed)

    def _run_agent_hub(self, agent, task) -> None:
        try:
            agent.assign_task(task)
            self.log(f"Task {task.id} delegated to 'agent_hub' (status={task.status})")
            # Agent Hub owns completion once its bounded worker finishes. Do not
            # emit a false completion for queued/running asynchronous work.
            if task.status in {"completed", "failed"}:
                self._complete(task)
        except Exception as exc:
            task.status = "failed"; task.error = str(exc)
            self.log(f"Agent 'agent_hub' raised: {exc}", "ERROR"); self._complete(task)

    def delegate(self, task) -> None:
        with self._lock: agents_snapshot = list(self.agents.items())
        for name, agent in agents_snapshot:
            try:
                if not agent.can_handle(task): continue
                if name == "agent_hub" and task.action in {"agent_hub", "agent_hub_control"}:
                    self._agent_hub_executor.submit(self._run_agent_hub, agent, task); self.log(f"Task {task.id} dispatched to Agent Hub worker"); return
                agent.assign_task(task); self.log(f"Task {task.id} delegated to '{name}'"); self._complete(task); return
            except Exception as exc:
                self.log(f"Agent '{name}' raised during task {task.id}: {exc}", "ERROR")
                task.status = "failed"; task.error = str(exc); self._complete(task); return
        self.log(f"No agent can handle task {task.id} (action={task.action})", "WARNING"); _bridge_task_done(task.id, task.action, "No handler found", True)
