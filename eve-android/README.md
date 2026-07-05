# EVE Agent — Android

EVE is an on-device autonomous agent orchestrator for Android.  
It uses the Accessibility Service to control the device screen, runs Python agent code via [Chaquopy](https://chaquo.com/chaquopy/), and exposes a local HTTP gateway (HermesAgent / Flask) for external tool integration.

---

## Architecture

```
MainActivity (4-tab ViewPager2)
│
├── DashboardFragment       — live status, task counts, agent health
├── AgentComputerFragment   — live screenshot + manual gesture overlay
├── MemoryEditorFragment    — read/write agent memory (key-value JSON)
└── HistoryFragment         — completed task log

EveService (foreground)
│
├── VirtualComputer (Kotlin singleton)
│   └── VirtualAccessibilityService  — gestures, screenshot (API 30+)
│
└── Python runtime (Chaquopy)
    └── eve.orchestrator.EVE
        ├── eve.hermes_agent.HermesAgent     — Flask HTTP on 127.0.0.1:5001
        └── eve.hacxgent_agent.HacxgentAgent — scan / audit / screenshot
            └── eve.adapters.android_computer — JNI bridge to VirtualComputer
```

---

## Build Requirements

| Tool | Version |
|------|---------|
| Android Studio | Hedgehog or newer |
| Android Gradle Plugin | 8.2.0 |
| Kotlin | 1.9.0 |
| Chaquopy | 14.0.2 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |
| NDK ABI | arm64-v8a |
| Python | 3.8 (Chaquopy default for 14.x) |

> **Chaquopy Maven repo** — Add `https://chaquo.com/maven` to your project-level `repositories` block (already present in `build.gradle`).

---

## Release Signing

Keystore credentials are read from environment variables at build time:

```
KEYSTORE_PASS=yourStorePass
KEY_PASS=yourKeyPass
```

Never commit actual credentials.  The bundled `eve.keystore` is a placeholder.

---

## Permissions Required at Runtime

| Permission | When prompted |
|------------|---------------|
| Accessibility Service | On first launch (redirects to Settings) |
| `SYSTEM_ALERT_WINDOW` | On first launch |
| `MANAGE_EXTERNAL_STORAGE` | On first launch (API 30+) |
| `RECORD_AUDIO` | Before using VoiceManager |
| Device Administrator | Via `WorkProfileManager` (optional) |

---

## HTTP API (HermesAgent)

Start the app, then forward the port from your dev machine:

```bash
adb forward tcp:5001 tcp:5001
```

Submit a task:

```bash
curl -X POST http://localhost:5001/command \
     -H "Content-Type: application/json" \
     -d '{"action": "screenshot", "params": {}}'
```

Health check:

```bash
curl http://localhost:5001/health
```

---

## Known Gaps / TODO

- [ ] `SkillSandboxService` — AIDL interface not yet defined; skill execution is a no-op
- [ ] Fragment ViewModels / LiveData wiring to `EveService`
- [ ] RecyclerView adapters for MemoryEditorFragment and HistoryFragment
- [ ] `VirtualComputer.captureScreen()` returns `null` until Accessibility Service is bound — add a retry/wait mechanism
- [ ] `spacy` model download on first run (add language-model download step to app startup)
- [ ] Work Profile (Managed Profile) provisioning flow — requires Device Owner setup via ADB or NFC
- [ ] Unit / instrumented tests

---

## File Layout

```
eve-android/
├── build.gradle                  top-level Gradle config
├── settings.gradle
├── gradle.properties
├── eve.keystore                  placeholder keystore (do not commit real creds)
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle              app module config + Chaquopy pip installs
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   └── system_prompts/   LLM system prompt text files
        ├── java/com/eve/agent/
        │   ├── MainActivity.kt
        │   ├── EveService.kt
        │   ├── VirtualComputer.kt
        │   ├── VirtualAccessibilityService.kt
        │   ├── WorkProfileManager.kt
        │   ├── EveDeviceAdminReceiver.kt
        │   ├── SkillSandboxService.kt
        │   ├── GitHubSkillManager.kt
        │   ├── Optimizer.kt
        │   ├── VoiceManager.kt
        │   ├── CrashReporter.kt
        │   ├── SectionPagerAdapter.kt
        │   ├── DashboardFragment.kt
        │   ├── AgentComputerFragment.kt
        │   ├── MemoryEditorFragment.kt
        │   └── HistoryFragment.kt
        ├── python/
        │   ├── requirements.txt
        │   └── eve/
        │       ├── __init__.py
        │       ├── orchestrator.py
        │       ├── task_queue.py
        │       ├── hermes_agent.py
        │       ├── hacxgent_agent.py
        │       └── adapters/
        │           ├── __init__.py
        │           └── android_computer.py
        └── res/
            ├── layout/
            │   ├── activity_main.xml
            │   ├── fragment_dashboard.xml
            │   ├── fragment_agent_computer.xml
            │   ├── fragment_memory_editor.xml
            │   └── fragment_history.xml
            ├── values/
            │   ├── strings.xml
            │   ├── colors.xml
            │   └── themes.xml
            └── xml/
                ├── accessibility_service_config.xml
                └── device_admin_receiver.xml
```
