# EVE Android hardening plan

**Baseline:** `freeze/hardening-checkpoint` is the frozen comparison point. The current implementation contains a foreground Kotlin service, Chaquopy/Python Agent Hub, a loopback Hermes gateway, Accessibility integration, SharedPreferences pipeline state, and Android-Keystore-backed model credential storage. This plan describes the dependency-ordered work required before production release; it does not claim those changes are already implemented.

## 1. Preserve and verify the frozen baseline
- Tag the exact approved commit, record source/artifact SHA-256 values, and protect the branch from force pushes.
- Capture current build, lint, test, dependency, and manifest reports as release evidence.
- **Acceptance:** a clean checkout of the tag reproduces the recorded debug build; deviations are reviewed as PRs.

## 2. Make the release supply chain fail closed
- Remove keystore fallbacks/placeholders; take keystore path/password/alias/password only from protected CI inputs.
- Add protected release environment approval, pinned CI actions, least-privilege workflow permissions, artifact checksums, and `apksigner` certificate verification.
- **Acceptance:** release build fails without secrets, succeeds only in the protected environment, and has the expected production certificate fingerprint.

## 3. Reduce and gate Android privilege
- Audit every manifest permission and exported component. Remove permissions/features without a demonstrated user-visible need, especially broad storage, contacts/calendar, device-admin, and cross-user claims.
- Add feature-specific disclosures and explicit runtime permission/Settings flows; handle denial without degradation into unsafe behavior.
- **Acceptance:** merged release manifest has a documented owner and purpose for every remaining privileged capability; Android 13/14 denial tests pass.

## 4. Secure the local service and bridge boundaries
- Bind Hermes only to loopback, rotate and require an ephemeral token, validate every command schema, and impose rate/size limits.
- Make Kotlin↔Python calls typed and allowlisted; reject unknown actions, malformed JSON, and untrusted intent extras.
- **Acceptance:** loopback/auth/schema negative tests pass and no external component can dispatch a privileged agent action.

## 5. Constrain Agent Hub execution and workspace access
- Enforce canonical app-private project roots, reject traversal/symlink escape, apply path/content quotas, and require user review before destructive writes.
- Keep test execution to argument-array allowlists with restricted environment, timeout, output cap, process-tree cleanup, and no shell interpretation.
- **Acceptance:** traversal, symlink, command-injection, timeout, and cancellation regression tests pass.

## 6. Implement the durable task model
- Replace JSON `SharedPreferences` pipeline storage with the Room v2 design in `PERSISTENCE_REDESIGN.md` and enforce `TASK_STATE_MACHINE.md` in a repository layer.
- Add leases, optimistic versions, idempotent events, startup recovery, terminal attempt immutability, bounded retention, and backup exclusions.
- **Acceptance:** migration, duplicate-event, queue/cancel/retry race, and process-death tests pass; no raw prompt/output/secret is persisted.

## 7. Establish a defensible privacy posture
- Default remote model providers to zero workspace context. Make any optional context transfer bounded, redacted, provider-specific, and approved by the user at request time.
- Publish data-flow, retention, consent, deletion, and provider disclosures; validate that telemetry/crash reports never bypass these rules.
- **Acceptance:** packet-level/provider-request tests show only approved data; privacy documentation and Play disclosures match behavior.

## 8. Harden observability, data lifecycle, and recovery
- Centralize redaction; cap logs, errors, events, artifacts, queue depth, snapshots, and disk use. Add secure deletion/expiry jobs and user data-clear controls.
- Make crash reporting opt-in or privacy-reviewed, remove secrets/paths/prompts from reports, and instrument only sanitized operational metrics.
- **Acceptance:** synthetic secret tests prove redaction in logs/events/crashes; quota, expiry, restore, uninstall/reinstall tests pass.

## 9. Build automated security and quality gates
- Add Kotlin unit tests, Room migration tests, Python tests, instrumentation tests, secret scanning, dependency/SBOM scanning, static manifest checks, and minified-release checks to CI.
- Treat lint, test, signature, and provenance failures as blocking. Add regression cases for every repaired vulnerability.
- **Acceptance:** CI independently produces a minified signed candidate and all gates are green from a clean runner.

## 10. Validate a staged release and retain rollback control
- Test signed release candidates on Android 13 and 14: fresh install, upgrade, uninstall/reinstall, permission denial/grant, foreground-service stop/restart, accessibility/screenshot, cancellation/retry, backup exclusion, provider configuration, and storage growth.
- Roll out to internal testers first, monitor sanitized failures, maintain a signed rollback artifact compatible with the production signing identity, then use staged production rollout.
- **Acceptance:** a named release owner records results, known exceptions, rollback steps, certificate fingerprint, and final go/no-go approval.

## Checkpoints

| Checkpoint | Required before proceeding |
|---|---|
| C1: privilege/security design | Steps 3–7 reviewed by a security owner; unresolved High findings block implementation release. |
| C2: persistence migration | Backup/restore and process-death migration tests pass before enabling Room v2 for users. |
| C3: release candidate | Protected signed build, all CI gates, and Android 13/14 manual checklist are complete. |
| C4: production rollout | Privacy disclosures, rollback artifact, monitoring owner, and staged rollout decision are recorded. |

## Open external inputs

- Authorized production signing keystore, passwords, alias, and release-environment access.
- Supported model-provider account/credential and approved data-processing terms for provider testing.
- Android 13 and Android 14 physical devices or configured emulators for final runtime validation.
- A named security/release owner authorized to approve exceptions and production rollout.
