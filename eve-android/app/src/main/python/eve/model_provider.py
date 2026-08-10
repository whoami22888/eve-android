"""Model-provider abstraction for EVE Agent Hub.

Supports any OpenAI-compatible chat endpoint, including local providers such
as Ollama when reachable from the Android runtime. No API key is hard-coded.
Configuration is read from EVE_DATA_DIR/model_config.json or environment vars.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Dict, List, Optional


@dataclass
class ModelConfig:
    base_url: str
    model: str
    api_key: str = ""
    timeout_seconds: int = 120


class ModelProviderError(RuntimeError):
    pass


def load_model_config(data_dir: str) -> ModelConfig:
    path = os.path.join(data_dir, "model_config.json")
    values: Dict[str, object] = {}
    try:
        if os.path.exists(path):
            with open(path, "r", encoding="utf-8") as fh:
                values = json.load(fh) or {}
    except (OSError, ValueError) as exc:
        raise ModelProviderError(f"Invalid model_config.json: {exc}")

    base_url = str(values.get("base_url") or os.environ.get("EVE_MODEL_BASE_URL") or "").strip().rstrip("/")
    model = str(values.get("model") or os.environ.get("EVE_MODEL_NAME") or "").strip()
    api_key = str(values.get("api_key") or os.environ.get("EVE_MODEL_API_KEY") or "").strip()
    timeout = int(values.get("timeout_seconds") or os.environ.get("EVE_MODEL_TIMEOUT") or 120)

    if not base_url or not model:
        raise ModelProviderError(
            "Model provider is not configured. Set EVE_MODEL_BASE_URL and "
            "EVE_MODEL_NAME or create model_config.json."
        )
    return ModelConfig(base_url, model, api_key, max(10, timeout))


class OpenAICompatibleProvider:
    """Minimal dependency-free client for /v1/chat/completions endpoints."""

    def __init__(self, config: ModelConfig):
        self.config = config

    def complete(self, system: str, user: str, temperature: float = 0.1) -> str:
        payload = json.dumps({
            "model": self.config.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": temperature,
        }).encode("utf-8")
        url = self.config.base_url
        if not url.endswith("/chat/completions"):
            url += "/v1/chat/completions"
        headers = {"Content-Type": "application/json"}
        if self.config.api_key:
            headers["Authorization"] = f"Bearer {self.config.api_key}"
        request = urllib.request.Request(url, data=payload, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=self.config.timeout_seconds) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")[:1000]
            raise ModelProviderError(f"Model HTTP {exc.code}: {detail}") from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise ModelProviderError(f"Model connection failed: {exc}") from exc

        try:
            data = json.loads(raw)
            content = data["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise ModelProviderError("Provider returned an unexpected response") from exc
        return str(content)


def build_provider(data_dir: str) -> OpenAICompatibleProvider:
    return OpenAICompatibleProvider(load_model_config(data_dir))
