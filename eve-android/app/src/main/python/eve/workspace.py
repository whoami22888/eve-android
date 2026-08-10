"""Sandboxed project-file operations for Agent Hub agents."""

from __future__ import annotations

import re
from pathlib import Path
from typing import List


class WorkspaceError(RuntimeError):
    pass


_PROJECT_NAME = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


class ProjectWorkspace:
    def __init__(self, root: str, project: str = "default"):
        if not _PROJECT_NAME.fullmatch(project):
            raise WorkspaceError("Invalid project name")
        base = Path(root).resolve() / "projects"
        self.root = (base / project).resolve()
        try:
            self.root.relative_to(base)
        except ValueError as exc:
            raise WorkspaceError("Project path escapes workspace root") from exc
        self.root.mkdir(parents=True, exist_ok=True)

    def _safe(self, relative: str) -> Path:
        if not relative or relative.startswith(("/", "\\")):
            raise WorkspaceError("Workspace path must be relative")
        candidate = (self.root / relative).resolve()
        try:
            candidate.relative_to(self.root)
        except ValueError as exc:
            raise WorkspaceError("Path escapes project workspace") from exc
        return candidate

    def list_files(self, limit: int = 500) -> List[str]:
        files = []
        for path in self.root.rglob("*"):
            if path.is_file():
                files.append(path.relative_to(self.root).as_posix())
                if len(files) >= limit:
                    break
        return sorted(files)

    def read(self, relative: str, max_bytes: int = 200_000) -> str:
        path = self._safe(relative)
        if not path.is_file():
            raise WorkspaceError(f"File not found: {relative}")
        data = path.read_bytes()
        if len(data) > max_bytes:
            raise WorkspaceError(f"File too large: {relative}")
        return data.decode("utf-8", errors="replace")

    def write(self, relative: str, content: str) -> None:
        path = self._safe(relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def delete(self, relative: str) -> None:
        path = self._safe(relative)
        if path.is_file():
            path.unlink()

    def snapshot(self, max_files: int = 80, max_bytes_each: int = 50_000) -> str:
        chunks = []
        for relative in self.list_files(max_files):
            try:
                content = self.read(relative, max_bytes_each)
            except WorkspaceError:
                continue
            chunks.append(f"\n--- FILE: {relative} ---\n{content}")
        return "".join(chunks)
