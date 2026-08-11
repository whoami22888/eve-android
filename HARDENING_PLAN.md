# EVE Android Hardening Plan

**Status**: Checkpoint at `c3f44118e38f7ab98d011bcb421275ff8b181101` on branch `freeze/hardening-checkpoint`

---

## 📋 Codebase Inventory

### Stack
- **Kotlin/Android**: 14 (API 34), AndroidX, Coroutines 1.7.3
- **Python**: 3.11 via Chaquopy 15.0.1
- **Key Libraries**: Flask 3.1.2, OkHttp 4.12.0, Retrofit 2.9.0, TensorFlow Lite 2.14.0
- **Storage**: SharedPreferences (PipelineStore), Python file I/O

### Architecture Summary
```
EveService (foreground)
  └─ Python Runtime (Chaquopy)
       ├─ EVE orchestrator (task_queue.py, orchestrator.py)
       ├─ HermesAgent (Flask HTTP gateway on 127.0.0.1:5001)
       ├─ AgentHubAgent (multi-stage pipeline, lifecycle-aware)
       ├─ HacxgentAgent (security/probing agent)
       └─ Android Computer adapter (JNI bridge to VirtualComputer)
  
  └─ VirtualComputer (singleton)
       ├─ VirtualAccessibilityService (gesture/screenshot)
       ├─ Input injection (click, typewrite, mouse)
       ├─ Script execution (Python, shell via subprocess)
       └─ HTTP client (okhttp3)

MainActivity (4-tab ViewPager2)
  ├─ DashboardFragment (status, logs)
  ├─ AgentHubFragment (pipeline control)
  ├─ AgentComputerFragment (live screen)
  ├─ MemoryEditorFragment (state)
  ├─ HistoryFragment (tasks)
  └─ [SetupFragment, ModelSettingsFragment]

Persistence
  └─ PipelineStore (SharedPreferences JSON, max 100 runs)
     └─ EveViewModel (observes EveEventBus, LiveData updates)
```

---

## ⚠️ Known Issues & Gaps

### 🔴 Critical
1. **Compilation Error**: `LocalAgentRuntimeBridge.kt:45` — Non-exhaustive `when` expression
   - **Fix**: Add `else -> {}` branch
   - **Impact**: Build fails, blocking all tests

2. **Missing Python Modules**: 
   - `agent_hub_agent.py` referenced in `EveService.kt` but not found in search results
   - `hacxgent_agent.py` referenced but not examined

3. **No Unit Tests**: Workflow defines pytest discovery but no tests exist yet

### 🟡 High Priority
4. **Task State Machine**: Incomplete, lacks explicit transitions:
   - States: pending → queued → running → paused → completed/failed/cancelled/interrupted
   - Missing: pause/resume logic, cancellation propagation, retry semantics

5. **Persistence Race Conditions**:
   - `PipelineStore.upsert()` is synchronized, but multiple threads call `load()` without holding lock
   - Live data updates in `EveViewModel` can race with persistence

6. **IPC Thread Safety**:
   - Python callbacks (`_bridge_log`, `_bridge_task_done`) swallow exceptions silently
   - No exception flow-back to Kotlin on bridge failures

7. **Worker/Cancellation**:
   - `ThreadPoolExecutor` for Agent Hub but no explicit cancellation tokens
   - Pause/resume events not integrated with worker threads

### 🟠 Medium Priority
8. **Secret Isolation**: No attestation that secrets don't leak in logs/output
9. **Network Security**: OkHttp TLS config not explicitly hardened (cert pinning missing)
10. **Skill Sandbox**: `SkillSandboxService` is TODO — no AIDL, no isolation
11. **Error Budgets**: No explicit failure mode testing (network down, OOM, ANR, crash recovery)
12. **Python-Kotlin Bridge**: Assumes `VirtualComputer.getInstance()` succeeds; no graceful fallback

---

## 🔧 Implementation Steps

### Step 1: Fix Compilation Error
**Files**: `LocalAgentRuntimeBridge.kt`
- Add `else -> {}` to exhaustive `when` expression
- Verify build passes with `./gradlew assembleDebug`

### Step 2: Inventory Complete Python Codebase
**Files**: Fetch all Python agent files
- `agent_hub_agent.py` (full)
- `hacxgent_agent.py`
- Any other adapters/utils

