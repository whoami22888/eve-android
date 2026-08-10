"""Model-provider abstraction for EVE Agent Hub.

Supports OpenAI-compatible providers plus native Anthropic and Gemini APIs.
Provider selection is read from EVE_DATA_DIR/model_config.json or environment
variables. Secrets are supplied by the Android app and are never hard-coded.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Dict


@dataclass
class ModelConfig:
    provider: str
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

    provider = str(values.get("provider") or os.environ.get("EVE_MODEL_PROVIDER") or "openai").strip().lower()
    base_url = str(values.get("base_url") or os.environ.get("EVE_MODEL_BASE_URL") or "").strip().rstrip("/")
    model = str(values.get("model") or os.environ.get("EVE_MODEL_NAME") or "").strip()
    api_key = str(values.get("api_key") or os.environ.get("EVE_MODEL_API_KEY") or "").strip()
    timeout = int(values.get("timeout_seconds") or os.environ.get("EVE_MODEL_TIMEOUT") or 120)

    defaults = {
        "openai": "https://api.openai.com",
        "anthropic": "https://api.anthropic.com",
        "gemini": "https://generativelanguage.googleapis.com",
        "deepseek": "https://api.deepseek.com",
        "openrouter": "https://openrouter.ai/api",
        "ollama": "http://127.0.0.1:11434",
    }
    if not base_url:
        base_url = defaults.get(provider, "")
    if not model:
        raise ModelProviderError("Model provider is not configured: choose a model before running Agent Hub")
    if provider not in defaults and not base_url:
        raise ModelProviderError("Unknown provider requires a base URL")
    return ModelConfig(provider, base_url, model, api_key, max(10, timeout))


def _request_json(url: str, payload: dict, headers: dict, timeout: int) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1500]
        raise ModelProviderError(f"Model HTTP {exc.code}: {detail}") from exc
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise ModelProviderError(f"Model connection failed: {exc}") from exc
    try:
        return json.loads(raw)
    except ValueError as exc:
        raise ModelProviderError("Provider returned invalid JSON") from exc


class OpenAICompatibleProvider:
    """Client for OpenAI-compatible chat-completions endpoints."""

    def __init__(self, config: ModelConfig):
        self.config = config

    def complete(self, system: str, user: str, temperature: float = 0.1) -> str:
        payload = {
            "model": self.config.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": temperature,
        }
        url = self.config.base_url
        if url.endswith("/v1"):
            url += "/chat/completions"
        elif not url.endswith("/chat/completions"):
            url += "/v1/chat/completions"
        headers = {}
        if self.config.api_key:
            headers["Authorization"] = f"Bearer {self.config.api_key}"
        data = _request_json(url, payload, headers, self.config.timeout_seconds)
        try:
            return str(data["choices"][0]["message"]["content"])
        except (KeyError, IndexError, TypeError) as exc:
            raise ModelProviderError("OpenAI-compatible provider returned an unexpected response") from exc


class AnthropicProvider:
    """Native Anthropic Messages API client."""

    def __init__(self, config: ModelConfig):
        self.config = config

    def complete(self, system: str, user: str, temperature: float = 0.1) -> str:
        if not self.config.api_key:
            raise ModelProviderError("Anthropic requires an API key")
        payload = {
            "model": self.config.model,
            "max_tokens": 4096,
            "system": system,
            "messages": [{"role": "user", "content": user}],
            "temperature": temperature,
        }
        url = self.config.base_url.rstrip("/") + "/v1/messages"
        headers = {
            "x-api-key": self.config.api_key,
            "anthropic-version": "2023-06-01",
        }
        data = _request_json(url, payload, headers, self.config.timeout_seconds)
        try:
            blocks = data["content"]
            return "\n".join(str(block["text"]) for block in blocks if isinstance(block, dict) and block.get("type") == "text")
        except (KeyError, TypeError) as exc:
            raise ModelProviderError("Anthropic returned an unexpected response") from exc


class GeminiProvider:
    """Native Google Gemini generateContent API client."""

    def __init__(self, config: ModelConfig):
        self.config = config

    def complete(self, system: str, user: str, temperature: float = 0.1) -> str:
        if not self.config.api_key:
            raise ModelProviderError("Gemini requires an API key")
        payload = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": {"temperature": temperature},
        }
        url = self.config.base_url.rstrip("/") + f"/v1beta/models/{urllib.parse.quote(self.config.model, safe='')}:generateContent"
        url += "?" + urllib.parse.urlencode({"key": self.config.api_key})
        data = _request_json(url, payload, {}, self.config.timeout_seconds)
        try:
            parts = data["candidates"][0]["content"]["parts"]
            return "\n".join(str(part["text"]) for part in parts if isinstance(part, dict) and "text" in part)
        except (KeyError, IndexError, TypeError) as exc:
            raise ModelProviderError("Gemini returned an unexpected response") from exc


def build_provider(data_dir: str):
    config = load_model_config(data_dir)
    if config.provider == "anthropic":
        return AnthropicProvider(config)
    if config.provider == "gemini":
        return GeminiProvider(config)
    return OpenAICompatibleProvider(config)
