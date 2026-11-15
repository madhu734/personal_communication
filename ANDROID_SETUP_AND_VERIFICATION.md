# Apun Ka Communication — Android Setup, Firebase Configuration & Verification Guide

## 1. Project Overview & Target Configuration

- **App Name**: `Apun Ka Communication`
- **Application ID**: `me.tmadhu.mandychat`
- **Firebase Project**: `mandy-chat`
- **Minimum SDK**: API 26 (Android 8.0) — supports `NotificationCompat.CallStyle`, `PictureInPictureParams`, and `AudioFocusRequest`.
- **Target / Compile SDK**: API 36
- **Default Backend Origin**: `https://pb.tmadhu.me` (HTTP & PocketBase SSE)
- **Default Web App Origin**: `https://chat.tmadhu.me` (Password reset & email change links via Custom Tabs)
- **Default LiveKit Media Server**: `wss://lk.tmadhu.me` (dynamically supplied by `POST /api/app/calls/{id}/token`)

---

## 2. Firebase Client Configuration (`google-services.json`)

1. Open the [Firebase Console](https://console.firebase.google.com/) and select project **`mandy-chat`**.
2. Register an Android app with package name **`me.tmadhu.mandychat`**.
3. Download the **client** `google-services.json` file (not the server Admin SDK service account JSON) and place it at:
   ```
   app/google-services.json
   ```
4. Keep all server-side credentials (`mandy-chat-firebase-adminsdk-*.json`, `LIVEKIT_API_SECRET`, `SMTP_PASSWORD`, `PB_SETTINGS_KEY`) on the server only.
5. Note: When `app/google-services.json` is omitted during local/emulator UI development, `googleServices { missingGoogleServicesStrategy = WARN }` allows `assembleDebug` to succeed and the in-app **Diagnostics & Call/Notification Test Triggers** let you test all call and notification flows immediately.

---

## 3. Building & Exporting the Project

- **Compile Debug APK**:
  ```bash
  gradle assembleDebug
  ```
  Output APK path: `app/build/outputs/apk/debug/app-debug.apk`
- **Run Local Unit & Robolectric Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```
- **Export to Android Studio / Push to GitHub**:
  Use the AI Studio **Export ZIP** or **Push to GitHub** option from the project settings menu.

### Automated GitHub Actions Signed APK & Release Workflow (`.github/workflows/release-apk.yml`)
- **Zero-Config Automatic Release**: Every push to `main` or `master` (or any `v*` tag, or manual click on **Run workflow** in the GitHub Actions tab) automatically compiles a **signed release APK** (`gradle :app:assembleRelease`) and publishes it directly to your repository's **GitHub Releases** page (`Apun-Ka-Communication-v1.0.<run_number>-signed.apk` and `Apun-Ka-Communication-latest.apk`).
- **Using Your Own Persistent Upload Keystore (Optional Recommended for Smooth In-Place App Updates)**:
  - If no secrets are added, the workflow automatically generates a valid self-signed release keystore (`alias = upload`) so your first push immediately produces a signed APK on the Releases page.
  - To sign every future GitHub release with the exact same persistent key (so users can install new updates over existing ones without uninstalling), add these 3 secrets under **GitHub Repository -> Settings -> Secrets and variables -> Actions**:
    1. `KEYSTORE_BASE64`: Base64-encoded `.jks` keystore file containing key alias `upload` (`base64 -w 0 my-upload-key.jks`)
    2. `STORE_PASSWORD`: Your keystore password
    3. `KEY_PASSWORD`: Your key password for alias `upload`
  - *(Optional)* `GOOGLE_SERVICES_JSON_BASE64`: Base64-encoded `app/google-services.json` if you do not commit `app/google-services.json` directly to the repository.

---

## 4. Two-Phone & Cross-Platform Verification Checklist

### A. Unified Message Ticks (`✓` Gray, `✓✓` Gray, `✓✓` Colored)
1. **Sent (`✓` gray)**: Turn Airplane Mode ON on Phone B. Send a message from Phone A (or Web). Verify Phone A shows a single gray `✓` beside the timestamp as soon as `POST /api/app/messages` saves on the server, even if Phone B's FCM push was queued.
2. **Delivered (`✓✓` gray)**: Turn Airplane Mode OFF on Phone B while keeping Phone B on the inbox or backgrounded (do not open the chat yet). When Phone B syncs the message and sends `PUT /api/app/conversations/{id}/delivered`, verify Phone A's tick transitions from `✓` gray to `✓✓` gray.
3. **Seen (`✓✓` colored)**: Open the conversation on Phone B. Verify Phone B sends `PUT /api/app/conversations/{id}/read` and Phone A's tick turns `✓✓` emerald/teal colored.
4. **Cross-Platform Parity**: Repeat between Android and Web (`chat.tmadhu.me`) after applying `BACKEND_AND_WEB_SYNC_PATCH.md`.

### B. Message Notifications: Direct Reply (`RemoteInput`) & Timed Mute (`1h, 2h, 8h, 1d, Always`)
1. **Grouped Notification & Lockscreen Privacy**: Send multiple messages from Phone A to Phone B while Phone B is locked/backgrounded. Verify messages are grouped per conversation and redacted on the lockscreen when `VISIBILITY_PRIVATE` is active.
2. **Direct Reply & Idempotent Retry**: Tap **Reply** on Phone B's notification and submit text without opening the app. Verify the message posts to that exact conversation. Test disconnecting network during reply: verify the notification displays **Retry Reply** and re-submitting reuses the exact same `client_message_id` without creating duplicate messages.
3. **Timed Mute & Auto-Expiry**: Tap **Mute** on the notification (or from the in-app chat menu) and test **১ ঘণ্টা (1h), ২ ঘণ্টা (2h), ৮ ঘণ্টা (8h), ১ দিন (1d), সবসময় (Always)**. Verify message notifications stay silent while muted, messages still sync in real time, **incoming calls still ring normally**, and the chat automatically unmutes once the timer expires.

### C. WhatsApp-Style Calling, Bluetooth, In-App Mini-Card & OS PiP
1. **Lockscreen & Background Ringing**: Lock Phone B and place an audio or video call from Phone A. Verify high-priority FCM wakes Phone B, displays the `CallStyle` notification (and full-screen UI when permitted), and cancels cleanly on caller hang-up or after the 45-second TTL.
2. **Audio Output Picker & Bluetooth**: Tap the audio route button during a call to open the BottomSheet populated with Earpiece, Speaker, Wired/USB, and Bluetooth endpoints. Verify switching routes and disconnecting a Bluetooth headset falls back gracefully.
3. **In-App Mini-Card vs. OS PiP**:
   - Tap the **Minimize** button inside the call screen: verify the **In-App Floating Mini-Card** appears over the inbox/chat with working round Mic, Camera, Expand, and Hang-up controls.
   - Press the Android **Home** button (or tap the PiP icon) during an active video call: verify the Activity enters **Android OS Picture-in-Picture** keeping the same LiveKit room alive and hiding chat/account chrome.
   - Single-tap the active video feed: verify **Focus Mode** hides controls, local preview, and in-call chat while preserving any unsent in-call chat draft, and a second tap restores them.
