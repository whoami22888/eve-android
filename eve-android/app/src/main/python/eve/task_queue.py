"""Thread-safe task queue shared between the orchestrator and all agents."""

import queue
import uuid


class Task:
    """Represents one unit of EVE work with explicit lifecycle state."""

    def __init__(self, agent: str, action: str, params: dict = None, task_id: str = None):
        self.id = task_id or uuid.uuid4().hex
        self.agent = agent
        self.action = action
        self.params = params or {}
        self.status = "pending"
        self.result = None
        self.error: str | None = None
        self.completed_at: float | None = None

    def __repr__(self):
        return f"<Task id={self.id} agent={self.agent} action={self.action} status={self.status}>"


class TaskQueue:
    """Thread-safe FIFO queue for Task objects."""

    def __init__(self):
        self._queue: queue.Queue = queue.Queue()

    def put(self, task: Task) -> None:
        self._queue.put(task)

    def get(self, timeout: float = None) -> Task | None:
        try:
            return self._queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def qsize(self) -> int:
        return self._queue.qsize()
