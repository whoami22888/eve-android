"""Task-driven Hacxgent device-scan and security-audit agent."""

import logging

from .task_queue import Task, TaskQueue

logger = logging.getLogger("EVE.HacxgentAgent")


class HacxgentAgent:
    HANDLED_KEYWORDS = {"scan", "audit", "screenshot", "capture", "analyze"}

    def __init__(self):
        self.task_queue: TaskQueue = None

    def set_task_queue(self, q: TaskQueue) -> None:
        self.task_queue = q

    def can_handle(self, task: Task) -> bool:
        return task.agent == "hacxgent" or any(
            keyword in task.action.lower() for keyword in self.HANDLED_KEYWORDS
        )

    def assign_task(self, task: Task) -> None:
        task.status = "running"
        try:
            task.result = self._execute(task)
            task.status = "completed"
        except Exception as exc:
            task.error = str(exc)
            task.status = "failed"
            logger.error("Task %s failed: %s", task.id, exc)

    def _execute(self, task: Task) -> str:
        action = task.action.lower()
        if "screenshot" in action or "capture" in action:
            return self._do_screenshot()
        if "scan" in action:
            return self._do_scan(task.params)
        if "audit" in action:
            return self._do_audit(task.params)
        return f"HacxgentAgent: unrecognised action '{task.action}'"

    @staticmethod
    def _do_screenshot() -> str:
        from .adapters.android_computer import screenshot

        bitmap = screenshot()
        if bitmap is None:
            return "Screenshot failed — accessibility service not bound"
        return f"Screenshot captured ({bitmap.getWidth()}x{bitmap.getHeight()} px)"

    @staticmethod
    def _do_scan(params: dict) -> str:
        target = params.get("target", "device")
        return f"Scan of '{target}' completed (simulated)"

    @staticmethod
    def _do_audit(params: dict) -> str:
        return "Security audit completed (simulated)"
