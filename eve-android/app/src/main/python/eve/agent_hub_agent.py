"""Model-backed five-stage Agent Hub pipeline."""

from __future__ import annotations

import json
import os
import re
import subprocess
import threading
import time
from typing import Dict, List

from .model_provider import ModelProviderError, build_provider
from .task_queue import Task
from .workspace import ProjectWorkspace, WorkspaceError

STAGES = ("planner", "coder", "reviewer", "tester", "security")

_SYSTEM = {
    "planner": """You are EVE Planner. Turn the user's request into a precise implementation plan. Return JSON with keys: summary, steps (array), files_to_change (array). Do not invent files when the workspace snapshot is provided.""",
    "coder": """You are EVE Coder. Implement the requested plan. Return JSON with keys: summary, files (array of {path, content}), notes. Only return complete UTF-8 text for files that should be created or replaced. Never use paths outside the project workspace.""",
    "reviewer": """You are EVE Reviewer. Review the proposed implementation against the plan and workspace. Return JSON with keys: approved (boolean), findings (array), required_changes (array). Do not modify files.""",
    "tester": """You are EVE Tester. Assess whether the implementation is testable and provide safe test commands. Return JSON with keys: tests (array), commands (array), findings (array). Commands must be common project test commands, not destructive shell operations.""",
    "security": """You are EVE Security Reviewer. Inspect the implementation for credential leaks, unsafe path handling, dependency risks, injection problems and insecure network behavior. Return JSON with keys: approved (boolean), severity, findings (array), fixes (array). Do not modify files.""",
}

_ALLOWED_TEST_COMMANDS = (
    "./gradlew test", "./gradlew :app:test", "./gradlew lint",
    "./gradlew :app:testDebugUnitTest", "./gradlew :app:assembleDebug",
    "npm test", "npm run test", "npm run lint", "pytest", "python -m pytest",
)
_SAFE_ENV_KEYS = (
    "PATH", "HOME", "TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL", "USER",
    "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME",
)
_ACTIVE_AGENT = None


def _json(text: str) -> Dict:
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        return {"summary": text.strip()}
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        return {"summary": text.strip()}


def _safe_env() -> Dict[str, str]:
    return {key: value for key, value in os.environ.items() if key in _SAFE_ENV_KEYS}


def refresh_default_provider(data_dir: str) -> bool:
    """Refresh the live Agent Hub instance after Android settings are saved."""
    global _ACTIVE_AGENT
    if _ACTIVE_AGENT is not None and os.path.abspath(_ACTIVE_AGENT.data_dir) == os.path.abspath(data_dir):
        _ACTIVE_AGENT._refresh_provider()
        return True
    return False


