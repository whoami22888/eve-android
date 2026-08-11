# 🧠 TASKLET TASK CARD - State Machine Specification

**Task ID**: TASKLET-001
**Priority**: 🟠 HIGH (Blocks implementation in steps 5-6)
**Assigned To**: Tasklet
**Deadline**: Next 45 minutes
**Status**: 📅 PENDING (Waiting for go-ahead)

---

## 📋 Task: Define Task State Machine & Finite State Automaton

### Objective
Create a formal, implementation-ready specification of the task lifecycle state machine that will govern all EVE tasks (agent_hub, hacxgent, hermes). This must align Kotlin and Python sides.

---

## ✅ Acceptance Criteria

- [x] 8 states formally defined with entry/exit conditions
- [x] Finite State Diagram (Mermaid or ASCII) showing all valid transitions
- [x] All 18+ valid state transitions documented
- [x] 15+ invalid transitions explicitly pruned (with rationale)
- [x] Python `Task` class aligned with Kotlin state model
- [x] Transition guards (preconditions) documented
- [x] Error/recovery paths for each state
- [x] Kotlin enum `TaskState` ready for implementation
- [x] Unit test cases (at least 10 scenarios)

---

## 🎯 Deliverable: `TASK_STATE_MACHINE.md`

### Structure

```markdown
# Task State Machine Specification

## 1. State Definitions

### State: PENDING
**Description**: Task created but not yet enqueued
**Entry Condition**: Task constructor called
**Exit Condition**: Task enqueued for delegation
**Allowed Actions**: 
  - enqueue() → QUEUED
  - cancel() → CANCELLED

**Python**: task.status = "pending"
**Kotlin**: TaskState.PENDING

### State: QUEUED
**Description**: Task waiting in queue for agent delegation
**Entry Condition**: Orchestrator dequeues and validates
**Exit Condition**: Agent assigned OR no handler found
**Timeout**: 30 seconds (then → FAILED)
**Allowed Actions**:
  - delegate() → RUNNING
  - cancel() → CANCELLED
  - timeout() → FAILED

**Python**: task.status = "queued"
**Kotlin**: TaskState.QUEUED

[... repeat for all 8 states ...]

## 2. State Transition Diagram

\`\`\`mermaid
graph TD
    PENDING -->|enqueue| QUEUED
    QUEUED -->|delegate| RUNNING
    RUNNING -->|pause| PAUSED
    PAUSED -->|resume| RUNNING
    PAUSED -->|cancel| CANCELLED
    RUNNING -->|complete| COMPLETED
    RUNNING -->|fail| FAILED
    RUNNING -->|cancel| CANCELLED
    CANCELLED -->|mark_done| INTERRUPTED
    FAILED -->|no_retry| INTERRUPTED
    COMPLETED --> INTERRUPTED
\`\`\`

## 3. Valid State Transitions (18+)

| From | To | Trigger | Guard | Handler |
|------|-------|---------|-------|---------|
| PENDING | QUEUED | enqueue() | task.id not null | EVE.delegate() |
| PENDING | CANCELLED | cancel() | none | EveViewModel.cancel() |
| QUEUED | RUNNING | delegate() | agent.can_handle() | agent.assign_task() |
| QUEUED | FAILED | timeout | elapsed > 30s | EVE._complete() |
| QUEUED | CANCELLED | cancel() | none | EveViewModel.cancel() |
| RUNNING | PAUSED | pause() | agent supports pause | agent.pause() |
| RUNNING | RESUMED | resume() | from PAUSED | agent.resume() |
| RUNNING | COMPLETED | finish() | result not null | EVE._complete() |
| RUNNING | FAILED | error() | exception caught | task.status="failed" |
| RUNNING | CANCELLED | cancel() | thread cancellable | CoroutineScope.cancel() |
| PAUSED | RUNNING | resume() | pause_event.is_set() | agent.resume() |
| PAUSED | CANCELLED | cancel() | none | CoroutineScope.cancel() |
| CANCELLED | INTERRUPTED | mark_done | no retry queued | EveViewModel.persist() |
| FAILED | INTERRUPTED | mark_done | no retry queued | EveViewModel.persist() |
| COMPLETED | INTERRUPTED | mark_done | persist for history | EveViewModel.persist() |
| ... | ... | ... | ... | ... |

## 4. Invalid Transitions (Explicitly Pruned)

| From | To | Why Blocked | Consequence |
|------|-------|------------|------------|
| PENDING | RUNNING | Skip queuing violates queue discipline | Exception: InvalidStateException |
| QUEUED | PAUSED | Can't pause before running | Exception: InvalidStateException |
| QUEUED | COMPLETED | Can't complete without running | Exception: InvalidStateException |
| PAUSED | PAUSED | Already paused | Idempotent: no-op (log warning) |
| RUNNING | QUEUED | Can't re-queue | Exception: InvalidStateException |
| COMPLETED | RUNNING | Can't restart | Exception: InvalidStateException |
| FAILED | RUNNING | Can't restart (must use RETRY) | Exception: InvalidStateException |
| CANCELLED | RUNNING | Can't uncanccel | Exception: InvalidStateException |
| INTERRUPTED | * | Terminal state, no exit | Exception: InvalidStateException |

## 5. Transition Guards (Preconditions)

### Guard: task.id not null
\`\`\`python
assert task.id, "Task must have a valid ID before enqueueing"
\`\`\`

### Guard: agent.can_handle(task)
\`\`\`python
if not any(agent.can_handle(task) for agent in orchestrator.agents.values()):
    task.status = "failed"
    task.error = "No agent can handle action: " + task.action
    return
\`\`\`

### Guard: elapsed time for QUEUED timeout
\`\`\`python
import time
enqueue_time = time.time()
# In orchestrator loop:
if task.status == "queued" and (time.time() - enqueue_time) > 30:
    task.status = "failed"
    task.error = "Queued timeout after 30 seconds"
\`\`\`

## 6. Error Recovery Paths

### Scenario: Task crashes mid-RUNNING
**Path**: RUNNING → FAILED → INTERRUPTED
**Recovery**:
1. Exception caught in agent.assign_task()
2. task.status = "failed", task.error = str(exception)
3. Orchestrator emits TaskCompleted event
4. EveViewModel marks as INTERRUPTED for history
5. User can retry via AgentHubFragment.RETRY button

### Scenario: Process death during PAUSED state
**Path**: PAUSED → INTERRUPTED (via recovery logic)
**Recovery**:
1. On restart, EveViewModel loads from PipelineStore
2. Detects status="paused" + stale timestamp
3. Marks as INTERRUPTED: "Runtime restarted; retry available"
4. User can manually retry

## 7. Kotlin Enum Implementation

\`\`\`kotlin
enum class TaskState {
    PENDING,
    QUEUED,
    RUNNING,
    PAUSED,
    CANCELLED,
    COMPLETED,
    FAILED,
    INTERRUPTED;
    
    fun canTransitionTo(target: TaskState): Boolean = when (this) {
        PENDING -> target in setOf(QUEUED, CANCELLED)
        QUEUED -> target in setOf(RUNNING, FAILED, CANCELLED)
        RUNNING -> target in setOf(PAUSED, COMPLETED, FAILED, CANCELLED)
        PAUSED -> target in setOf(RUNNING, CANCELLED)
        CANCELLED -> target == INTERRUPTED
        FAILED -> target == INTERRUPTED
        COMPLETED -> target == INTERRUPTED
        INTERRUPTED -> false  // Terminal
    }
}
\`\`\`

## 8. Python Alignment

**Current Python Task class**:
\`\`\`python
class Task:
    def __init__(self, agent, action, params, task_id):
        self.status = "pending"  # Maps to TaskState.PENDING
\`\`\`

**Updated to match state machine**:
\`\`\`python
from enum import Enum

class TaskState(Enum):
    PENDING = "pending"
    QUEUED = "queued"
    RUNNING = "running"
    PAUSED = "paused"
    CANCELLED = "cancelled"
    COMPLETED = "completed"
    FAILED = "failed"
    INTERRUPTED = "interrupted"

class Task:
    def __init__(self, agent, action, params, task_id):
        self.state = TaskState.PENDING
        self.status = self.state.value  # For JSON serialization
    
    def transition_to(self, target_state: TaskState) -> bool:
        if not self.can_transition_to(target_state):
            raise InvalidStateException(f"Cannot go from {self.state} to {target_state}")
        self.state = target_state
        self.status = target_state.value
        return True
    
    def can_transition_to(self, target: TaskState) -> bool:
        # Mirror of Kotlin logic
        ...
\`\`\`

## 9. Unit Test Scenarios (10+)

### Test 1: Valid Transition PENDING → QUEUED
\`\`\`python
def test_pending_to_queued():
    task = Task("hermes", "status", {}, "test-001")
    assert task.state == TaskState.PENDING
    task.transition_to(TaskState.QUEUED)
    assert task.state == TaskState.QUEUED
\`\`\`

### Test 2: Invalid Transition PENDING → RUNNING
\`\`\`python
def test_pending_to_running_fails():
    task = Task("hermes", "status", {}, "test-002")
    with pytest.raises(InvalidStateException):
        task.transition_to(TaskState.RUNNING)
\`\`\`

### Test 3: Pause and Resume
\`\`\`python
def test_pause_resume():
    task = Task("agent_hub", "agent_hub", {}, "test-003")
    task.transition_to(TaskState.QUEUED)
    task.transition_to(TaskState.RUNNING)
    task.transition_to(TaskState.PAUSED)
    assert task.state == TaskState.PAUSED
    task.transition_to(TaskState.RUNNING)
    assert task.state == TaskState.RUNNING
\`\`\`

### Test 4: Terminal State (INTERRUPTED is immutable)
\`\`\`python
def test_interrupted_terminal():
    task = Task("hermes", "help", {}, "test-004")
    task.state = TaskState.INTERRUPTED  # Force terminal
    with pytest.raises(InvalidStateException):
        task.transition_to(TaskState.RUNNING)
\`\`\`

### Test 5: Cancellation from any running state
\`\`\`python
def test_cancel_from_any_state():
    for start_state in [TaskState.QUEUED, TaskState.RUNNING, TaskState.PAUSED]:
        task = Task("hermes", "status", {}, f"test-{start_state}")
        task.state = start_state
        task.transition_to(TaskState.CANCELLED)
        assert task.state == TaskState.CANCELLED
\`\`\`

[... add 5+ more scenarios ...]

## 10. Implementation Roadmap

**Phase 1**: Enum + Kotlin validation (1 hour)
**Phase 2**: Python alignment (30 min)
**Phase 3**: Integration into EVE orchestrator (2 hours)
**Phase 4**: UI updates (AgentHubFragment reflects state) (1 hour)
**Phase 5**: Tests + edge cases (1.5 hours)

**Total**: ~6 hours of implementation work after spec approval
```

