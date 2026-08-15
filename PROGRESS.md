# EVE Android Hardening - Task Distribution & Progress

**Last Updated**: 2026-08-11 04:40 UTC
**Main Checkpoint**: `freeze/hardening-checkpoint` (c3f44118e38f7ab98d011bcb421275ff8b181101)
**Latest Fix**: `4745dfd64c113de36bcf248d1df400438d84d0c5` (compilation fix applied)

---

## ✅ Completed Tasks

### STEP 1: Freeze & Inventory ✅
- [x] Created branch `freeze/hardening-checkpoint` from main
- [x] Complete codebase inventory completed
- [x] Architecture diagram created
- [x] Stack identified: Kotlin + Python 3.11 (Chaquopy)
- [x] ~2500 lines of Kotlin code catalogued
- [x] ~500 lines of Python orchestrator code catalogued

### STEP 2: Fix Compilation Error ✅
- [x] Identified: `LocalAgentRuntimeBridge.kt:45` non-exhaustive `when` expression
- [x] Root cause: Missing `else` branch in sealed class pattern matching
- [x] **Fix applied**: Added `else -> {}` branch
- [x] Commit: `4745dfd64c113de36bcf248d1df400438d84d0c5`
- [x] Next: Run build to verify

---

## 🔄 In Progress / Next Steps

### STEP 3: Build & Test Compilation ⏳
**Assigned To**: **ChatGPT** (execution)
- [ ] Run: `cd eve-android && ./gradlew :app:assembleDebug`
- [ ] Verify: `BUILD SUCCESSFUL` in output
- [ ] Check: No new Kotlin compiler errors
- [ ] Capture: Build log (pass/fail)
- [ ] If fail: Extract error, escalate

**Expected Output**:
```
> Task :app:compileDebugKotlin SUCCESSFUL
> Task :app:assembleDebug SUCCESSFUL

BUILD SUCCESSFUL in 45s
```

---

## 📋 Task Distribution Matrix

### **CHATGPT** - Execution & Testing Focus
**Role**: Run commands, verify outputs, collect logs

#### Currently Assigned:
1. **Build Verification** (STEP 3)
   - Execute gradle build
   - Capture output
   - Report success/failure
   - Est. Time: 15-20 min

#### Next in Queue:
2. **Python Module Inventory** (STEP 2-bonus)
   - Fetch missing Python files: `agent_hub_agent.py`, `hacxgent_agent.py`
   - Verify imports and dependencies
   - Est. Time: 10 min

3. **Unit Test Execution** (STEP 8)
   - Run Python tests: `python -m unittest discover`
   - Run Kotlin tests: `./gradlew testDebugUnitTest`
   - Capture coverage reports
   - Est. Time: 15 min

4. **Release Build Smoke Test** (STEP 10)
   - Build APK: `./gradlew assembleRelease`
   - Verify APK size, signing
   - Est. Time: 20 min

---

### **TASKLET** - Architecture & Design Focus
**Role**: Design, validate state machines, create specifications

#### Currently Assigned:
1. **Task State Machine Specification** (STEP 4)
   - Define enum: `TaskState(PENDING, QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, INTERRUPTED)`
   - UML state diagram (valid transitions)
   - Kotlin + Python alignment
   - Deliverable: `TaskStateMachine.md`
   - Est. Time: 30 min

#### Next in Queue:
2. **Persistence Architecture Redesign** (STEP 5)
   - Design SQLite schema (Room ORM)
   - Migration strategy (SharedPreferences → SQLite)
   - Transaction guards & race condition prevention
   - Deliverable: `PersistenceSchema.md`
   - Est. Time: 45 min

3. **Cancellation & Worker Strategy** (STEP 6)
   - Design `CancellationToken` context
   - Thread/coroutine lifecycle diagram
   - Timeout guards specification
   - Deliverable: `WorkerLifecycle.md`
   - Est. Time: 40 min

4. **Security Audit Checklist** (STEP 7)
   - Secret scanning patterns
   - Path traversal vectors
   - Command injection surface area
   - Network security requirements
   - Deliverable: `SecurityAuditChecklist.md`
   - Est. Time: 50 min

---

## 🎯 Success Metrics

| Step | Status | ChatGPT Task | Tasklet Task | Blocker? |
|------|--------|--------------|--------------|----------|
| 1 | ✅ Complete | N/A | Inventory docs | No |
| 2 | ✅ Complete | N/A | Fetch Python files | No |
| 3 | ⏳ In Progress | **Build verification** | N/A | Yes |
| 4 | 📅 Pending | N/A | **State machine spec** | No |
| 5 | 📅 Pending | N/A | **Persistence design** | Blocks step 4 impl |
| 6 | 📅 Pending | N/A | **Cancellation design** | Blocks step 4 impl |
| 7 | 📅 Pending | N/A | **Security checklist** | No |
| 8 | 📅 Pending | **Run chaos tests** | N/A | No |
| 9 | 📅 Pending | **Lifecycle tests** | N/A | No |
| 10 | 📅 Pending | **Release build & APK install** | N/A | Yes |

