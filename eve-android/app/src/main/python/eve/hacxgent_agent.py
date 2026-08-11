"""
eve/hacxgent_agent.py
=====================
HacxgentAgent — the device-scan and security-audit agent.

Handles tasks whose action contains "scan", "audit", or is explicitly
targeted at this agent.  Currently runs simulated scans; wire in real
OpenCV / spaCy / network-probe logic as needed.
"""

import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from .task_queue import Task, TaskQueue

logger = logging.getLogger("EVE.HacxgentAgent")


class HacxgentAgent:

    HANDLED_KEYWORDS = {"scan", "audit", "screenshot", "capture", "analyze"}

    def __init__(self):
        self.task_queue: TaskQueue = None
        self._running = False
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="eve-hacxgent")

    # ------------------------------------------------------------------
    # Agent protocol
    # ------------------------------------------------------------------

    def set_task_queue(self, q: TaskQueue) -> None:
        self.task_queue = q

    def can_handle(self, task: Task) -> bool:
        if task.agent == "hacxgent":
            return True
        return any(kw in task.action.lower() for kw in self.HANDLED_KEYWORDS)

    def assign_task(self, task: Task) -> None:
        """Queue the task on a bounded worker so the dispatcher never blocks."""
        task.status = "queued"
        self._executor.submit(self._run_task, task)

    def _run_task(self, task: Task) -> None:
        task.status = "running"
        try:
            result = self._execute(task)
            task.result = result
            task.status = "completed"
        except Exception as exc:
            task.error = str(exc)
            task.status = "failed"
            logger.error("Task %s failed: %s", task.id, exc)
        finally:
            self._complete(task)

    def _complete(self, task: Task) -> None:
        """Report completion through the Kotlin bridge (no-op off-device)."""
        try:
            from java import jclass
            jclass("com.eve.agent.EveKotlinBridge").onTaskCompleted(
                task.id, task.action, str(task.error if task.status == "failed" else (task.result or "")),
                task.status == "failed",
            )
        except Exception:
            pass

    # ------------------------------------------------------------------
    # Execution
    # ------------------------------------------------------------------

    def _execute(self, task: Task) -> str:
        action = task.action.lower()

        if "screenshot" in action or "capture" in action:
            return self._do_screenshot(task.params)
        if "scan" in action:
            return self._do_scan(task.params)
        if "audit" in action:
            return self._do_audit(task.params)

        return f"HacxgentAgent: unrecognised action '{task.action}'"

    def _do_screenshot(self, params: dict) -> str:
        from .adapters.android_computer import screenshot
        bmp = screenshot()
        if bmp is None:
            return "Screenshot failed — accessibility service not bound"
        return f"Screenshot captured ({bmp.getWidth()}x{bmp.getHeight()} px)"

    def _do_scan(self, params: dict) -> str:
        # TODO: integrate real network or app scan
        time.sleep(0.5)  # simulate work
        target = params.get("target", "device")
        return f"Scan of '{target}' completed (simulated)"

    def _do_audit(self, params: dict) -> str:
        # TODO: integrate spaCy NLP analysis
        return "Security audit completed (simulated)"

    # ------------------------------------------------------------------
    # Idle loop
    # ------------------------------------------------------------------

    def run(self) -> None:
        """Idle loop; Hacxgent is task-driven, not event-driven."""
        self._running = True
        logger.info("HacxgentAgent ready")
        while self._running:
            time.sleep(5)

    def stop(self) -> None:
        """Stop the idle loop and shut down the task worker."""
        self._running = False
        self._executor.shutdown(wait=False, cancel_futures=True)