### Step 3: Define & Validate Task State Machine
**Deliverables**:
- Explicit enum `TaskState(PENDING, QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, INTERRUPTED)`
- State transition diagram (UML state machine)
- Kotlin + Python sides synchronized
- Unit tests for valid/invalid transitions

### Step 4: Harden Persistence
**Files**: `PipelineStore.kt`, `EveViewModel.kt`
- Replace SharedPreferences with SQLite (Room ORM) for transactional writes
- Add transaction guards on multi-step updates
- Migrate schema with proper versioning
- Unit tests: upsert race conditions, recovery on crash

### Step 5: Harden Workers & Cancellation
**Files**: `orchestrator.py`, `agent_hub_agent.py`, EVE

Python-side:
- Replace bare `threading.Event` with context-aware `CancellationToken`
- Propagate cancellation through worker stack
- Add timeout guards on all blocking calls

Kotlin-side:
- Tie coroutine/thread cancellation to `EveViewModel` lifecycle
- Add explicit cancellation tests (kill task mid-run, verify cleanup)

### Step 6: Harden Python-Android IPC
**Files**: `orchestrator.py`, `adapters/android_computer.py`, `EveKotlinBridge.kt`
- Add try-finally guards around all JNI calls
- Define structured exception types (bridged exceptions)
- Add circuit breaker for repeated bridge failures
- Unit tests: bridge down, exception flow-back, recovery

### Step 7: Security Audit
**Scope**:
- Secret scanning: grep for hardcoded API keys, tokens
- Path traversal: validate all file paths (workspace workspace boundaries)
- Command injection: sanitize all subprocess arguments (Python `execute()`, shell scripts)
- Network: verify TLS, add cert pinning for known endpoints
- Permissions: audit Android permissions (accessibility, device admin, overlay)
- Python imports: ensure no eval/exec of untrusted input

**Deliverable**: Security audit report + fixes

### Step 8: Failure-Injection Tests
**Tools**: Mockito, Chaos Monkey for Android
- Network failures (timeout, 500 error, connection refused)
- Storage failures (disk full, permission denied, corrupt JSON)
- Process death (simulate crash, verify recovery)
- OOM (low memory condition, verify graceful degradation)
- ANR (long-running task, verify cancellation works)

### Step 9: Lifecycle Tests
**Integration Tests**:
- Bind service → run pipeline → check state → unbind → rebind → check recovery
- Fragment recreation (config change, low memory kill, relaunch)
- Background/foreground transitions
- Permission denial (accessibility, etc.)
- Orientation changes mid-task

### Step 10: Release Build & Install Test
**Steps**:
1. Generate release signing key from env (KEYSTORE_PASS, KEY_PASS)
2. Build release APK: `./gradlew assembleRelease`
3. Install on emulator/device: `adb install eve-release.apk`
4. Run smoke tests (boot, bind, start task, capture log)
5. Check crash logs: `adb logcat -s EVE`

---

## 📊 Success Criteria

| Step | Artifact | Pass Criterion |
|------|----------|-----------------|
| 1 | Build log | `BUILD SUCCESSFUL` |
| 2 | Python inventory | All agent modules found & parse-able |
| 3 | State machine | UML diagram + tests, 100% coverage on transitions |
| 4 | SQLite schema | Room migration v1→v2, recovery tests pass |
| 5 | Cancellation | Task cancellation latency < 500ms, no thread leaks |
| 6 | IPC hardening | All exceptions logged, zero silent failures, circuit breaker trips < 5 failures |
| 7 | Security audit | Zero high-severity findings, all recommended fixes merged |
| 8 | Chaos tests | ≥10 failure modes tested, all recover gracefully |
| 9 | Lifecycle tests | ≥5 lifecycle scenarios pass, no crashes or data loss |
| 10 | Release build | APK size <50MB, boot time <5s, smoke tests pass |

---

## Timeline

- **Day 1**: Steps 1–2 (fix compilation, inventory Python)
- **Day 2**: Steps 3–4 (state machine, persistence hardening)
- **Day 3**: Steps 5–6 (workers, IPC)
- **Day 4**: Steps 7–8 (security, failure injection)
- **Day 5**: Steps 9–10 (lifecycle, release build)

---

## 🚀 Next Action

**Immediate**: Fix compilation error in `LocalAgentRuntimeBridge.kt` and apply it to `freeze/hardening-checkpoint` branch.

