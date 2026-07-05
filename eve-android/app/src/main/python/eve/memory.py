"""
eve/memory.py
=============
Persistent key-value memory store for EVE agents.

Data is stored as a JSON object in ``$EVE_DATA_DIR/memory.json`` so it is
readable and editable by the Kotlin MemoryEditorFragment.

Thread safety: All public functions acquire a module-level lock before
touching the file, so multiple agents can read/write concurrently.

Usage:
    from eve.memory import remember, recall, forget, all_memories

    remember("user_name", "Alice")
    name = recall("user_name")          # "Alice"
    forget("user_name")
    memories = all_memories()           # {"key": "value", ...}
"""

import json
import os
import threading

_lock = threading.Lock()
_DATA_DIR = os.environ.get("EVE_DATA_DIR", "/data/data/com.eve.agent/files")
_MEMORY_FILE = os.path.join(_DATA_DIR, "memory.json")


def _load() -> dict:
    """Read the current memory JSON file. Returns {} if missing or corrupt."""
    try:
        if os.path.exists(_MEMORY_FILE):
            with open(_MEMORY_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
    except (json.JSONDecodeError, OSError):
        pass
    return {}


def _save(data: dict) -> None:
    """Atomically write the memory dict to disk."""
    os.makedirs(_DATA_DIR, exist_ok=True)
    tmp = _MEMORY_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
    os.replace(tmp, _MEMORY_FILE)   # atomic on POSIX


# ── Public API ────────────────────────────────────────────────────────────────

def remember(key: str, value) -> None:
    """Store [value] under [key]. Value must be JSON-serialisable."""
    with _lock:
        data = _load()
        data[key] = value
        _save(data)


def recall(key: str, default=None):
    """Return the value stored under [key], or [default] if not found."""
    with _lock:
        return _load().get(key, default)


def forget(key: str) -> bool:
    """Delete [key] from memory. Returns True if the key existed."""
    with _lock:
        data = _load()
        if key in data:
            del data[key]
            _save(data)
            return True
        return False


def all_memories() -> dict:
    """Return a snapshot of the entire memory store."""
    with _lock:
        return dict(_load())


def clear() -> None:
    """Wipe all stored memories. Use with caution."""
    with _lock:
        _save({})
