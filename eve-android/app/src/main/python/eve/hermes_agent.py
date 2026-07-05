"""
eve/hermes_agent.py
===================
HermesAgent — the HTTP command gateway.

Runs a Flask server on 127.0.0.1 (localhost only) so external tools or
the EVE Dashboard UI can submit tasks via REST.

Task routing
------------
HermesAgent is a pure ingestion gateway — it does NOT execute device actions
itself.  When a command arrives, it tags the task with the correct target agent
so the EVE orchestrator can dispatch it to the right executor:

  Device actions  (screenshot, click, scan …) → agent = "hacxgent"
  Meta actions    (status, help …)             → agent = "hermes"  (handled inline)

Authentication
--------------
All mutating endpoints require a Bearer token.  The token is stored in
``<data_dir>/hermes_token.txt`` (the app's private internal storage).

Read it without adb: open the Setup tab inside the EVE app.

Port: 5001 (default), with automatic fallback to 5002–5010 if already bound.
Forward: adb forward tcp:5001 tcp:5001
"""

import logging
import os
import secrets
import socket

from flask import Flask, request, jsonify
from .task_queue import Task, TaskQueue

logger = logging.getLogger("EVE.HermesAgent")

DEFAULT_PORT = 5001
PORT_RANGE   = range(DEFAULT_PORT, DEFAULT_PORT + 10)   # 5001–5010

# ── Action routing ────────────────────────────────────────────────────────────

_HACXGENT_ACTIONS = frozenset({
    "screenshot", "scan", "audit",
    "capture", "analyze",
    "click", "move_to", "typewrite",
    "execute_script", "http_get",
})

_HERMES_ACTIONS = frozenset({
    "status", "help", "list_agents",
})

_ALLOWED_ACTIONS = _HACXGENT_ACTIONS | _HERMES_ACTIONS


def _route_action(action: str) -> str:
    if action in _HACXGENT_ACTIONS:
        return "hacxgent"
    return "hermes"


# ── Token helpers ─────────────────────────────────────────────────────────────

def _load_or_create_token(data_dir: str) -> str:
    token_path = os.path.join(data_dir, "hermes_token.txt")
    try:
        if os.path.exists(token_path):
            return open(token_path).read().strip()
        token = secrets.token_hex(32)
        os.makedirs(data_dir, exist_ok=True)
        with open(token_path, "w") as f:
            f.write(token)
        logger.info("Generated new Hermes token at %s", token_path)
        return token
    except OSError as exc:
        logger.warning("Could not persist Hermes token (%s); using ephemeral token", exc)
        return secrets.token_hex(32)


# ── Port probe ────────────────────────────────────────────────────────────────

def _find_free_port() -> int:
    """Return the first free port in PORT_RANGE, or DEFAULT_PORT as fallback."""
    for port in PORT_RANGE:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    logger.warning(
        "All ports %d–%d are in use; falling back to %d (may fail)",
        DEFAULT_PORT, DEFAULT_PORT + 9, DEFAULT_PORT
    )
    return DEFAULT_PORT


# ─────────────────────────────────────────────────────────────────────────────

class HermesAgent:

    def __init__(self, data_dir: str = None):
        resolved_dir = (
            data_dir
            or os.environ.get("EVE_DATA_DIR")
            or "/data/data/com.eve.agent/files"
        )
        self.task_queue: TaskQueue = None
        self._token: str = _load_or_create_token(resolved_dir)
        self._port: int  = _find_free_port()
        self._app = Flask(__name__)
        self._setup_routes()

    # ── Auth ──────────────────────────────────────────────────────────────────

    def _check_auth(self) -> bool:
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return False
        return secrets.compare_digest(auth[7:], self._token)

    # ── Routes ────────────────────────────────────────────────────────────────

    def _setup_routes(self):
        app = self._app

        @app.route("/command", methods=["POST"])
        def command():
            if not self._check_auth():
                return jsonify({"error": "Unauthorized"}), 401

            data   = request.get_json(force=True, silent=True) or {}
            action = data.get("action", "").strip()
            params = data.get("params", {})

            if not action:
                return jsonify({"error": "Missing 'action' field"}), 400
            if action not in _ALLOWED_ACTIONS:
                return jsonify({"error": f"Action '{action}' not permitted",
                                "allowed": sorted(_ALLOWED_ACTIONS)}), 400
            if not isinstance(params, dict):
                return jsonify({"error": "'params' must be a JSON object"}), 400
            if self.task_queue is None:
                return jsonify({"error": "Task queue not initialised"}), 503

            target = _route_action(action)
            task   = Task(agent=target, action=action, params=params)
            self.task_queue.put(task)
            logger.info("Accepted task %s (action=%s → %s)", task.id, action, target)
            return jsonify({
                "status":     "accepted",
                "task_id":    task.id,
                "routed_to":  target,
                "port":       self._port,
            }), 202

        @app.route("/health", methods=["GET"])
        def health():
            return jsonify({
                "status": "ok",
                "agent":  "hermes",
                "port":   self._port,
            })

    # ── Agent protocol ────────────────────────────────────────────────────────

    def set_task_queue(self, q: TaskQueue) -> None:
        self.task_queue = q

    def can_handle(self, task: Task) -> bool:
        return task.agent == "hermes"

    def assign_task(self, task: Task) -> None:
        action = task.action.lower()
        if action == "status":
            task.result = "EVE is running"
        elif action == "list_agents":
            task.result = "hermes, hacxgent"
        elif action == "help":
            task.result = f"Allowed actions: {', '.join(sorted(_ALLOWED_ACTIONS))}"
        else:
            task.result = f"Unhandled meta action: {task.action}"
        task.status = "completed"

    def run(self) -> None:
        logger.info("HermesAgent HTTP gateway on 127.0.0.1:%d", self._port)
        self._app.run(
            host="127.0.0.1",
            port=self._port,
            threaded=True,
            use_reloader=False,
        )
