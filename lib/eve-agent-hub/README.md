# Eve Agent Hub

The Agent Hub is Eve's multi-agent workspace layer. It keeps the agent runtime behind the normal Eve Android UI.

## Agents

- **Planner** — decomposes requests into a structured implementation plan.
- **Coder** — implements the plan by writing files inside the selected project workspace.
- **Reviewer** — reviews the resulting workspace and identifies regressions.
- **Tester** — proposes safe test commands and runs only an allowlisted set of common project test commands.
- **Security** — reviews the implementation for secrets, unsafe paths, injection issues, dependency risks and insecure network behavior.

## Runtime

The Android service starts the Python EVE orchestrator through Chaquopy. The Agent Hub calls an OpenAI-compatible `/v1/chat/completions` endpoint. This can be a hosted provider or a local provider reachable from the phone. No provider credential is stored in source control.

Provider configuration is read from the app-private `model_config.json` or these environment variables:

- `EVE_MODEL_BASE_URL`
- `EVE_MODEL_NAME`
- `EVE_MODEL_API_KEY`
- `EVE_MODEL_TIMEOUT`

See `app/src/main/python/model_config.example.json` for the shape of the configuration. A local Ollama-style endpoint can be used when the model server exposes an OpenAI-compatible API.

## Workspace

Projects live under the app-private EVE data directory at `projects/<project-name>`. The workspace rejects path traversal and caps snapshot/read sizes. The Coder can only write files under this root.

## Pipeline

`Planner -> Coder -> Reviewer -> Tester -> Security`

Each stage receives the prior stage's structured result plus the current workspace snapshot. The final task result contains the plan, changed files, review, test results and security assessment.

## Android UX

The Android app exposes the Agent Hub as a normal Eve screen. Terminals, Linux/container details and runtime logs remain secondary screens rather than the primary user experience.