class AgentHubAgent:
    def __init__(self, log=None, data_dir=None):
        global _ACTIVE_AGENT
        if isinstance(log, str) and data_dir is None:
            data_dir, log = log, None
        self.task_queue = None
        self.log = log if callable(log) else (lambda message, level="INFO": None)
        self.data_dir = data_dir or os.environ.get("EVE_DATA_DIR") or "."
        self.provider = None
        self._cancel_events = {}
        self._active_processes = {}
        self._state_lock = threading.Lock()
        self._refresh_provider()
        _ACTIVE_AGENT = self

    def _refresh_provider(self) -> None:
        try:
            self.provider = build_provider(self.data_dir)
        except ModelProviderError as exc:
            self.provider = None
            self.log(f"Model provider not configured: {exc}", "WARN")

    def set_task_queue(self, q):
        self.task_queue = q

    def can_handle(self, task: Task) -> bool:
        return task.action in {"agent_hub", "agent_hub_cancel"}

    def _cancelled(self, task_id: str) -> bool:
        with self._state_lock:
            event = self._cancel_events.get(task_id)
            return event.is_set() if event else False

    def _call(self, stage: str, user: str, task_id: str = "") -> Dict:
        if task_id and self._cancelled(task_id):
            raise RuntimeError("Agent Hub task cancelled")
        self._refresh_provider()
        if self.provider is None:
            raise ModelProviderError("Configure the model provider before running Agent Hub")
        self.log(f"Agent Hub: {stage} calling model (EVE routing)")
        return _json(self.provider.complete(_SYSTEM[stage], user, stage=stage))

    def _apply_coder_files(self, workspace: ProjectWorkspace, result: Dict) -> List[str]:
        changed = []
        for item in result.get("files", []) if isinstance(result.get("files", []), list) else []:
            if not isinstance(item, dict) or not item.get("path") or not isinstance(item.get("content"), str):
                continue
            workspace.write(str(item["path"]), item["content"])
            changed.append(str(item["path"]))
        return changed

    def _redact(self, text: str) -> str:
        redacted = str(text)
        key = getattr(getattr(self.provider, "config", None), "api_key", "") if self.provider else ""
        return redacted.replace(key, "[REDACTED]") if key else redacted

    def _run_tests(self, workspace: ProjectWorkspace, commands: List[str], task_id: str = "") -> List[Dict]:
        results = []
        for command in commands[:3]:
            if self._cancelled(task_id):
                results.append({"command": command, "status": "cancelled"})
                break
            if command not in _ALLOWED_TEST_COMMANDS:
                results.append({"command": command, "status": "skipped", "reason": "not allowlisted"})
                continue
            proc = None
            try:
                proc = subprocess.Popen(command.split(), cwd=str(workspace.root), env=_safe_env(), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                with self._state_lock:
                    self._active_processes[task_id] = proc
                try:
                    output, _ = proc.communicate(timeout=120)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    output, _ = proc.communicate()
                    raise subprocess.TimeoutExpired(command, 120, output=output)
                results.append({"command": command, "status": "passed" if proc.returncode == 0 else "failed", "exit_code": proc.returncode, "output": self._redact((output or "")[-4000:])})
            except subprocess.TimeoutExpired as exc:
                results.append({"command": command, "status": "error", "output": self._redact(str(exc))})
            except OSError as exc:
                results.append({"command": command, "status": "error", "output": self._redact(str(exc))})
            finally:
                with self._state_lock:
                    self._active_processes.pop(task_id, None)
        return results

    def _cancel_all(self) -> None:
        with self._state_lock:
            events = list(self._cancel_events.values())
            processes = list(self._active_processes.values())
        for event in events:
            event.set()
        for process in processes:
            try:
                process.kill()
            except OSError:
                pass

    def assign_task(self, task: Task) -> None:
        if task.action == "agent_hub_cancel":
            self._cancel_all()
            task.status = "completed"
            task.result = "Agent Hub cancellation requested"
            task.completed_at = time.time()
            return

        prompt = str(task.params.get("task", "")).strip()
        project = str(task.params.get("project", "default")).strip() or "default"
        if not prompt:
            task.status = "failed"
            task.error = "Agent Hub task is empty"
            return

        task.status = "running"
        with self._state_lock:
            self._cancel_events[task.id] = threading.Event()
        try:
            workspace = ProjectWorkspace(self.data_dir, project)
            snapshot = workspace.snapshot()
            self.log(f"Agent Hub planner: {project}")
            plan = self._call("planner", f"USER REQUEST:\n{prompt}\n\nWORKSPACE:\n{snapshot}", task.id)
            self.log("Agent Hub coder: implementing plan")
            coder = self._call("coder", f"REQUEST:\n{prompt}\n\nPLAN:\n{json.dumps(plan)}\n\nWORKSPACE:\n{snapshot}", task.id)
            changed = self._apply_coder_files(workspace, coder)
            after = workspace.snapshot()
            self.log("Agent Hub reviewer: checking implementation")
            review = self._call("reviewer", f"REQUEST:\n{prompt}\n\nPLAN:\n{json.dumps(plan)}\n\nCHANGED:\n{changed}\n\nWORKSPACE:\n{after}", task.id)
            self.log("Agent Hub tester: preparing and running tests")
            test_plan = self._call("tester", f"REQUEST:\n{prompt}\n\nREVIEW:\n{json.dumps(review)}\n\nWORKSPACE:\n{after}", task.id)
            test_results = self._run_tests(workspace, test_plan.get("commands", []), task.id)
            if self._cancelled(task.id):
                raise RuntimeError("Agent Hub task cancelled")
            self.log("Agent Hub security: final review")
            security = self._call("security", f"REQUEST:\n{prompt}\n\nREVIEW:\n{json.dumps(review)}\n\nTEST RESULTS:\n{json.dumps(test_results)}\n\nWORKSPACE:\n{after}", task.id)
            task.result = json.dumps({"project": project, "plan": plan, "changed_files": changed, "review": review, "tests": test_results, "security": security})
            task.status = "completed"
            task.completed_at = time.time()
            self.log("Agent Hub pipeline completed")
        except (ModelProviderError, WorkspaceError, OSError, ValueError, RuntimeError) as exc:
            task.status = "failed"
            task.error = str(exc)
            self.log(f"Agent Hub failed: {exc}", "ERROR")
        finally:
            with self._state_lock:
                self._cancel_events.pop(task.id, None)
                self._active_processes.pop(task.id, None)

    def run(self) -> None:
        return None
