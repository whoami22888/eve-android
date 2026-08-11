# Pipeline persistence redesign: SharedPreferences v1 to Room v2

## Goal and non-goals

`PipelineStore` currently serializes up to 100 mutable `Run` objects into one `SharedPreferences` JSON string. That format has no schema evolution, cannot atomically record state plus history, and currently permits task text/output/error to become backup-eligible plaintext. Version 2 replaces it with an internal Room database, a normalized event trail, optimistic concurrency, and retention rules.

This design persists **sanitized metadata**, not provider credentials, raw prompts, workspace snapshots, full model output, or command output. `ModelProviderStore` remains a separate Android-Keystore-backed credential store and must not share this database.

## SQLite schema

```sql
CREATE TABLE pipeline_runs (
  run_id TEXT NOT NULL PRIMARY KEY,
  logical_task_id TEXT NOT NULL,
  attempt_no INTEGER NOT NULL CHECK (attempt_no > 0),
  project_id TEXT NOT NULL,
  request_summary TEXT NOT NULL,                 -- redacted, bounded 512 chars
  state TEXT NOT NULL CHECK (state IN
    ('QUEUED','RUNNING','PAUSED','CANCELLING','CANCELLED','FAILED','COMPLETED')),
  stage TEXT NOT NULL,
  progress INTEGER NOT NULL CHECK (progress BETWEEN 0 AND 100),
  failure_code TEXT,
  failure_summary TEXT,                          -- redacted, bounded 2 KiB
  result_summary TEXT,                           -- redacted, bounded 4 KiB
  lease_owner TEXT,
  lease_expires_at INTEGER,
  version INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  completed_at INTEGER,
  retention_until INTEGER NOT NULL,
  UNIQUE(logical_task_id, attempt_no)
);

CREATE TABLE pipeline_events (
  event_id TEXT NOT NULL PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES pipeline_runs(run_id) ON DELETE CASCADE,
  sequence_no INTEGER NOT NULL,
  from_state TEXT,
  to_state TEXT NOT NULL,
  stage TEXT NOT NULL,
  progress INTEGER NOT NULL CHECK (progress BETWEEN 0 AND 100),
  event_type TEXT NOT NULL,
  message TEXT NOT NULL,                         -- redacted, bounded 2 KiB
  actor TEXT NOT NULL,                           -- USER, WORKER, RECOVERY, SYSTEM
  created_at INTEGER NOT NULL,
  UNIQUE(run_id, sequence_no)
);

CREATE TABLE pipeline_artifacts (
  artifact_id TEXT NOT NULL PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES pipeline_runs(run_id) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  relative_path TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  byte_count INTEGER NOT NULL CHECK (byte_count >= 0),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  UNIQUE(run_id, kind, relative_path)
);

CREATE TABLE persistence_meta (
  meta_key TEXT NOT NULL PRIMARY KEY,
  meta_value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
```

Indexes:

```sql
CREATE INDEX idx_runs_state_updated ON pipeline_runs(state, updated_at DESC);
CREATE INDEX idx_runs_logical_attempt ON pipeline_runs(logical_task_id, attempt_no DESC);
CREATE INDEX idx_runs_lease ON pipeline_runs(lease_expires_at) WHERE lease_expires_at IS NOT NULL;
CREATE INDEX idx_events_run_sequence ON pipeline_events(run_id, sequence_no ASC);
CREATE INDEX idx_artifacts_expiry ON pipeline_artifacts(expires_at);
```

`relative_path` is always normalized under an app-private artifact root; absolute paths, `..`, and symlinks are rejected before insertion. Foreign keys must be enabled in `RoomDatabase.Callback.onOpen`.

## Room model

