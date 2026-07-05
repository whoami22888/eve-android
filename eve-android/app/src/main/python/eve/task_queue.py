"""
eve/task_queue.py
=================
Thread-safe task queue shared between the orchestrator and all agents.
"""

import queue
import uuid


class Task:
    """
    Represents a single unit of work.

    Attributes:
        id       Unique hex identifier.
        agent    Target agent name (e.g. "hermes", "hacxgent"). The orchestrator
                 uses this as a hint but ultimately relies on can_handle().
        action   String describing the requested operation (e.g. "screenshot",
                 "execute_script", "web_search").
        params   Arbitrary dict of action parameters.
        status   "pending" → "running" → "completed" | "failed"
        result   Set by the agent when the task finishes.
        error    Set by the agent if the task fails.
    """

    def __init__(self, agent: str, action: str, params: dict = None, task_id: str = None):
        self.id = task_id or uuid.uuid4().hex
        self.agent = agent
        self.action = action
        self.params = params or {}
        self.status = "pending"
        self.result = None
        self.error: str = None

    def __repr__(self):
        return f"<Task id={self.id} agent={self.agent} action={self.action} status={self.status}>"


class TaskQueue:
    """Thread-safe FIFO queue for Task objects."""

    def __init__(self):
        self._queue: queue.Queue = queue.Queue()

    def put(self, task: Task) -> None:
        self._queue.put(task)

    def get(self, timeout: float = None) -> Task | None:
        """Returns the next task, or None if the queue is empty after [timeout]."""
        try:
            return self._queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def qsize(self) -> int:
        return self._queue.qsize()
