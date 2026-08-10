# Eve Agent Hub

The Agent Hub is Eve's multi-agent workspace layer. It is designed to keep the agent runtime behind the normal Eve Android UI.

## Agents

- **Planner** — decomposes requests and coordinates work.
- **Coder** — implements changes in the selected workspace.
- **Reviewer** — reviews changes and identifies regressions.
- **Tester** — runs available checks/builds and reports failures.
- **Security** — reviews dependencies, permissions, secrets and unsafe patterns.

## Architecture

`Eve UI -> Agent Hub -> runtime adapter -> project workspace`

The runtime is intentionally abstracted behind `AgentRuntime`, so Eve can later connect it to a local Android runtime, a remote worker, or a model provider without changing the dashboard/orchestration API.

## Android direction

The Android app should expose the dashboard as a normal Eve screen. Terminals, Linux/container details and runtime logs remain secondary screens rather than the primary user experience.
