"""
eve/hermes_agent.py
===================
HermesAgent — the HTTP command gateway.

Runs a Flask server on 127.0.0.1:5001 (localhost only) so external tools or
the EVE Dashboard UI can submit tasks via REST.

Authentication:
  All mutating endpoints require a Bearer token in the Authorization header.
  The token is generated randomly on first run and stored in the app's
  internal storage as ``hermes_token.txt``.  Read it via:
      adb shell run-as com.eve.agent cat files/hermes_token.txt

Endpoint:
  POST /command
  Header: Authorization: Bearer <token>
  Body: { "action": "...", "params": { ... } }
  Response: { "status": "accepted", "task_id": "..." }

NOTE: Port 5000 conflicts with ADB's reverse-forward convention; using 5001.
      If you need to reach this from your dev machine:
        adb forward tcp:5001 tcp:5001

Allowed actions (allowlist — reject anything not in this set):
  screenshot, scan, audit, execute_script, web_search, typewrite, click
"""

import logging
import os
import secrets

from flask import Flask, request, jsonify
from .task_queue import Task, TaskQueue

logger = logging.getLogger("EVE.HermesAgent")

# Actions that mutating endpoints may accept.  Reject anything else to limit
# the local attack surface exposed by the unauthenticated socket.
_ALLOWED_ACTIONS = frozenset({
    "screenshot", "scan", "audit",
    "execute_script", "web_search",
    "typewrite", "click", "move_to", "http_get",
})

# Path where the bearer token is persisted (inside the app's private storage).
# Resolved at startup in run(); default shown here for tests.
_TOKEN_PATH = "/data/data/com.eve.agent/files/hermes_token.txt"


def _load_or_create_token(path: str) -> str:
    """Load an existing token or generate a new one and persist it."""
    try:
        if os.path.exists(path):
            return open(path).read().strip()
        token = secrets.token_hex(32)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(token)
        return token
    except OSError as exc:
        logger.warning("Could not persist Hermes token (%s); using ephemeral token", exc)
        return secrets.token_hex(32)


class HermesAgent:
    PORT = 5001

    def __init__(self, token_path: str = _TOKEN_PATH):
        self.task_queue: TaskQueue = None
        self._token: str = _load_or_create_token(token_path)
        self._app = Flask(__name__)
        self._setup_routes()

    # ------------------------------------------------------------------
    # Auth helper
    # ------------------------------------------------------------------

    def _check_auth(self) -> bool:
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return False
        return secrets.compare_digest(auth[7:], self._token)

    # ------------------------------------------------------------------
    # Flask routes
    # ------------------------------------------------------------------

    def _setup_routes(self):
        app = self._app

        @app.route("/command", methods=["POST"])
        def command():
            if not self._check_auth():
                return jsonify({"error": "Unauthorized"}), 401

            data = request.get_json(force=True, silent=True) or {}
            action = data.get("action", "")
            params = data.get("params", {})

            if not action:
                return jsonify({"error": "Missing 'action' field"}), 400
            if action not in _ALLOWED_ACTIONS:
                return jsonify({"error": f"Action '{action}' not permitted"}), 400
            if not isinstance(params, dict):
                return jsonify({"error": "'params' must be a JSON object"}), 400
            if self.task_queue is None:
                return jsonify({"error": "Task queue not initialised"}), 503

            task = Task(agent="hermes", action=action, params=params)
            self.task_queue.put(task)
            logger.info("Accepted task %s (action=%s)", task.id, action)
            return jsonify({"status": "accepted", "task_id": task.id}), 202

        @app.route("/health", methods=["GET"])
        def health():
            # Health check is unauthenticated (no sensitive data returned)
            return jsonify({"status": "ok", "agent": "hermes"})

    # ------------------------------------------------------------------
    # Agent protocol
    # ------------------------------------------------------------------

    def set_task_queue(self, q: TaskQueue) -> None:
        self.task_queue = q

    def can_handle(self, task: Task) -> bool:
        return task.agent == "hermes"

    def assign_task(self, task: Task) -> None:
        """
        Lightweight pass-through — Hermes is primarily an ingestion agent.
        Real execution is handled downstream by specialised agents.
        """
        task.status = "completed"
        task.result = f"Ingested by Hermes: {task.action}"

    def run(self) -> None:
        """Start the Flask server (blocking). Called on a daemon thread by EVE."""
        logger.info("HermesAgent HTTP gateway listening on 127.0.0.1:%d", self.PORT)
        self._app.run(
            host="127.0.0.1",
            port=self.PORT,
            threaded=True,
            use_reloader=False,    # reloader forks and breaks Chaquopy
        )