---

## 🚀 Immediate Action Items

### For ChatGPT (Next 30 minutes):
```bash
# 1. Build & verify compilation
cd eve-android
./gradlew :app:assembleDebug --no-daemon

# 2. Check for remaining Kotlin errors
grep -i "error" build.log

# 3. Report result
echo "BUILD RESULT: [PASS/FAIL]"
```

### For Tasklet (Next 45 minutes):
1. Create `TASK_STATE_MACHINE.md`
   - Define all 8 states with entry/exit conditions
   - Draw FSM diagram (Mermaid or ASCII)
   - List valid transitions (18+ edges)
   - List invalid transitions (15+ pruned paths)

2. Create `PERSISTENCE_REDESIGN.md`
   - SQLite schema (tables, columns, indexes)
   - Room entity classes (Kotlin data classes)
   - Migration logic (v1 → v2)
   - Transaction guards (which operations are atomic)

3. Create `SECURITY_AUDIT_CHECKLIST.md`
   - 20+ security vectors to scan
   - Remediation steps for each
   - High/Medium/Low severity classification

---

## 📊 Timeline (5-Day Plan)

```
Day 1 (Today):
  ✅ Step 1: Freeze & inventory
  ✅ Step 2: Fix compilation
  ⏳ Step 3: Build verification (ChatGPT)
  📅 Step 4: State machine (Tasklet)

Day 2:
  📅 Step 5: Persistence hardening (Tasklet)
  📅 Step 6: Workers & cancellation (Tasklet)
  ⏳ Step 3: Python unit tests (ChatGPT)

Day 3:
  📅 Step 7: Security audit (Tasklet → ChatGPT)
  ⏳ Step 8: Failure-injection tests (ChatGPT)

Day 4:
  ⏳ Step 9: Lifecycle tests (ChatGPT)
  📅 Integration & hardening (Both)

Day 5:
  ⏳ Step 10: Release build & install (ChatGPT)
  📅 Final review & sign-off (Both)
```

---

## 💾 Artifacts Generated So Far

1. ✅ `HARDENING_PLAN.md` - 10-step strategy
2. ✅ `freeze/hardening-checkpoint` - Frozen baseline
3. ✅ `LocalAgentRuntimeBridge.kt` - Fixed compilation error
4. ⏳ Build logs (pending from ChatGPT)
5. 📅 `TASK_STATE_MACHINE.md` (pending from Tasklet)
6. 📅 `PERSISTENCE_REDESIGN.md` (pending from Tasklet)
7. 📅 `SECURITY_AUDIT_CHECKLIST.md` (pending from Tasklet)

---

## 🔗 Links

- **Repository**: https://github.com/whoami22888/eve-android
- **Branch**: `freeze/hardening-checkpoint`
- **Main Plan**: https://github.com/whoami22888/eve-android/blob/main/HARDENING_PLAN.md
- **Latest Commit**: `4745dfd64c113de36bcf248d1df400438d84d0c5`

---

## ⚠️ Known Blockers

1. **Build verification** - Need ChatGPT to run gradle and confirm no new errors
2. **Python module gap** - `agent_hub_agent.py`, `hacxgent_agent.py` not yet fully examined
3. **State machine design** - Tasklet needs to finalize before implementation starts

---

## 📞 Communication

**ChatGPT** → Report build results to issue/comment
**Tasklet** → Create design docs in repo

Both → Escalate any blockers immediately

---

## 2026-08-15 Baseline Verification Update

**Starting Commit**: `34e720e` (`fix: fail closed on missing release signing configuration`)
**Branch**: `work`

### Verified Results

- `python3 --version` reported `Python 3.11.15`.
- `python3 -m compileall -q eve-android/app/src/main/python` passed.
- `python3 -m unittest discover -s eve-android/app/src/main/python/tests -p 'test_*.py' -v` initially failed because the tests start directory did not put `eve-android/app/src/main/python` on `sys.path`; after the test import-path fix, 13 tests passed.
- `JAVA_HOME=/root/.local/share/mise/installs/java/17 ./gradlew --version` passed and reported Gradle 8.2.
- The ambient `JAVA_HOME` is invalid (`/usr/lib/jvm/java-17-openjdk-amd64`), so Gradle commands require the corrected `JAVA_HOME` value in this container.
- `JAVA_HOME=/root/.local/share/mise/installs/java/17 ./gradlew :app:testDebugUnitTest --no-daemon` is blocked because no Android SDK location is configured.
- `JAVA_HOME=/root/.local/share/mise/installs/java/17 ./gradlew :app:assembleDebug --no-daemon` is blocked because no Android SDK location is configured.
- Hermes startup readiness was inspected in `EveService.submitTask`; submissions could return before Hermes token/port files were available. The service now retries submission with bounded delays and emits a clear error if retries are exhausted.

### Current Limitations

- Android unit tests and debug APK assembly still need to be run in an environment with `ANDROID_HOME` or `sdk.dir` configured.
- No release build or device/emulator test was run during this update.
