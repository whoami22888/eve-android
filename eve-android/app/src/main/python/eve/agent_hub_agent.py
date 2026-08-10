"""Model-backed, bounded five-stage Agent Hub pipeline."""

from __future__ import annotations

import json
import os
import re
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List

from .model_provider import ModelProviderError, build_provider
from .task_queue import Task
from .workspace import ProjectWorkspace, WorkspaceError

STAGES = ("planner", "researcher", "coder", "tester", "reviewer")
_STAGE_LABELS = {"planner": "Plan", "researcher": "Research", "coder": "Code", "tester": "Test", "reviewer": "Review"}
_SYSTEM = {
    "planner": "You are EVE Planner. Turn the user's request into a precise implementation plan. Return JSON with keys: summary, steps (array), files_to_change (array). Do not invent files when the workspace snapshot is provided.",
    "researcher": "You are EVE Researcher. Inspect the supplied workspace and plan, identify relevant implementations, compatibility constraints, dependencies and test requirements. Return JSON with keys: findings, constraints, recommendations. Do not modify files.",
    "coder": "You are EVE Coder. Implement the requested plan using the research. Return JSON with keys: summary, files (array of {path, content}), notes. Only return complete UTF-8 text for files that should be created or replaced. Never use paths outside the project workspace.",
    "tester": "You are EVE Tester. Assess the implementation and provide safe test commands. Return JSON with keys: tests, commands, findings. Commands must be common project test commands, not destructive shell operations.",
    "reviewer": "You are EVE Reviewer. Review the implementation, test results and security implications. Return JSON with keys: approved, severity, findings, required_changes. Do not modify files.",
}
_ALLOWED_TEST_COMMANDS = ("./gradlew test", "./gradlew :app:test", "./gradlew lint", "./gradlew :app:testDebugUnitTest", "./gradlew :app:assembleDebug", "npm test", "npm run test", "npm run lint", "pytest", "python -m pytest")
_SAFE_ENV_KEYS = ("PATH", "HOME", "TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL", "USER", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME")
_ACTIVE_AGENT = None


def _json(text: str) -> Dict:
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match: return {"summary": text.strip()}
    try: return json.loads(match.group(0))
    except json.JSONDecodeError: return {"summary": text.strip()}


def _safe_env() -> Dict[str, str]: return {key: value for key, value in os.environ.items() if key in _SAFE_ENV_KEYS}


def refresh_default_provider(data_dir: str) -> bool:
    global _ACTIVE_AGENT
    if _ACTIVE_AGENT is not None and os.path.abspath(_ACTIVE_AGENT.data_dir) == os.path.abspath(data_dir):
        _ACTIVE_AGENT._refresh_provider(); return True
    return False


class AgentHubAgent:
    def __init__(self, log=None, data_dir=None):
        global _ACTIVE_AGENT
        if isinstance(log, str) and data_dir is None: data_dir, log = log, None
        self.task_queue = None; self.log = log if callable(log) else (lambda message, level="INFO": None)
        self.data_dir = data_dir or os.environ.get("EVE_DATA_DIR") or "."; self.provider = None
        self._cancel_events = {}; self._pause_events = {}; self._active_processes = {}; self._task_objects = {}; self._task_specs = {}; self._task_stage = {}
        self._state_lock = threading.Lock(); self._executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="eve-agent-hub")
        self._refresh_provider(); _ACTIVE_AGENT = self

    def _refresh_provider(self) -> None:
        try: self.provider = build_provider(self.data_dir)
        except ModelProviderError as exc: self.provider = None; self.log(f"Model provider not configured: {exc}", "WARN")

    def set_task_queue(self, q): self.task_queue = q
    def can_handle(self, task: Task) -> bool: return task.action in {"agent_hub", "agent_hub_control"}

    def _emit(self, task_id: str, method: str, *args) -> None:
        try:
            from java import jclass
            getattr(jclass("com.eve.agent.EveKotlinBridge"), method)(*args)
        except Exception: pass

    def _emit_stage(self, task_id: str, stage: str, status: str, progress: int, message: str = "") -> None:
        self._task_stage[task_id] = stage; self.log(f"Agent Hub [{_STAGE_LABELS.get(stage, stage)}] {status}: {message}".strip())
        self._emit(task_id, "onPipelineStage", task_id, _STAGE_LABELS.get(stage, stage), status, int(progress), message)

    def _complete(self, task: Task) -> None:
        failed = task.status == "failed"; result = self._redact(task.error if failed else (task.result or ""))
        self._emit(task.id, "onTaskCompleted", task.id, task.action, result, failed)

    def _cancelled(self, task_id: str) -> bool:
        with self._state_lock:
            event = self._cancel_events.get(task_id); return event.is_set() if event else False

    def _wait_if_paused(self, task_id: str) -> None:
        while True:
            with self._state_lock: pause = self._pause_events.get(task_id); cancelled = self._cancel_events.get(task_id)
            if cancelled and cancelled.is_set(): raise RuntimeError("Agent Hub task cancelled")
            if pause is None or not pause.is_set(): return
            self._emit_stage(task_id, self._task_stage.get(task_id, "planner"), "paused", 0, "Pipeline paused"); time.sleep(0.2)

    def _call(self, stage: str, user: str, task_id: str = "") -> Dict:
        self._wait_if_paused(task_id)
        if task_id and self._cancelled(task_id): raise RuntimeError("Agent Hub task cancelled")
        self._refresh_provider()
        if self.provider is None: raise ModelProviderError("Configure the model provider before running Agent Hub")
        return _json(self.provider.complete(_SYSTEM[stage], user, stage=stage))

    def _apply_coder_files(self, workspace: ProjectWorkspace, result: Dict) -> List[str]:
        changed = []
        for item in result.get("files", []) if isinstance(result.get("files", []), list) else []:
            if not isinstance(item, dict) or not item.get("path") or not isinstance(item.get("content"), str): continue
            workspace.write(str(item["path"]), item["content"]); changed.append(str(item["path"]))
        return changed

    def _redact(self, text: str) -> str:
        redacted = str(text); secrets = set()
        if self.provider:
            key = getattr(getattr(self.provider, "config", None), "api_key", "")
            if key: secrets.add(key)
        for name, value in os.environ.items():
            if any(token in name.upper() for token in ("KEY", "TOKEN", "SECRET", "PASSWORD")) and value: secrets.add(value)
        for secret in sorted(secrets, key=len, reverse=True): redacted = redacted.replace(secret, "[REDACTED]")
        return redacted

    def _run_tests(self, workspace: ProjectWorkspace, commands: List[str], task_id: str = "") -> List[Dict]:
        results = []
        for command in commands[:3]:
            self._wait_if_paused(task_id)
            if self._cancelled(task_id): results.append({"command": command, "status": "cancelled"}); break
            if command not in _ALLOWED_TEST_COMMANDS: results.append({"command": command, "status": "skipped", "reason": "not allowlisted"}); continue
            proc = None
            try:
                proc = subprocess.Popen(command.split(), cwd=str(workspace.root), env=_safe_env(), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                with self._state_lock: self._active_processes[task_id] = proc
                try: output, _ = proc.communicate(timeout=120)
                except subprocess.TimeoutExpired:
                    proc.kill(); output, _ = proc.communicate(); raise subprocess.TimeoutExpired(command, 120, output=output)
                results.append({"command": command, "status": "passed" if proc.returncode == 0 else "failed", "exit_code": proc.returncode, "output": self._redact((output or "")[-4000:])})
            except subprocess.TimeoutExpired as exc: results.append({"command": command, "status": "error", "output": self._redact(str(exc))})
            except OSError as exc: results.append({"command": command, "status": "error", "output": self._redact(str(exc))})
            finally:
                with self._state_lock: self._active_processes.pop(task_id, None)
        return results

    def _control(self, task: Task) -> None:
        target = str(task.params.get("task_id", "")).strip().lower(); command = str(task.params.get("command", "")).strip().lower()
        with self._state_lock: targets = list(self._task_objects) if target == "all" else [target]
        matched = False
        for task_id in targets:
            with self._state_lock: pause = self._pause_events.get(task_id); cancel = self._cancel_events.get(task_id); target_task = self._task_objects.get(task_id)
            if not target_task: continue
            matched = True
            if command == "pause" and pause: pause.set(); task.result = f"Paused {task_id}"
            elif command == "resume" and pause: pause.clear(); task.result = f"Resumed {task_id}"
            elif command == "cancel" and cancel:
                cancel.set()
                with self._state_lock: proc = self._active_processes.get(task_id)
                if proc:
                    try: proc.kill()
                    except OSError: pass
                task.result = f"Cancellation requested for {task_id}"
            elif command == "retry" and target_task.status == "failed":
                with self._state_lock: self._cancel_events[task_id] = threading.Event(); self._pause_events[task_id] = threading.Event()
                target_task.status = "running"; self._executor.submit(self._execute_pipeline, target_task, self._task_specs.get(task_id, {}), self._task_stage.get(task_id, "planner")); task.result = f"Retry started for {task_id}"
            else: matched = False
        if not matched: task.status = "failed"; task.error = f"Unknown or unavailable Agent Hub control: {command}"; return
        task.status = "completed"

    def assign_task(self, task: Task) -> None:
        if task.action == "agent_hub_control": self._control(task); return
        prompt = str(task.params.get("task", "")).strip(); project = str(task.params.get("project", "default")).strip() or "default"
        if not prompt: task.status = "failed"; task.error = "Agent Hub task is empty"; return
        try: ProjectWorkspace(self.data_dir, project)
        except WorkspaceError as exc: task.status = "failed"; task.error = str(exc); return
        task.status = "queued"
        with self._state_lock:
            self._cancel_events[task.id] = threading.Event(); self._pause_events[task.id] = threading.Event(); self._task_objects[task.id] = task; self._task_specs[task.id] = {"prompt": prompt, "project": project}
        self._emit_stage(task.id, "planner", "queued", 0, "Pipeline queued"); self._executor.submit(self._execute_pipeline, task, {"prompt": prompt, "project": project}, "planner")

    def _execute_pipeline(self, task: Task, spec: Dict, start_stage: str = "planner") -> None:
        prompt = str(spec.get("prompt", "")).strip(); project = str(spec.get("project", "default")).strip() or "default"; task.status = "running"
        stage_index = {name: i for i, name in enumerate(STAGES)}; start = stage_index.get(start_stage, 0)
        try:
            workspace = ProjectWorkspace(self.data_dir, project); snapshot = workspace.snapshot(); context: Dict = {}
            if start <= 0:
                self._emit_stage(task.id, "planner", "running", 10, "Building implementation plan"); context["plan"] = self._call("planner", f"USER REQUEST:\n{prompt}\n\nWORKSPACE:\n{snapshot}", task.id)
            if start <= 1:
                self._emit_stage(task.id, "researcher", "running", 25, "Inspecting workspace and compatibility"); context["research"] = self._call("researcher", f"REQUEST:\n{prompt}\n\nPLAN:\n{json.dumps(context.get('plan', {}))}\n\nWORKSPACE:\n{snapshot}", task.id)
            if start <= 2:
                self._emit_stage(task.id, "coder", "running", 45, "Implementing planned changes"); context["coder"] = self._call("coder", f"REQUEST:\n{prompt}\n\nPLAN:\n{json.dumps(context.get('plan', {}))}\n\nRESEARCH:\n{json.dumps(context.get('research', {}))}\n\nWORKSPACE:\n{snapshot}", task.id); context["changed"] = self._apply_coder_files(workspace, context["coder"]); snapshot = workspace.snapshot()
            if start <= 3:
                self._emit_stage(task.id, "tester", "running", 65, "Preparing and running safe tests"); test_plan = self._call("tester", f"REQUEST:\n{prompt}\n\nCHANGED:\n{context.get('changed', [])}\n\nWORKSPACE:\n{snapshot}", task.id); context["tests"] = self._run_tests(workspace, test_plan.get("commands", []), task.id)
            if start <= 4:
                self._emit_stage(task.id, "reviewer", "running", 85, "Final implementation and security review"); context["review"] = self._call("reviewer", f"REQUEST:\n{prompt}\n\nCHANGED:\n{context.get('changed', [])}\n\nTEST RESULTS:\n{json.dumps(context.get('tests', []))}\n\nWORKSPACE:\n{snapshot}", task.id)
            if self._cancelled(task.id): raise RuntimeError("Agent Hub task cancelled")
            task.result = self._redact(json.dumps({"project": project, "stages": context}, ensure_ascii=False)); task.status = "completed"; task.completed_at = time.time(); self._emit_stage(task.id, "reviewer", "completed", 100, "Pipeline completed"); self._complete(task)
        except (ModelProviderError, WorkspaceError, OSError, ValueError, RuntimeError) as exc:
            task.status = "failed"; task.error = self._redact(str(exc)); self._emit_stage(task.id, self._task_stage.get(task.id, start_stage), "failed", max(1, stage_index.get(self._task_stage.get(task.id, start_stage), 0) * 20), task.error); self.log(f"Agent Hub failed: {task.error}", "ERROR"); self._complete(task)
        finally:
            with self._state_lock: self._cancel_events.pop(task.id, None); self._pause_events.pop(task.id, None); self._active_processes.pop(task.id, None)

    def run(self) -> None: return None
