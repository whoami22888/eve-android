import json
import os
import tempfile
import unittest
from unittest.mock import patch

from eve.agent_hub_agent import AgentHubAgent
from eve.model_provider import (
    AnthropicProvider,
    GeminiProvider,
    ModelConfig,
    ModelProviderError,
    OpenAICompatibleProvider,
    _auto_model,
    load_model_config,
)
from eve.workspace import ProjectWorkspace, WorkspaceError


class ProviderTests(unittest.TestCase):
    def test_auto_routing_selects_stage_models(self):
        self.assertEqual(_auto_model("deepseek", "auto", "coder"), "deepseek-v4-pro")
        self.assertEqual(_auto_model("deepseek", "auto", "planner"), "deepseek-v4-flash")
        self.assertEqual(_auto_model("gemini", "auto", "security"), "gemini-3.1-pro-preview")
        self.assertEqual(_auto_model("openai", "auto", "tester"), "gpt-5-mini")
        self.assertEqual(_auto_model("anthropic", "auto", "reviewer"), "claude-opus-4-1")

    def test_openai_compatible_presets_share_adapter(self):
        for provider in ("openai", "deepseek", "openrouter", "ollama"):
            with self.subTest(provider=provider):
                config = ModelConfig(provider, "https://example.test", "auto", "key")
                adapter = OpenAICompatibleProvider(config)
                with patch("eve.model_provider._request_json", return_value={"choices": [{"message": {"content": "OK"}}]}) as request:
                    self.assertEqual(adapter.complete("s", "u", stage="planner"), "OK")
                    payload = request.call_args.args[1]
                    self.assertTrue(payload["model"])

    def test_anthropic_response(self):
        config = ModelConfig("anthropic", "https://example.test", "auto", "key")
        provider = AnthropicProvider(config)
        with patch("eve.model_provider._request_json", return_value={"content": [{"type": "text", "text": "OK"}]}):
            self.assertEqual(provider.complete("s", "u", stage="coder"), "OK")

    def test_gemini_3_does_not_send_deprecated_temperature(self):
        config = ModelConfig("gemini", "https://example.test", "auto", "key")
        provider = GeminiProvider(config)
        with patch("eve.model_provider._request_json", return_value={"candidates": [{"content": {"parts": [{"text": "OK"}]}}]}) as request:
            self.assertEqual(provider.complete("s", "u", stage="planner"), "OK")
            payload = request.call_args.args[1]
            self.assertNotIn("generationConfig", payload)

    def test_missing_model_is_clean_configuration_error(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ModelProviderError):
                load_model_config(directory)

    def test_invalid_timeout_is_clean_configuration_error(self):
        with tempfile.TemporaryDirectory() as directory:
            with open(os.path.join(directory, "model_config.json"), "w", encoding="utf-8") as fh:
                json.dump({"provider": "openai", "model": "auto", "timeout_seconds": "bad"}, fh)
            with self.assertRaises(ModelProviderError):
                load_model_config(directory)

    def test_timeout_is_clamped(self):
        with tempfile.TemporaryDirectory() as directory:
            with open(os.path.join(directory, "model_config.json"), "w", encoding="utf-8") as fh:
                json.dump({"provider": "openai", "model": "auto", "timeout_seconds": 9999}, fh)
            self.assertEqual(load_model_config(directory).timeout_seconds, 600)

    def test_deepseek_v4_config_loads(self):
        with tempfile.TemporaryDirectory() as directory:
            with open(os.path.join(directory, "model_config.json"), "w", encoding="utf-8") as fh:
                json.dump({"provider": "deepseek", "model": "auto", "api_key": "test"}, fh)
            config = load_model_config(directory)
            self.assertEqual(config.provider, "deepseek")
            self.assertEqual(config.model, "auto")
            self.assertEqual(config.base_url, "https://api.deepseek.com")

    def test_workspace_rejects_project_and_file_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(WorkspaceError):
                ProjectWorkspace(directory, "../escape")
            workspace = ProjectWorkspace(directory, "demo")
            with self.assertRaises(WorkspaceError):
                workspace.write("../escape.txt", "blocked")
            with self.assertRaises(WorkspaceError):
                workspace.write("/absolute.txt", "blocked")

    def test_agent_hub_refreshes_provider_after_configuration_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            with open(os.path.join(directory, "model_config.json"), "w", encoding="utf-8") as fh:
                json.dump({"provider": "openai", "model": "auto", "api_key": "test"}, fh)
            agent = AgentHubAgent(data_dir=directory)
            self.assertEqual(agent.provider.config.provider, "openai")
            with open(os.path.join(directory, "model_config.json"), "w", encoding="utf-8") as fh:
                json.dump({"provider": "deepseek", "model": "auto", "api_key": "test"}, fh)
            agent._refresh_provider()
            self.assertEqual(agent.provider.config.provider, "deepseek")


if __name__ == "__main__":
    unittest.main()