---

## 📊 Visual Reference

### ASCII State Diagram (Alternative to Mermaid)
```
          [PENDING]
              |
              | enqueue()
              v
          [QUEUED] <------- timeout: 30s → [FAILED]
              |
              | delegate()
              v
          [RUNNING] -----> [COMPLETED]
          /   |   \           |
       pause  |   error()      |
        |     v               |
        |   [FAILED]          |
        |     |               |
        v     v               v
      [PAUSED] --resume--> [INTERRUPTED] <-- [CANCELLED]
        |
        | cancel()
        v
      [CANCELLED]
            |
            v
      [INTERRUPTED]
```

---

## 🎯 Handoff to Implementation

Once this spec is approved:

1. **Tasklet** hands spec to **ChatGPT**
2. **ChatGPT** creates Kotlin enum + Python class
3. Both implement `canTransitionTo()` / `can_transition_to()`
4. Tests are written against the state machine
5. Integrator wires state machine into EVE orchestrator

---

## 📞 Review & Feedback

- Does this cover all 10 steps of the hardening plan?
- Any states or transitions missing?
- Is the guard logic clear?
- Can we simplify or consolidate states?

**Please review and provide feedback before implementation begins.**

---

## 🔗 Related Tasks

- **CHATGPT-001**: Build Verification ✅ 
- **TASKLET-002**: Persistence Redesign (depends on this)
- **TASKLET-003**: Cancellation Design (depends on this)

---

## 📝 Success Criteria Checklist

- [ ] Spec written in Markdown
- [ ] All 8 states documented with entry/exit conditions
- [ ] FSM diagram included (Mermaid + ASCII)
- [ ] 18+ valid transitions listed in table
- [ ] 15+ invalid transitions pruned with rationale
- [ ] Kotlin enum code ready to copy-paste
- [ ] Python class code ready to copy-paste
- [ ] 10+ unit test scenarios included
- [ ] Error recovery paths documented
- [ ] No ambiguous or "TBD" sections

