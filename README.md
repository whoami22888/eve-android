EVE Android – Autonomous Agent Orchestrator

EVE Android is a full‑featured APK that turns your Android device into an autonomous agent ecosystem. It integrates the EVE orchestrator, Hermes command interface, Hacxgent helper, and a native Virtual Computer (based on autonomous‑computer) into one seamless application. Agents can control your phone’s screen, launch apps, access files, interact with cloud services, and even download new skills from GitHub – all under human supervision.

---

🧠 Overview

EVE Android brings desktop‑grade agent automation to your mobile device. The system is built around:

· EVE – the central orchestrator that breaks down user requests, delegates tasks to the best agent, and merges results.
· Hermes – a REST/WebSocket interface that accepts external commands and forwards them to EVE.
· Hacxgent – a security‑oriented helper agent (port scanning, phishing simulation, etc.).
· Virtual Computer – a Kotlin‑based service that simulates a computer on Android, providing screen capture, mouse/keyboard injection, app control, file I/O, network access, and multi‑language script execution (Python, Shell, etc.).

All agents run inside an Android Work Profile sandbox for isolation, and every risky action requires human authorisation via a pop‑up dialog.

---

🚀 Key Features

· Automouse & GUI Automation – move mouse, click, drag, scroll, type, and even pinch/zoom using the Accessibility Service.
· Screen Capture – take screenshots (Android 9+ via takeScreenshot(); fallback using MediaProjection).
· App Control – list installed apps, launch any app, force‑stop, retrieve app info.
· File System Access – read/write files in internal/external storage (with runtime permissions).
· Cloud Integration – upload, download, delete, and list files over HTTP (configurable for any cloud provider).
· Multi‑Language Script Execution – run Python, Shell (Bash), and (stub for) Java/Kotlin scripts on‑device.
· Skill Marketplace via GitHub OAuth – log in with GitHub, browse, download, and install new agent skills from public repositories.
· Human Authorisation – sensitive tasks (file deletions, cloud uploads, system changes) trigger a user approval dialog (with optional biometric confirmation).
· Persistent Memory & Learning – EVE remembers past tasks, agent success rates, and user‑provided facts. It adapts delegation choices over time.
· Contextual Conversation – natural language interface with clarification questions and step‑by‑step thought process explanation.
· Voice Interaction – selectable TTS voices for spoken feedback.
· Performance Optimisation – automatic cache clearing, self‑error reporting, and resource throttling to minimise battery/CPU impact.

---

🏗️ Architecture

```
Android Work Profile (Sandbox)
│
├── EVE (Orchestrator)
│   ├── Task Queue & Delegation Logic
│   ├── Memory (SQLite – user facts + agent stats)
│   └── Clarification Engine (asks user until request is clear)
│
├── Agents
│   ├── Hermes (REST/WebSocket API – accepts external commands)
│   ├── Hacxgent (helper – scanning, probing)
│   └── Dynamic Skills (downloaded from GitHub)
│
└── Virtual Computer (Kotlin)
    ├── Accessibility Service (gestures, screenshot)
    ├── Input Injection (mouse, keyboard)
    ├── App Launcher / Manager
    ├── File & Network Operations
    ├── Script Executor (Python, Shell)
    └── Cloud Client (HTTP)
```

Communication between Python agents and Kotlin components uses Chaquopy (Python on Android) and a JNI bridge.

---

🔧 How to Build

1. Clone this repository.
2. Generate your own eve.keystore and update app/build.gradle with the passwords.
3. Open the project in Android Studio.
4. Build: ./gradlew assembleRelease.
5. Install the APK on your Android device (≥ Android 7.0).
6. Grant accessibility, storage, overlay, and device admin permissions (the app guides you).
7. Start sending commands through the built‑in chat UI or via the Hermes REST API.

---

📦 Dependencies

· AndroidX – UI, lifecycle, work manager.
· OkHttp / Retrofit – networking and GitHub API.
· Chaquopy – Python runtime (Flask, requests, OpenCV, Pillow, spaCy).
· TensorFlow Lite – optional on‑device LLM (Phi‑2) for advanced reasoning.
· AppAuth – GitHub OAuth flow.
· Biometric – fingerprint/face authentication for sensitive actions.

---

📝 License

This project is open‑source under the MIT License. Feel free to fork, extend, and contribute.

---

🤝 Contributing

We welcome pull requests! Please ensure your code follows Kotlin coding conventions and passes all tests. For major changes, open an issue first to discuss your proposal.

---

EVE Android – your autonomous mobile agent, ready to assist.
