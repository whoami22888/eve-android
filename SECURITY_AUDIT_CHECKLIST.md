# Security audit checklist

**Use:** scan this list before every release and again after changes to permissions, the local HTTP gateway, Agent Hub, model providers, persistence, or build tooling. Severity means impact if the finding is exploitable in the shipped configuration.

| # | Vector to scan | Severity | Required remediation / release evidence |
|---:|---|---|---|
| 1 | Release keystore committed, placeholder fallback, or default passwords in Gradle | High | Require externally supplied signing inputs; fail closed when absent; scan Git history and verify the release certificate fingerprint. |
| 2 | Secrets in source, assets, manifests, test fixtures, logs, crash reports, or APK | High | Run secret scanning; use Android Keystore for device secrets; redact before logging/persisting; rotate any exposed credential. |
| 3 | Backup of pipeline history, tokens, or private artifacts | High | Disable backup for sensitive stores or supply data-extraction/backup exclusion rules; verify restore on Android 13/14. |
| 4 | Model API key plaintext, weak encryption, or key loss handling | High | Keep only ciphertext in private storage using Android Keystore AES-GCM; never log it; clear safely if decryption fails. |
| 5 | Remote model receives workspace, prompts, source, or secrets without explicit consent | High | Default remote providers to no workspace context; show a per-request consent summary and use bounded, redacted allowlisted context only. |
| 6 | Path traversal/project selector such as `..`, `.`, absolute paths, or symlinks | High | Canonicalize and enforce app-private workspace root on every read/write/snapshot; test traversal and symlink escapes. |
| 7 | Model-generated file write outside policy | High | Enforce path allowlist, size limits, UTF-8 validation, atomic writes, and review/approval before destructive replacement. |
| 8 | Shell command injection in generated test commands | High | Use a fixed command allowlist and argument arrays; no shell; bounded timeout/output/environment; test malicious command strings. |
| 9 | Local Hermes HTTP API exposed beyond loopback or missing authentication | High | Bind only to `127.0.0.1`; require an ephemeral high-entropy token for every non-health endpoint; reject LAN access in tests. |
| 10 | Token leakage via files, logs, intents, notifications, or debug builds | High | Store token app-private with restrictive mode, redact logs, avoid intents/notifications, rotate on service start, and disable debug diagnostics in release. |
| 11 | Overbroad accessibility, overlay, device-admin, or storage permissions | High | Remove unused permissions; disclose purpose; gate each privileged action behind explicit user approval; validate Play policy and runtime denial paths. |
| 12 | Accessibility service performs unsupported or silent actions | High | Maintain an explicit capability allowlist, visible action log, confirmation for sensitive actions, and Android-version tests. |
| 13 | Screenshot/media projection capture or storage leakage | High | Request user consent per capture/session, keep images app-private, encrypt/delete on expiry, prevent sharing by default, and test API-level guards. |
| 14 | Exported activity/service/receiver/provider attack surface | High | Make components non-exported unless required; protect required exported components with correct permissions and explicit intent validation. |
| 15 | Intent extras, deep links, or binder calls treated as trusted | High | Validate caller, action, type, length, and values at every boundary; never let an external intent dispatch an agent command directly. |
| 16 | TLS downgrade, cleartext traffic, weak certificates, or provider endpoint SSRF | High | Deny cleartext in network security config; require HTTPS except explicit loopback; validate provider URL scheme/host and block private/metadata targets. |
| 17 | Dependency, Gradle plugin, Python package, or native-library vulnerability | High | Pin versions and hashes where supported; generate SBOM; run dependency vulnerability scans; upgrade/mitigate before release. |
| 18 | R8/ProGuard removes security controls or release remains debuggable | High | Build minified release; inspect merged manifest; confirm `debuggable=false`, no test endpoints, and mapping file is protected. |
| 19 | Unsafe WebView, JavaScript bridge, file access, or custom URL loading | High | If introduced, disable file/content access by default, allowlist origins, avoid JS bridges, and validate redirects. |
| 20 | SQL injection or unbounded query/API pagination in new Room code | Medium | Use Room bound parameters only, add indexes/limits, validate sort keys, and fuzz search/filter inputs. |
| 21 | Race between task control, process death, and persistence | High | Enforce state-machine/lease/version transaction guards; test duplicate events, cancellation races, and restart recovery. |
| 22 | Sensitive output/error retained indefinitely or restored after uninstall/backup | Medium | Redact and bound summaries, apply retention purge, exclude backups, and test uninstall/reinstall and cloud restore. |
| 23 | Logs/crash reports expose prompt, workspace, headers, paths, or credentials | High | Centralize redaction, cap sizes, disable verbose release logs, and conduct synthetic-secret tests. |
| 24 | Biometric/device-lock protection missing for credential editing or privileged actions | Medium | Require current authentication for key reveal/edit and sensitive controls; test lock-screen and enrollment-change behavior. |
| 25 | Notification permission and foreground-service behavior on Android 13/14 | Medium | Request `POST_NOTIFICATIONS` appropriately; do not claim background execution when denied; validate process restart and user-stop semantics. |
| 26 | Denial of service via huge prompt, snapshot, model output, queue, or subprocess output | Medium | Enforce per-field, total, queue, time, concurrency, and disk quotas; surface deterministic `FAILED/RESOURCE_LIMIT`. |
| 27 | Python/Kotlin bridge accepts arbitrary method/action names or deserializes untrusted data | High | Maintain typed, allowlisted bridge methods and action schemas; reject unknown fields; cover malformed input tests. |
| 28 | Work-profile/device-admin assumptions bypass Android platform policy | Medium | Treat provisioning as optional, verify caller/device-owner status, remove privileged paths that cannot work for normal apps. |
| 29 | GitHub Actions/workflow injection, unpinned actions, or secret echo | High | Pin actions by commit SHA, quote untrusted inputs, use least privileges, mask secrets, and restrict release environments. |
| 30 | Signing, artifact provenance, and update compatibility not verified | High | Generate signed release in protected CI, run `apksigner verify --print-certs`, attest provenance, archive checksums, and test upgrade from production. |
| 31 | Privacy policy, data disclosure, and third-party provider terms inconsistent with behavior | Medium | Document data flows/retention/remote processing; obtain consent; update Play Data safety and provider disclosures before shipment. |
| 32 | Incomplete security regression suite | Medium | Add unit/instrumented tests for state guards, traversal, redaction, gateway auth, permission denial, backup exclusion, and release manifest. |

## Scan cadence and sign-off

- **Every PR:** vectors 1, 2, 6–10, 14–21, 23, 26–30; automated static/dependency/secret checks where possible.
- **Every release candidate:** all vectors, minified signed build, Android 13 and 14 manual validation, and a recorded security owner sign-off.
- **On incident or credential rotation:** vectors 1–5, 9–10, 16, 23, 29–30; preserve sanitized evidence and revoke exposed credentials.

A High finding is a release blocker unless a named security owner documents a time-bounded exception and compensating control. Medium findings require a tracked remediation before general availability. Low findings below are hygiene items, not permission to ignore systemic risks.

## Low-severity hygiene checks

| Vector | Severity | Remediation |
|---|---|---|
| Error messages reveal internal class names or stack traces to the UI | Low | Replace with stable user messages; retain sanitized diagnostics only in protected logs. |
| Security-relevant timestamps use inconsistent clocks | Low | Store UTC epoch milliseconds and use monotonic timers for timeouts. |
| Stale inactive artifacts accumulate below quota | Low | Add scheduled purge telemetry and a user-visible clear-data control. |
| Security docs diverge from the app | Low | Update this checklist and threat model with each privileged capability change. |
