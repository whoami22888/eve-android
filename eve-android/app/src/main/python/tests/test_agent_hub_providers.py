import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from eve.agent_hub_agent import AgentHubAgent, _safe_env
from eve.hermes_agent import HermesAgent
from eve.orchestrator import EVE
from eve.model_provider import (
    AnthropicProvider,
    GeminiProvider,
    ModelConfig,
    ModelProviderError,
    OpenAICompatibleProvider,
    _auto_model,
    load_model_config,
)
from eve.task_queue import Task, TaskQueue
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

    def test_agent_hub_accepts_kotlin_positional_data_dir(self):
        with tempfile.TemporaryDirectory() as directory:
            with open(os.path.join(directory, "model_config.json"), "w", encoding="utf-8") as fh:
                json.dump({"provider": "openai", "model": "auto", "api_key": "test"}, fh)
            agent = AgentHubAgent(directory)
            self.assertEqual(agent.data_dir, directory)
            self.assertEqual(agent.provider.config.provider, "openai")

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

    def test_test_subprocess_environment_excludes_provider_secrets(self):
        os.environ["EVE_MODEL_API_KEY"] = "super-secret"
        try:
            self.assertNotIn("EVE_MODEL_API_KEY", _safe_env())
        finally:
            os.environ.pop("EVE_MODEL_API_KEY", None)

    def test_invalid_project_marks_task_failed(self):
        with tempfile.TemporaryDirectory() as directory:
            agent = AgentHubAgent(data_dir=directory)
            task = Task("agent_hub", "agent_hub", {"task": "x", "project": "../escape"})
            agent.assign_task(task)
            self.assertEqual(task.status, "failed")
            self.assertIn("invalid project", task.error.lower())

    def test_hermes_repeated_task_id_is_queued_once(self):
        with tempfile.TemporaryDirectory() as directory:
            hermes = HermesAgent(directory)
            hermes.set_task_queue(TaskQueue())
            client = hermes._app.test_client()
            payload = {"action": "agent_hub", "params": {"task_id": "a" * 32}}
            headers = {"Authorization": f"Bearer {hermes._token}"}
            first = client.post("/command", json=payload, headers=headers)
            second = client.post("/command", json=payload, headers=headers)
            self.assertEqual(first.status_code, 202)
            self.assertEqual(second.status_code, 202)
            self.assertEqual(second.get_json()["duplicate"], True)
            self.assertEqual(hermes.task_queue.qsize(), 1)

    def test_orchestrator_stops_registered_agents(self):
        class StopProbe:
            def __init__(self):
                self.stopped = False

            def set_task_queue(self, queue):
                self.queue = queue

            def stop(self):
                self.stopped = True

        probe = StopProbe()
        eve = EVE()
        eve.register_agent("probe", probe)
        eve.stop()
        self.assertTrue(probe.stopped)

    def test_orchestrator_accepts_task_driven_agent_without_run_loop(self):
        class TaskDrivenProbe:
            def set_task_queue(self, queue):
                self.queue = queue

        eve = EVE()
        eve.register_agent("task_driven", TaskDrivenProbe())
        thread = threading.Thread(target=eve.run, daemon=True)
        thread.start()
        time.sleep(0.05)
        self.assertTrue(thread.is_alive())
        eve.stop()
        thread.join(2)
        self.assertFalse(thread.is_alive())

    def test_hermes_server_stops_and_new_instance_restarts(self):
        def start(agent):
            thread = threading.Thread(target=agent.run, daemon=True)
            thread.start()
            deadline = time.monotonic() + 2
            while agent._server is None and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertIsNotNone(agent._server)
            return thread

        with tempfile.TemporaryDirectory() as directory:
            first = HermesAgent(directory)
            first_thread = start(first)
            first.stop()
            first_thread.join(2)
            self.assertFalse(first_thread.is_alive())

            second = HermesAgent(directory)
            second_thread = start(second)
            second.stop()
            second_thread.join(2)
            self.assertFalse(second_thread.is_alive())


if __name__ == "__main__":
    unittest.main()
