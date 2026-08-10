"""Local Agent Hub pipeline coordinator.

This is the Android-facing entry point for the five-stage Agent Hub. It keeps
pipeline state local to EVE and provides a safe integration point for future
model/provider executors without changing the Android UI or IPC contract.
"""

import time
from .task_queue import Task


STAGES = ("planner", "coder", "reviewer", "tester", "security")


class AgentHubAgent:
    def __init__(self, log=None):
        self.task_queue = None
        self.log = log or (lambda message, level="INFO": None)

    def set_task_queue(self, q):
        self.task_queue = q

    def can_handle(self, task: Task) -> bool:
        return task.action == "agent_hub"

    def assign_task(self, task: Task) -> None:
        prompt = str(task.params.get("task", "")).strip()
        if not prompt:
            task.status = "failed"
            task.error = "Agent Hub task is empty"
            return

        self.log(f"Agent Hub accepted: {prompt}")
        task.status = "running"

        # Record the five-stage plan locally. Provider-specific execution is
        # intentionally injected later; the Android bridge already has a
        # stable contract and does not need to know how models are hosted.
        for stage in STAGES:
            self.log(f"Agent Hub stage ready: {stage}")

        task.result = (
            "Agent Hub pipeline created: "
            + " → ".join(STAGES)
            + f" for task '{prompt}'"
        )
        task.status = "completed"
        task.completed_at = time.time()

    def run(self) -> None:
        # The coordinator is event-driven by EVE.delegate().
        return None