```kotlin
@Entity(
    tableName = "pipeline_runs",
    indices = [Index(value = ["state", "updated_at"]), Index(value = ["logical_task_id", "attempt_no"], unique = true)]
)
data class PipelineRunEntity(
    @PrimaryKey @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "logical_task_id") val logicalTaskId: String,
    @ColumnInfo(name = "attempt_no") val attemptNo: Int,
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "request_summary") val requestSummary: String,
    val state: PipelineState,
    val stage: String,
    val progress: Int,
    @ColumnInfo(name = "failure_code") val failureCode: String?,
    @ColumnInfo(name = "failure_summary") val failureSummary: String?,
    @ColumnInfo(name = "result_summary") val resultSummary: String?,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String?,
    @ColumnInfo(name = "lease_expires_at") val leaseExpiresAt: Long?,
    val version: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "retention_until") val retentionUntil: Long
)

@Entity(
    tableName = "pipeline_events",
    foreignKeys = [ForeignKey(entity = PipelineRunEntity::class, parentColumns = ["run_id"], childColumns = ["run_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["run_id", "sequence_no"], unique = true)]
)
data class PipelineEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "sequence_no") val sequenceNo: Long,
    @ColumnInfo(name = "from_state") val fromState: PipelineState?,
    @ColumnInfo(name = "to_state") val toState: PipelineState,
    val stage: String,
    val progress: Int,
    @ColumnInfo(name = "event_type") val eventType: String,
    val message: String,
    val actor: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(
    tableName = "pipeline_artifacts",
    foreignKeys = [ForeignKey(entity = PipelineRunEntity::class, parentColumns = ["run_id"], childColumns = ["run_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["expires_at"])]
)
data class PipelineArtifactEntity(
    @PrimaryKey @ColumnInfo(name = "artifact_id") val artifactId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    val kind: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    val sha256: String,
    @ColumnInfo(name = "byte_count") val byteCount: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long
)

enum class PipelineState { QUEUED, RUNNING, PAUSED, CANCELLING, CANCELLED, FAILED, COMPLETED }
```

Use Room type converters for `PipelineState`; reject an unknown persisted value instead of coercing it to a success state.

## v1 → v2 migration

v1 is the `eve_pipeline_state/runs` preference JSON, not an existing Room database. The first v2 open performs a one-time bootstrap import:

1. Open the Room database with foreign keys enabled; obtain a database-wide migration mutex.
2. Check `persistence_meta['legacy_v1_import']`. If it is `complete`, do nothing.
3. Read the legacy JSON once. Parse each object defensively; skip and count malformed records rather than crashing startup.
4. Validate UUID/task ID, clamp progress to `0..100`, normalize stage, bound all display strings, redact secrets, and generate a stable `run_id` from legacy task ID plus attempt `1`.
5. Map `queued → QUEUED`, `running/paused/interrupted → FAILED` with `failure_code = PROCESS_RESTARTED`, `failed → FAILED`, `completed → COMPLETED`, and `cancelled → CANCELLED`. Unknown statuses map to `FAILED/LEGACY_INVALID_STATE`.
6. In **one Room transaction**, insert each run, insert a `LEGACY_IMPORT` event at sequence 1, and write `legacy_v1_import=complete` with the count and timestamp.
7. Only after the transaction commits, remove the `runs` preference with synchronous `commit()`. If removal fails, leave it; the metadata marker makes the next launch idempotent.
8. Retain migration telemetry without raw task content. Remove the v1 parser after two release cycles and confirmed adoption.

A normal Room `Migration(1, 2)` is still used for any pre-release Room schema used by testers. The SharedPreferences bootstrap is deliberately outside that API because it has no prior SQLite file.

## Transaction guards

| Operation | Atomic unit | Guard |
|---|---|---|
| Create run | run row + `QUEUED` event | Unique `(logical_task_id, attempt_no)`; validate project and request before transaction. |
| Claim worker | state `QUEUED→RUNNING` + lease + event | `UPDATE ... WHERE state='QUEUED' AND version=:expectedVersion`; exactly one row must change. |
| Progress/stage update | run version/progress/stage + event | Lease owner and unexpired lease must match; progress cannot regress. |
| Pause/resume | state update + event + lease mutation | Allowed transition table and expected version. |
| Cancel request | state `→CANCELLING` + event | Idempotent; terminal states reject it. Cleanup happens outside the DB transaction. |
| Finish cancellation | `CANCELLING→CANCELLED` + event + clear lease | Cleanup acknowledgement must be recorded. |
| Fail/complete | terminal run mutation + terminal event + artifact metadata | Verify the active lease and policy outcome; clear lease in the same transaction. |
| Retry/rerun | insert new attempt + `QUEUED` event | Source attempt must be terminal; never mutate it back to active. |
| Startup recovery | identify expired leases + `FAILED` event | Single recovery transaction per run; guarded by lease expiry/version. |
| Retention purge | expired artifact rows/files + old run metadata | Delete database rows transactionally; delete files before final metadata removal and retry failures. |

DAOs expose only repository methods that perform these guards. UI and bridge code must never write entities directly.
