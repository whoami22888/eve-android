"""Model-backed five-stage Agent Hub pipeline.

Pipeline: Planner -> Coder -> Reviewer -> Tester -> Security.
EVE remains the orchestrator; the selected provider can automatically choose
an appropriate model per stage when the model setting is ``auto``.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
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
    "npm test", "npm run test", "npm run lint", "pytest", "python -m pytest",
)


def _json(text: str) -> Dict:
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        return {"summary": text.strip()}
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        return {"summary": text.strip()}


class AgentHubAgent:
    def __init__(self, log=None, data_dir=None):
        self.task_queue = None
        self.log = log or (lambda message, level="INFO": None)
        self.data_dir = data_dir or os.environ.get("EVE_DATA_DIR") or "."
        self.provider = None
        try:
            self.provider = build_provider(self.data_dir)
        except ModelProviderError as exc:
            self.log(f"Model provider not configured: {exc}", "WARN")

    def set_task_queue(self, q):
        self.task_queue = q

    def can_handle(self, task: Task) -> bool:
        return task.action == "agent_hub"

    def _call(self, stage: str, user: str) -> Dict:
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

    def _run_tests(self, workspace: ProjectWorkspace, commands: List[str]) -> List[Dict]:
        results = []
        for command in commands[:3]:
            if command not in _ALLOWED_TEST_COMMANDS:
                results.append({"command": command, "status": "skipped", "reason": "not allowlisted"})
                continue
            try:
                proc = subprocess.run(command.split(), cwd=str(workspace.root), capture_output=True,
                                      text=True, timeout=120, check=False)
                results.append({"command": command, "status": "passed" if proc.returncode == 0 else "failed",
                                "exit_code": proc.returncode, "output": (proc.stdout + proc.stderr)[-4000:]})
            except (OSError, subprocess.TimeoutExpired) as exc:
                results.append({"command": command, "status": "error", "output": str(exc)})
        return results

    def assign_task(self, task: Task) -> None:
        prompt = str(task.params.get("task", "")).strip()
        project = str(task.params.get("project", "default")).strip() or "default"
        if not prompt:
            task.status = "failed"
            task.error = "Agent Hub task is empty"
            return

        task.status = "running"
        workspace = ProjectWorkspace(self.data_dir, project)
        try:
            snapshot = workspace.snapshot()
            self.log(f"Agent Hub planner: {project}")
            plan = self._call("planner", f"USER REQUEST:\n{prompt}\n\nWORKSPACE:\n{snapshot}")

            self.log("Agent Hub coder: implementing plan")
            coder = self._call("coder", f"REQUEST:\n{prompt}\n\nPLAN:\n{json.dumps(plan)}\n\nWORKSPACE:\n{snapshot}")
            changed = self._apply_coder_files(workspace, coder)

            after = workspace.snapshot()
            self.log("Agent Hub reviewer: checking implementation")
            review = self._call("reviewer", f"REQUEST:\n{prompt}\n\nPLAN:\n{json.dumps(plan)}\n\nCHANGED:\n{changed}\n\nWORKSPACE:\n{after}")

            self.log("Agent Hub tester: preparing and running tests")
            test_plan = self._call("tester", f"REQUEST:\n{prompt}\n\nREVIEW:\n{json.dumps(review)}\n\nWORKSPACE:\n{after}")
            test_results = self._run_tests(workspace, test_plan.get("commands", []))

            self.log("Agent Hub security: final review")
            security = self._call("security", f"REQUEST:\n{prompt}\n\nREVIEW:\n{json.dumps(review)}\n\nTEST RESULTS:\n{json.dumps(test_results)}\n\nWORKSPACE:\n{after}")

            task.result = json.dumps({
                "project": project,
                "plan": plan,
                "changed_files": changed,
                "review": review,
                "tests": test_results,
                "security": security,
            })
            task.status = "completed"
            task.completed_at = time.time()
            self.log("Agent Hub pipeline completed")
        except (ModelProviderError, WorkspaceError, OSError, ValueError) as exc:
            task.status = "failed"
            task.error = str(exc)
            self.log(f"Agent Hub failed: {exc}", "ERROR")

    def run(self) -> None:
        return None
