# Cloud Agent — exact-commit Android verification

This PR adds the **Cloud Agent** tab to the existing EVE Android application.
It does not replace or reconfigure local Hermes, Agent Hub, or phone-control tools.

## CI build
1. Open the PR and verify all three `Eve Agent Hub CI` jobs pass for its **latest head SHA**.
2. Open that head's workflow run and download the `eve-cloud-agent-debug-<SHA>` artifact.
3. Check `sha256sum -c app-debug.apk.sha256` from the same artifact.
4. Install that APK on the Samsung Galaxy S23 Ultra (Android 16) by opening the file and allowing installs from the chosen file manager, or `adb install -r app-debug.apk` over USB.
5. Open EVE and verify the **Cloud Agent** tab is present and other tabs remain usable. Capture a screenshot.
6. Enter the verified **HTTPS bridge URL** and your bridge access token (at least 24 characters). Choose **SAVE**, then **TEST**. `/ready` must confirm an accessible Hermes API.
7. Submit a harmless task (e.g. summarize a public paragraph), observe RUN status until completed and retrieve the response.
8. Test STOP on a separate in-progress run, REFRESH after switching tabs, and clear/re-enter encrypted settings.
9. Review `adb logcat` for app crashes. Confirm no access token is present in APK resources or logs.

## Current limitations
- A successful APK build **does not prove** a live Hermes backend, deployed bridge, or physical-device pass.
- Configure a legitimate cloud model provider; no bypass of other apps' paid entitlements.
- Physical device evidence requires a user-connected phone or authorised device-testing service.
- This prototype monitors run status by polling; SSE and resumable artifact output are future integration steps.
- Keep API keys out of Git commits and Android APKs.
