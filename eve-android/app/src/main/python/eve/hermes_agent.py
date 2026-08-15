"""HermesAgent — authenticated local HTTP command gateway."""

import logging
import os
import re
import secrets
import socket
import threading
import uuid
from collections import OrderedDict

from flask import Flask, request, jsonify
from werkzeug.serving import make_server
from .task_queue import Task, TaskQueue

logger = logging.getLogger("EVE.HermesAgent")
DEFAULT_PORT = 5001
PORT_RANGE = range(DEFAULT_PORT, DEFAULT_PORT + 10)
MAX_RECENT_TASK_IDS = 1024

_HACXGENT_ACTIONS = frozenset({
    "screenshot", "scan", "audit", "capture", "analyze",
    "click", "move_to", "typewrite", "execute_script", "http_get",
})
_HERMES_ACTIONS = frozenset({"status", "help", "list_agents"})
_AGENT_HUB_ACTIONS = frozenset({"agent_hub", "agent_hub_control"})
_ALLOWED_ACTIONS = _HACXGENT_ACTIONS | _HERMES_ACTIONS | _AGENT_HUB_ACTIONS
_ID_RE = re.compile(r"^[a-f0-9]{16,64}$")


def _route_action(action: str) -> str:
    if action in _HACXGENT_ACTIONS:
        return "hacxgent"
    if action in _AGENT_HUB_ACTIONS:
        return "agent_hub"
    return "hermes"


def _load_or_create_token(data_dir: str) -> str:
    token_path = os.path.join(data_dir, "hermes_token.txt")
    try:
        if os.path.exists(token_path):
            with open(token_path, encoding="utf-8") as f:
                return f.read().strip()
        token = secrets.token_hex(32)
        os.makedirs(data_dir, exist_ok=True)
        with open(token_path, "w", encoding="utf-8") as f:
            f.write(token)
        return token
    except OSError as exc:
        logger.warning("Could not persist Hermes token (%s); using ephemeral token", exc)
        return secrets.token_hex(32)


def _find_free_port() -> int:
    for port in PORT_RANGE:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    return DEFAULT_PORT


class HermesAgent:
    def __init__(self, data_dir: str = None):
        resolved_dir = data_dir or os.environ.get("EVE_DATA_DIR") or "/data/data/com.eve.agent/files"
        self._data_dir = resolved_dir
        self.task_queue: TaskQueue = None
        self._accepted_task_ids: OrderedDict[str, None] = OrderedDict()
        self._accepted_task_ids_lock = threading.Lock()
        self._server_lock = threading.Lock()
        self._server = None
        self._stopped = False
        self._token = _load_or_create_token(resolved_dir)
        self._port = _find_free_port()
        self._app = Flask(__name__)
        self._setup_routes()
        self._persist_port()

    def _check_auth(self) -> bool:
        auth = request.headers.get("Authorization", "")
        return auth.startswith("Bearer ") and secrets.compare_digest(auth[7:], self._token)

    def _setup_routes(self):
        app = self._app

        @app.route("/command", methods=["POST"])
        def command():
            if not self._check_auth():
                return jsonify({"error": "Unauthorized"}), 401
            data = request.get_json(force=True, silent=True) or {}
            action = str(data.get("action", "")).strip()
            params = data.get("params", {})
            if not action:
                return jsonify({"error": "Missing 'action' field"}), 400
            if action not in _ALLOWED_ACTIONS:
                return jsonify({"error": f"Action '{action}' not permitted", "allowed": sorted(_ALLOWED_ACTIONS)}), 400
            if not isinstance(params, dict):
                return jsonify({"error": "'params' must be a JSON object"}), 400
            if self.task_queue is None:
                return jsonify({"error": "Task queue not initialised"}), 503
            params = dict(params)
            requested_id = str(params.pop("task_id", "")).strip().lower()
            task_id = requested_id if _ID_RE.fullmatch(requested_id) else uuid.uuid4().hex
            target = _route_action(action)
            with self._accepted_task_ids_lock:
                if task_id in self._accepted_task_ids:
                    self._accepted_task_ids.move_to_end(task_id)
                    return jsonify({"status": "accepted", "task_id": task_id, "routed_to": target, "port": self._port, "duplicate": True}), 202
                self._accepted_task_ids[task_id] = None
                if len(self._accepted_task_ids) > MAX_RECENT_TASK_IDS:
                    self._accepted_task_ids.popitem(last=False)
            task = Task(agent=target, action=action, params=params, task_id=task_id)
            self.task_queue.put(task)
            return jsonify({"status": "accepted", "task_id": task.id, "routed_to": target, "port": self._port}), 202

        @app.route("/health", methods=["GET"])
        def health():
            return jsonify({"status": "ok", "agent": "hermes", "port": self._port})

    def set_task_queue(self, q: TaskQueue) -> None:
        self.task_queue = q

    def can_handle(self, task: Task) -> bool:
        return task.agent == "hermes"

    def assign_task(self, task: Task) -> None:
        action = task.action.lower()
        if action == "status": task.result = "EVE is running"
        elif action == "list_agents": task.result = "hermes, agent_hub, hacxgent"
        elif action == "help": task.result = f"Allowed actions: {', '.join(sorted(_ALLOWED_ACTIONS))}"
        else: task.result = f"Unhandled meta action: {task.action}"
        task.status = "completed"

    def _persist_port(self) -> None:
        try:
            with open(os.path.join(self._data_dir, "hermes_port.txt"), "w", encoding="utf-8") as f:
                f.write(str(self._port))
        except OSError as exc:
            logger.warning("Could not persist Hermes port: %s", exc)

    def run(self) -> None:
        with self._server_lock:
            if self._stopped:
                return
            self._server = make_server("127.0.0.1", self._port, self._app, threaded=True)
        logger.info("HermesAgent HTTP gateway on 127.0.0.1:%d", self._port)
        try:
            self._server.serve_forever()
        finally:
            with self._server_lock:
                server = self._server
                self._server = None
            if server is not None:
                server.server_close()

    def stop(self) -> None:
        with self._server_lock:
            self._stopped = True
            server = self._server
        if server is not None:
            server.shutdown()
