# PR #1 — Comprehensive Code Review

**Verdict: REQUEST CHANGES**  
**Scope:** All 28 changed files in `pr-1`, compared with the merge base of `origin/main` (`493a19d`).

## Critical errors

### 1. Agent Hub is constructed with the data directory as its logger

- **Files:** `eve-android/app/src/main/java/com/eve/agent/EveService.kt:46`; `eve-android/app/src/main/python/eve/agent_hub_agent.py:48–60, 72`
- Kotlin calls `AgentHubAgent(filesDir.absolutePath)` with one positional argument, but Python declares `__init__(self, log=None, data_dir=None)`. The path therefore binds to `log`, not `data_dir`.
- On a fresh/unconfigured install, `_refresh_provider()` calls `self.log(...)`, causing `TypeError: 'str' object is not callable` during `EveService.onCreate()`. With a configured provider, the first pipeline stage fails at the next logging call.
- **Fix:** Pass `data_dir` explicitly through Chaquopy (keyword argument), or change the constructor so `data_dir` is first. Add a regression test using the same positional call as Kotlin and guard service initialization so one agent cannot crash the service.

### 2. Provider test suite fails at the PR head

- **File:** `eve-android/app/src/main/python/tests/test_agent_hub_providers.py:97`
- The test patches `eve.agent_hub_agent.OpenAICompatibleProvider.complete`, but that class is not imported into `eve.agent_hub_agent`. `unittest.mock.patch` raises `AttributeError`.
- The exact workflow command currently produces **9 passed, 1 error, exit code 1**.
- **Fix:** Patch `eve.model_provider.OpenAICompatibleProvider.complete`, or patch the newly built provider instance. Also test the real Kotlin-to-Python constructor call.

### 3. Model API keys are exposed to model-generated test code

- **Files:** `eve-android/app/src/main/python/eve/agent_hub_agent.py:84–96`; `EveService.kt:54–60`
- `subprocess.run` inherits the Android Python process environment, including `EVE_MODEL_API_KEY`. The coder stage writes model-generated files, after which the tester stage executes allowlisted project commands. Tests can read the key, print it into captured task output, or send it over the network.
- **Fix:** Supply a minimal sanitized `env` to each subprocess, explicitly remove all secret/provider variables, and redact known secrets from captured output.

## Important errors

### 4. The bridge becomes permanently unusable after its view is recreated

- **Files:** `LocalAgentRuntimeBridge.kt:29, 42–58`; `AgentHubFragment.kt:33–35, 145–159`
- `onDestroyView()` cancels the bridge’s one permanent coroutine scope. The same bridge instance is retained by the Fragment and reused after view recreation. Future `scope.launch` calls are immediately cancelled, so **RUN AGENTS** silently does nothing after switching tabs or recreating the view.
- **Fix:** Tie work to `viewLifecycleOwner.lifecycleScope`, recreate the bridge scope on attachment, or cancel it only in `Fragment.onDestroy()`.

### 5. Saving settings does not update the running provider

- **Files:** `ModelSettingsFragment.kt:96–110`; `EveService.kt:29–61, 82–95`
- **Save AI Model** only writes SharedPreferences. Python environment variables are refreshed only at service startup or when **Test Connection** is pressed. Agent Hub continues using stale provider/model/key settings until a test or service restart.
- **Fix:** Refresh the service’s Python provider environment immediately after a successful save.

### 6. Pause, progress, completion, and submission state are not actually connected

- **Files:** `LocalAgentRuntimeBridge.kt:42–54`; `AgentHubFragment.kt:91–113, 145–154`; `EveService.kt:68–79`
- `stopPipeline()` only changes UI text; it does not pause or cancel any task.
- Progress is set to 25% whenever `running=true` and never follows pipeline stages or completion.
- Agent Hub does not collect `EveEventBus` task/log events, so it never displays the real result or failure.
- `runPipeline()` announces “Pipeline submitted” immediately even if the HTTP request later fails or returns a non-2xx response. `EveService` also logs every HTTP response as submitted regardless of status.
- **Fix:** Add task IDs and real cancellation semantics, drive state from `EveEventBus`, update progress from stage events, and only mark submission successful after a 2xx response.

### 7. Invalid project names leave task status stuck at `running`

- **File:** `eve-android/app/src/main/python/eve/agent_hub_agent.py:99–109, 140–143`
- `ProjectWorkspace(...)` is created before the guarded `try`. A bad `project` parameter raises `WorkspaceError` after status is set to `running`, bypassing the agent’s failure-state update.
- **Fix:** Move workspace construction inside the `try` and centralize task finalization.

### 8. One Agent Hub task blocks every other EVE task

- **Files:** `eve-android/app/src/main/python/eve/agent_hub_agent.py:99–143`; `eve-android/app/src/main/python/eve/orchestrator.py:101–108, 121–134`
- The single orchestrator thread calls `assign_task` synchronously. Agent Hub performs five sequential model calls—each allowing up to 600 seconds—plus three 120-second test commands. During that time, status, Hermes, and Hacxgent tasks cannot be processed.
- **Fix:** Dispatch long-running Agent Hub work to a dedicated worker or executor while preserving bounded concurrency, cancellation, and result reporting.

### 9. Android Keystore failures crash the settings screen

- **Files:** `ModelProviderStore.kt:35–43, 61–82`; `ModelSettingsFragment.kt:96–110`
- Encryption/key-generation exceptions propagate from the Save click handler on the main thread. Decryption handles failures, but encryption does not.
- **Fix:** Return a typed save result or throw into a caught boundary; show a user-visible error without partially updating preferences.

## Minor issue

### 10. Local Android builds may use an incompatible Python version

- **File:** `eve-android/app/build.gradle:29`
- The runtime is declared as Python 3.11, but the fallback build executable is generic `python3`. A machine where that resolves to another version can fail outside CI.
- **Fix:** Default to `python3.11` or fail early with a clear version check.

## What is done well

- Workspace project/file traversal checks reject parent and absolute-path escapes.
- Provider IDs, URLs, models, and timeout values are normalized and bounded.
- Provider adapters and automatic stage routing are separated cleanly.
- TypeScript Agent Hub sources pass strict type checking.
- Credentials are encrypted at rest using Android Keystore-backed AES-GCM.

## Verification story

- Reviewed every changed Kotlin, Python, TypeScript/TSX, XML, Gradle, workflow, documentation, and configuration block, plus surrounding orchestrator, queue, event-bus, service, and pager callers.
- Ran Python compilation and the exact provider-test discovery command: **failed, 9 passed / 1 error**.
- Reproduced the `AgentHubAgent(single_positional_path)` constructor failure.
- Checked changed Python files statically and exercised workspace traversal rejection.
- Strict TypeScript checking passed.
- The Android workflow had passed on earlier revisions, but the current Python test added after those runs fails as described above.

## Changed files assessed

`.github/workflows/eve-agent-hub.yml`; `.env.example`; Android Gradle and manifest files; `AgentHubFragment.kt`; `EveService.kt`; `LocalAgentRuntimeBridge.kt`; `MainActivity.kt`; provider preset/store/settings Kotlin files; `agent_hub_agent.py`; `hermes_agent.py`; `model_provider.py`; `workspace.py`; Python config, requirements, and tests; model settings XML; and all files under `lib/eve-agent-hub/`.
