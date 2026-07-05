"""
eve/hermes_agent.py
===================
HermesAgent — the HTTP command gateway.

Runs a Flask server on 127.0.0.1:5001 (localhost only) so external tools or
the EVE Dashboard UI can submit tasks via REST.

Task routing
------------
HermesAgent is a pure ingestion gateway — it does NOT execute device actions
itself.  When a command arrives, it tags the task with the correct target agent
so the EVE orchestrator can dispatch it to the right executor:

  Device actions  (screenshot, click, scan …) → agent = "hacxgent"
  Meta actions    (status, help …)             → agent = "hermes"  (handled inline)

This fixes the previous bug where HermesAgent would silently swallow every
device action by claiming to handle it.

Authentication
--------------
All mutating endpoints require a Bearer token.  The token is stored in
``<data_dir>/hermes_token.txt`` (the app's private internal storage).

Read it via:
    adb shell run-as com.eve.agent cat files/hermes_token.txt

Port: 5001 (avoids conflict with ADB's default 5000 forward).
Forward: adb forward tcp:5001 tcp:5001
"""

import logging
import os
import secrets

from flask import Flask, request, jsonify
from .task_queue import Task, TaskQueue

logger = logging.getLogger("EVE.HermesAgent")

# ── Action routing ────────────────────────────────────────────────────────────

# Actions the hacxgent agent handles (device control, scans, captures)
_HACXGENT_ACTIONS = frozenset({
    "screenshot", "scan", "audit",
    "capture", "analyze",
    "click", "move_to", "typewrite",
    "execute_script", "http_get",
})

# Actions handled inline by Hermes (meta / status queries)
_HERMES_ACTIONS = frozenset({
    "status", "help", "list_agents",
})

# Full allowlist — reject everything else
_ALLOWED_ACTIONS = _HACXGENT_ACTIONS | _HERMES_ACTIONS


def _route_action(action: str) -> str:
    """Return the target agent name for this action."""
    if action in _HACXGENT_ACTIONS:
        return "hacxgent"
    return "hermes"


# ── Token helpers ─────────────────────────────────────────────────────────────

def _load_or_create_token(data_dir: str) -> str:
    """Load an existing auth token or generate and persist a new one."""
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


# ─────────────────────────────────────────────────────────────────────────────

class HermesAgent:
    PORT = 5001

    def __init__(self, data_dir: str = None):
        # data_dir is passed by EveService.kt via the Python constructor call.
        # Fall back to EVE_DATA_DIR env var (also set by EveService), then a
        # hardcoded default (only used in tests outside Android).
        resolved_dir = (
            data_dir
            or os.environ.get("EVE_DATA_DIR")
            or "/data/data/com.eve.agent/files"
        )
        self.task_queue: TaskQueue = None
        self._token: str = _load_or_create_token(resolved_dir)
        self._app = Flask(__name__)
        self._setup_routes()

    # ── Auth helper ───────────────────────────────────────────────────────────

    def _check_auth(self) -> bool:
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return False
        return secrets.compare_digest(auth[7:], self._token)

    # ── Flask routes ──────────────────────────────────────────────────────────

    def _setup_routes(self):
        app = self._app

        @app.route("/command", methods=["POST"])
        def command():
            if not self._check_auth():
                return jsonify({"error": "Unauthorized"}), 401

            data = request.get_json(force=True, silent=True) or {}
            action = data.get("action", "").strip()
            params = data.get("params", {})

            if not action:
                return jsonify({"error": "Missing 'action' field"}), 400
            if action not in _ALLOWED_ACTIONS:
                return jsonify({"error": f"Action '{action}' not permitted"}), 400
            if not isinstance(params, dict):
                return jsonify({"error": "'params' must be a JSON object"}), 400
            if self.task_queue is None:
                return jsonify({"error": "Task queue not initialised"}), 503

            target_agent = _route_action(action)
            task = Task(agent=target_agent, action=action, params=params)
            self.task_queue.put(task)
            logger.info(
                "Accepted task %s (action=%s → agent=%s)",
                task.id, action, target_agent
            )
            return jsonify({
                "status": "accepted",
                "task_id": task.id,
                "routed_to": target_agent,
            }), 202

        @app.route("/health", methods=["GET"])
        def health():
            return jsonify({"status": "ok", "agent": "hermes"})

    # ── Agent protocol ────────────────────────────────────────────────────────

    def set_task_queue(self, q: TaskQueue) -> None:
        self.task_queue = q

    def can_handle(self, task: Task) -> bool:
        """Hermes only handles meta tasks tagged to itself (status, help, etc.)."""
        return task.agent == "hermes"

    def assign_task(self, task: Task) -> None:
        """Handle meta / status tasks inline."""
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
        """Start the Flask HTTP gateway (blocking). Called on a daemon thread."""
        logger.info("HermesAgent HTTP gateway on 127.0.0.1:%d", self.PORT)
        self._app.run(
            host="127.0.0.1",
            port=self.PORT,
            threaded=True,
            use_reloader=False,    # reloader forks — breaks Chaquopy
        )
