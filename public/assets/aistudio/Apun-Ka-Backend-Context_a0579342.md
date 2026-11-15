# Apun Ka Communication — current service and Android handoff

Verified 15 November 2026, Asia/Dhaka. The public repository's `main`, the local implementation, and the finished Coolify deployment matched commit `fd663e2d2bce506e322d89fe4c1d9a6d2bec0463`. The public web page and health endpoint responded successfully. The application collection metadata below was checked against live PocketBase. No user records or secret values are included. [GitHub repository](https://github.com/madhu734/apun-ka-communication) · [Verified source revision](https://github.com/madhu734/apun-ka-communication/tree/fd663e2d2bce506e322d89fe4c1d9a6d2bec0463)

## 1. System and deployment

| Component | Current implementation |
|---|---|
| Product | Apun Ka Communication, a private one-to-one chat and audio/video calling service |
| Web | [chat.tmadhu.me](https://chat.tmadhu.me/). Vanilla JavaScript and CSS, Vite 8.3.8, PocketBase JS SDK 0.29.0, LiveKit JS SDK 2.24.1, and a small Web Push service worker |
| Backend and database | Go 1.27.4 application embedding PocketBase 0.40.6. PocketBase owns SQLite data, auth, collections, files, and realtime. The Go application adds account, chat, call, and push routes |
| Native API origin | `https://pb.tmadhu.me`. Health `GET /api/app/health` returns `status` and `callingConfigured` |
| Media | Separate LiveKit server v1.13.11. Use the WebSocket URL returned by the call token endpoint. Production signals at `wss://lk.tmadhu.me`, with authenticated TURN/TLS at `turn.tmadhu.me:443` |
| Runtime | One Coolify Docker Compose resource at **Apun Ka Projects → goriber saas → Apun Ka Communication**. The `backend` container serves the web build, Go routes, and PocketBase/SQLite. A second `livekit` container runs the media server |
| Persistence | PocketBase data and media use a host-mounted volume. Daily backups start at 02:00 Dhaka time with a seven-date retention window. They share the server disk |
| Publishing | A signed GitHub push webhook deploys `main`. Changes on that branch can redeploy production. Put the native Android source in its own repository or a separate project directory and keep the production deployment branch out of AI Studio's write path |

PocketBase HTTP requests from the production website go to the website's own origin. The website opens PocketBase SSE on `pb.tmadhu.me` so the website proxy's buffering stays out of the event stream. Native clients can use `pb.tmadhu.me` for both HTTP and SSE. PocketBase is also manageable at the [admin dashboard](https://pb.tmadhu.me/_/). Native clients authenticate as ordinary `users`.

The Compose stack builds the web bundle and Go binary in separate build stages and copies both into the same runtime image. The runtime reads PocketBase state at `/app/pb_data` and the built site at `/app/web`. Host state and backend settings are under `/opt/private-communication`. The two containers communicate over their Compose network and share the Coolify proxy network for ingress. Traefik provides HTTPS, LiveKit WebSocket and TURN/TLS routing. The media side exposes RTC TCP 7881, RTC UDP 7882 and TURN UDP 3478. Native clients should let LiveKit negotiate ICE and TURN with the server's returned connection details.

For isolated web work, run `npm ci` then `npm run build` from `web/`. `VITE_PB_URL` can point the web dev build at a test instance. For backend API tests, build the Go application and run `serve` with a new scratch data directory. Its migrations create the application collections. Test mail, media and push with independently supplied test configuration. The deployment references environment entries such as `APP_URL`, `LIVEKIT_WS_URL`, `LIVEKIT_INTERNAL_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `FCM_CREDENTIALS_FILE`, `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY`, `VAPID_CONTACT`, `SMTP_USERNAME`, `SMTP_PASSWORD` and `PB_SETTINGS_KEY`. Android only needs the public API origin, its Firebase client configuration, the ordinary user's token and per-call grants.

### Source map

| File | Read it for |
|---|---|
| `main.go`, `Dockerfile`, `compose.yaml`, `livekit.yaml` | Routes, runtime, static assets, proxy, Compose services and LiveKit configuration |
| `schema.go`, `accounts.go`, `requests.go` | Migrations, collection rules, relationship shapes, signup, sessions, OTP, profile files, member search and requests |
| `messages.go` | Message posting and retry, media limits, replies, edits, reactions, read cursor, typing and presence |
| `calls.go` | Call lifecycle, acceptance device binding, token grant, signed webhook and cleanup |
| `push.go`, `web/public/sw.js` | FCM/Web Push registration, payload, worker and browser delivery |
| `web/src.js`, `web/style.css`, `web/index.html` | The UI that actually ships and its client state and network behavior |
| `backup.go`, `disk_unix.go`, `disk_windows.go` | Backup maintenance and upload free-space check |
| `API.md`, `README.md`, `WEB.md`, `ANDROID.md`, `STATUS.md` | Existing references. `WEB.md` contains an older React proposal and `ANDROID.md` is a build brief. Use the implemented Go and web code for current behavior |

## 2. Web features the Android app should carry over

- **Account** supports signup, sign-in, password visibility, editing the display name, uploading/removing a profile picture, and email change by confirmation link. A signed-in user can change the password by repeating the new password. Recovery offers an email link or a six-digit code. Self-signup currently creates an account without an email verification gate.
- **Device change** keeps one active sign-in. A sign-in from a different installation revokes the preceding session and locks earlier chat history. The account continues to work for new messages. An emailed code unlocks older messages without deleting data. Testing Android and web simultaneously for the same account will normally sign out one of them.
- **Inbox** lets users find people by display name or full email address. Users can send a request with an optional first message, accept/decline received requests, cancel sent ones, or open an existing chat. Acceptance creates or reuses the chat and saves the optional note as the sender's first message. The admin can also manage conversations.
- **Chat** shows avatars, realtime updates with catch-up, text, replies, sender-only edit, protected images, voice notes, video, read-through, typing and presence. The latest page has 30 messages. The seven common reactions are 👍 👎 ❤️ 😂 😮 😢 😡, and the keyboard can supply a custom emoji. Each user can replace or remove their one reaction per message.
- **Calls** include incoming/outgoing one-to-one audio and video, accept/decline/hang up, mic and camera toggles, front/rear camera switch, screen sharing when available, and Auto HD, Smooth and Audio-only quality settings. In-call chat uses the same conversation and supports sending text without leaving the video.
- **Call layouts** keep mic, camera and hang-up icons available when minimized. Tapping the expanded video enters a distraction-free viewport layout and hides controls, in-call chat and the local preview. Tap again to restore, or press Escape on desktop. The chat draft and video element survive the toggle. The browser's minimized card and focus mode are in-page layouts. Android should additionally support OS-level PiP.
- **Web notifications** use an opt-in service worker for generic incoming call and new message notifications. The notification click focuses or opens the web app and refreshes server state.

The browser renders the sign-in panel, chat sidebar, timeline, composer, account dialogs and call overlay from `web/src.js`. The responsive layout, colors and self-hosted Jakarta font are in `web/style.css` and `web/public/`. PocketBase auth and the installation UUID use browser storage. Message state and the send retry key are held in memory. The service worker handles push and notification clicks, and the web code does not implement a persistent offline send queue. LiveKit loads on demand. Its web settings use adaptive stream and dynacast, prefer H.264 with VP8 backup, and request a lower subscribed layer when the call is minimized.

## 3. PocketBase schema

Each app collection has a generated 15-character `id` and `created` and `updated` autodates. Relations are single-valued and required unless described otherwise. Hidden fields are omitted from ordinary record responses. Treat record IDs as opaque strings and dates as strings normalized to instants. PocketBase can return empty strings for unset date, file or relation fields.

| Collection | Fields beyond the shared fields | Index and access |
|---|---|---|
| `users` (auth) | Required `email`, `password` (hidden, min 8), `tokenKey` (hidden, length 30–60); `emailVisibility` and `verified` bool; `name` text≤255; `avatar` one protected file≤3 MiB, JPEG/PNG/WebP/GIF; `current_device` hidden text≤128; `history_cutoff` date | Unique nonempty email and token key. Self list/view/update. Conversation partners can fetch the avatar in protected-file context. Account creation goes through the custom signup route. User edits cannot set device trust, history cutoff, verification or email visibility |
| `conversations` | `members` → users, exactly two; required hidden `pair` text≤32, normally sorted user IDs joined by `:` | Unique `pair`. Member-only list/view. The server also recognizes an existing matching member pair when an admin entered a different pair key |
| `message_requests` | `sender`, `recipient` → users, cascade on user removal; required hidden `pair` text≤32; optional `note` text≤500 | Unique pair, nonunique `(sender,created)` and `(recipient,created)`. Pending records visible to sender and recipient |
| `conversation_members` | `conversation` → conversations, `user` → users; `read_through` date, `read_id` text≤15, `presence_until` date, `typing_until` date | Unique `(conversation,user)`. Readable by either conversation member |
| `messages` | `conversation` → conversations, `sender` → users; `text` text≤10,000; required `client_message_id` text≤64; required `kind` in `text,image,voice,video`; `attachment` one protected file≤50 MiB; `attachment_bytes` integer; optional `reply_to` → messages; `in_call` bool | Unique `(sender,client_message_id)`, nonunique `(conversation,created,id)`. Member-only, filtered by history cutoff |
| `message_reactions` | `conversation` → conversations, `message` → messages, `user` → users; required `emoji` text≤24 | Unique `(message,user)`. Member-only. The referenced message's creation time determines history access |
| `calls` | `conversation` → conversations, `caller`/ `receiver` → users; required `room` text≤80; required `kind` in `audio,video`; required `status` in `ringing,accepted,connected,ended,rejected,cancelled,missed,failed`; dates `ring_expires_at,accepted_at,connected_at,ended_at,empty_since`; hidden `accepted_device` text≤80 and `cleanup_pending` bool | Unique `room`, nonunique `status`. Member-only with history cutoff. Call actions additionally check caller/receiver and server state |
| `devices` | `user` → users; required `platform` in `android,web`; required hidden `destination` text≤4096; hidden `p256dh` text≤150 and `auth_secret` text≤100 | Unique `(platform,destination)`. Owner list/view. Device registration is an application API operation |
| `notification_outbox` | `user` → users, `device` → devices; required `event` text≤24, `reference` text≤80; dates `expires_at,next_attempt`; integer `attempts` | Server-only. Due index on `next_attempt` |
| `account_codes` | `user` → users; required `purpose` text≤20 (`history` or `recovery` in the implementation); required hidden `digest` text≤64; hidden `session` text≤64; integer `attempts`; date `expires` | Server-only. Unique `(user,purpose)`. Codes are stored as digests bound to the current token key |

PocketBase system collections also exist. `_superusers` is the separate admin auth store. `_authOrigins` records login fingerprints for PocketBase's auth alert. `_externalAuths`, `_mfas` and `_otps` serve PocketBase's OAuth, MFA and built-in OTP features. The app's recovery/history codes use `account_codes`. Live `users` enables password auth and has OAuth, MFA, built-in OTP and auth alerts disabled.

The app schema is built through Go migrations `1794678000_initial.go`, `1794678100_member_api.go`, `1794679200_accounts.go`, `1794682800_profiles.go`, and `1794686400_message_requests.go`. Application collection rules permit only the reads described above. Use explicit application routes for writes, except the allowed PocketBase profile and auth operations. Treat required relations as real dependencies in any admin cleanup.

## 4. Client API and sync

Base URL `https://pb.tmadhu.me`. Send the raw PocketBase auth token in `Authorization` for authenticated requests. Generate one UUID at install time and keep it stable across process restarts and ordinary sign-outs. Put it in `X-App-Device` for registration and password sign-in, and send the same string as `deviceId` when answering or requesting a call token. The header accepts 24–128 characters, and the accept body limits the value to 80. A UUID fits both. Registration stores the ID and signs in the new user immediately.

### Account routes

| Method and path | Input and response |
|---|---|
| `POST /api/app/account/register` | `{name,email,password,passwordConfirm}` and device header. Returns `{token,record}`, 200. The name accepts up to 80 characters, and the matching password is 8–72 UTF-8 bytes |
| `POST /api/collections/users/auth-with-password` | `{identity,password}` and device header. Use the email as `identity`. Returns `{token,record}` |
| `POST /api/collections/users/auth-refresh` | Auth token, returns refreshed `{token,record}`. Use this to restore/refresh existing auth |
| `GET /api/app/account/session` | `{historyLocked:boolean}` |
| `PATCH /api/collections/users/records/{ownUserId}` | Edit `name` and upload `avatar` as multipart. Clear avatar with an empty `avatar` field. Keep the actual auth token when updating the local profile |
| `POST /api/app/account/password` | Authenticated `{password,passwordConfirm}`. Replaces the password and rotates the auth token. Save the returned `{token,record}` immediately |
| `POST /api/app/account/history/request` and `/history/confirm` | Request with `{}`, confirm with `{code}`. 204. Refresh session and history on success |
| `POST /api/app/account/recovery/request` and `/recovery/confirm` | Guest request `{email}`, confirm `{email,code,password,passwordConfirm}`. 204. Sign in with the new password after successful confirm |
| `POST /api/collections/users/request-password-reset` and `/confirm-password-reset` | Guest request `{email}`, confirm `{token,password,passwordConfirm}`. The branded email links to `https://chat.tmadhu.me/#reset/{TOKEN}` |
| `POST /api/collections/users/request-email-change` and `/confirm-email-change` | Start while signed in with `{newEmail}`. Confirm with `{token,password}`, using the **current** password. The email links to `https://chat.tmadhu.me/#email/{TOKEN}`. Resume by signing in with the new address |
| `POST /api/app/account/logout` | Authenticated `{}`, revokes the session token, 204. The client should delete its known push registration first, then clear local state |

Every password sign-in rotates the token key. A different installation additionally sets `history_cutoff`, removes previous push registrations and ends active calls. The history lock excludes messages and calls at or before cutoff, and reactions on those messages, including protected message files. Make any local cache respect the same lock and purge user data when authorization is revoked. Live `users` token durations are five days for auth, three minutes for files and 30 minutes each for email-change and password-reset links. A custom code expires in 10 minutes, has up to five confirmation attempts, and has a short resend cooldown. SMTP uses branded Gmail HTML mail on the server. Use the existing HTTPS email confirmation pages for the native first release.

### Chat, requests, and devices

All paths in the next two tables are relative to `/api/app` unless a path starts with `/api/`.

| Method and path | Input and response |
|---|---|
| `GET /inbox` | `{chats:[{id,peer:{id,name,avatar?}}],requests:[{id,peer:{id,name},incoming,note}]}` |
| `GET /members/search?q=…` | Display-name substring of at least two characters, or exact full email. Up to 20 results `[{id,name}]`. Excludes self and does not return member emails |
| `GET /conversations/{id}/people` | `[{id,name,avatar}]` for that chat's members |
| `POST /message-requests` | `{recipient,note?}`, optional note up to 500 characters. Returns `{request,direction}` with `incoming` or `outgoing`, or `{conversation}` if already paired. A duplicate or crossed request returns the existing request |
| `POST /message-requests/{id}/accept` | Recipient only, `{}`, returns `{conversation}`. Server transaction creates/reuses the conversation and both membership rows, saves the introductory message if present, and removes the pending request |
| `DELETE /message-requests/{id}` | Sender cancels or recipient declines. 204. If an accept or cancellation races with another action, reload the inbox |
| `POST /messages` | `{conversation,text,client_message_id,kind,reply_to?,in_call?}` as JSON for text. Use the same fields as multipart plus a single `attachment` for `image`, `voice` or `video`. Returns message, 201 for new, 200 for matching retry. Keep the same client ID for that queued send |
| `PATCH /messages/{id}` | Sender only, `{text}`, returns updated message. Reply targets must be in the same chat and visible under history policy |
| `PUT /reactions/{messageId}` | `{emoji}`, 204. An empty string removes the caller's reaction |
| `PUT /conversations/{id}/read` | `{message}`, 204. Monotonic `(read_through,read_id)` cursor. A successful send shows server receipt. There is no separately reported per-device delivery receipt |
| `PUT /conversations/{id}/presence` | `{typing:true/false}`, 204. Presence lasts 45 s and typing lasts 8 s. Send while relevant UI is foregrounded |
| `GET /push` | `{android:boolean,webVapidPublicKey:string}`. Android should check whether FCM is enabled |
| `POST /devices` | Android `{platform:"android",token:<FCM registration token>}`. Returns the device record, 200. Keep its record ID, not only the FCM token. An existing same-user token is reused. A conflicting cross-account registration returns 409 |
| `DELETE /devices/{id}` | Deletes own registration and its pending push jobs, 204. Current server cap is ten registered destinations per account |

**Protected media.** Request a short-lived file token via `POST /api/files/token` with auth. Load `/api/files/messages/{recordId}/{filename}?token=…` or `/api/files/users/{recordId}/{avatar}?token=…`. Append `thumb=400x0` for message images, or `thumb=96x96` / `192x192` for avatars. Retry once with a new file token when it has expired, while respecting record access. The backend accepts `image/jpeg,image/png,image/webp,audio/webm,audio/ogg,audio/mpeg,audio/mp4,audio/aac,video/mp4,video/webm,application/ogg` for message files. Android AAC in an MPEG-4 container is a sensible voice-note format to validate with a real upload and cross-client playback. Attachment caps are 50 MiB per file, 2 GiB aggregate and a 15% disk-free guard. Text max is 10,000 bytes in the custom Go handler.

**Realtime.** Use `GET /api/realtime` to obtain `PB_CONNECT` with the client ID, then authenticated `POST /api/realtime` with `{clientId,subscriptions}`. Supply the entire topic set each time. Useful collection topics are `conversations/*`, `conversation_members/*`, `message_requests/*`, `messages/*`, `message_reactions/*`, `calls/*`. Consume create/update/delete, merge by record ID, and re-fetch after reconnect, token change and notification tap. Use REST when SSE is temporarily unavailable. Standard PocketBase lists live at `GET /api/collections/{collection}/records`, individual records at `.../records/{id}`. Page newest at `-created,-id` in groups of 30, fetch older with a composite cursor, and catch up after the highest tuple. Parse PocketBase timestamps and RFC 3339 push expiry separately. [PocketBase realtime reference](https://pocketbase.io/docs/api-realtime/)

**Errors.** Handle 400 validation, 401 missing/revoked auth, 404 unavailable/not visible, 409 stale action/busy/duplicate association, 429 rate limiting, 503 unavailable calling or mail and 507 media quota/disk guard. Empty 204 responses need no JSON decoder. A protected-record 404 by itself does not mean the user should be signed out.

## 5. Calls and push contract

| Method and path | Behavior |
|---|---|
| `POST /calls` | `{conversation,kind}`, with kind `audio` or `video`. 201 and a call record initially `ringing`. Ring window is 45 s. Server prevents simultaneous active calls for either party |
| `GET /api/collections/calls/records/{callId}` | Authoritative state for push, notification actions, app resume and reconnect |
| `POST /calls/{id}/accept` | Recipient only with `{deviceId}`. Repeat accept from the accepting device is idempotent while active |
| `POST /calls/{id}/reject` | Recipient with `{}`, while ringing |
| `POST /calls/{id}/end` | Caller with `{}` can cancel ringing. Either party can end accepted/connected calls |
| `POST /calls/{id}/token` | `{deviceId}`, allowed only when accepted/connected. Receiver must use the accepting device ID. Returns `{token,wsUrl,roomName,expiresAt}`. The room JWT lasts five minutes and permits media publication/subscription in that room |

The call progresses `ringing → accepted → connected`. A signed LiveKit webhook and periodic server reconciliation update connectivity. Terminal states are `ended,rejected,cancelled,missed,failed`. A call that cannot reach two participants within roughly a minute of acceptance can fail. A connected call typically has a 45-second reconnect grace period after the server detects a missing participant. Use the authoritative record if foreground UI, push and media SDK signals arrive in a different order. Start media after acceptance. Let LiveKit resume before requesting a new room token. Use PocketBase for every chat message inside a call. The LiveKit grant disables data publication. LiveKit uses encrypted WebRTC transport, and the source shows no application-level media E2EE or recording pipeline.

### What the server actually sends

```json
{"event":"incoming_call","id":"<call record ID>","expiresAt":"<UTC RFC3339 timestamp>"}
```

For `new_message`, `id` is the **conversation ID**. FCM uses high priority for `incoming_call` with TTL limited by ring expiry. `new_message` is normal priority with an expiry around 15 minutes. Push payloads contain no caller name, chat content, auth credential or media token. A DB outbox queues delivery, the worker checks roughly every three seconds, and dead tokens and exhausted jobs are pruned. Pending requests and terminal call transitions have no explicit push event in this revision.

The backend container has a readable configured Firebase service credential. Existing validation reached Firebase HTTP v1 in validate-only mode. A production native APK, Android token registration and real lockscreen delivery still need device testing. The Firebase project is `mandy-chat`. The existing Android brief proposes `me.tmadhu.mandychat` as the app ID. Confirm that ID against Firebase before adding the Android app. The client should receive its matching `google-services.json` through local/project setup. Firebase Admin credentials, SMTP credentials, the PocketBase superuser, server settings encryption and LiveKit signing keys remain on the server. [Firebase Android client setup](https://firebase.google.com/docs/cloud-messaging/android/client)

## 6. Android implementation plan

### A. Foundation and web parity

Build native Kotlin and Jetpack Compose with coroutines/Flow, an HTTP/SSE client, Firebase Messaging, LiveKit Android and Jetpack Core-Telecom. Keep screen state, account/session storage, repositories, and the live call controller distinct. Use Android Keystore-backed storage for session tokens. Verify the latest compatible Gradle/AGP/Kotlin/Compose SDK versions when starting. A debug build can use mocked notification fixtures when no Firebase client file is provided and must say so. Production sign-in and PocketBase APIs use the real backend. Stream/download only when needed, use the system photo picker, and keep private media out of shared storage unless the user explicitly exports it.

Implement a scoped outgoing queue with send progress and an explicit retry affordance. Retain the same `client_message_id` for a retry. For initial caching, keep recent private data in memory or protected account-scoped storage. Invalidate caches on revoked sessions and block cached older content when history is locked. Use the current branded web email pages in a Custom Tab for link recovery and email confirmation. Support the two code flows directly in Android.

### B. Reliable Android calling is the first device milestone

1. Use `FirebaseMessagingService` to forward incoming call data to one call coordinator. Verify signed-in state and validate `event`, call ID and `expiresAt`. Deduplicate a call by call ID across FCM, SSE, resume and repeated actions. Post a high-importance generic incoming call notification promptly when an eligible incoming event arrives. Fetch current call state for enrichment in parallel and dismiss the notification if the record is no longer ringing or belongs to another account. Keep normal message notifications distinct and group them by conversation. Deduplicate exact repeated message payloads without suppressing later messages for that conversation.
2. Use `NotificationCompat.CallStyle` for incoming/ongoing notifications and user actions, and use one Core-Telecom self-managed call entry. Keep Answer, Decline and End routed to the same serialized coordinator, including lockscreen, notification, handset and Bluetooth callbacks. Use a foreground phone-call service with the SDK/platform requirements. Post the call notification within the Telecom deadline and complete Telecom callbacks promptly. Query the actual permissions and channel settings. Offer full-screen incoming UI only on eligible platform configurations. Provide usable heads-up or notification actions when full-screen presentation is unavailable. [Core-Telecom guide](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom) · [Call-style notifications](https://developer.android.com/develop/ui/views/notifications/call-style) · [Android full-screen intent behavior](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi)
3. On Answer, reload the call by ID with the user's session, verify recipient, ringing state and expiry, then call accept with the stable installation UUID and fetch the LiveKit grant. Join one room. Start microphone/camera only after valid user interaction and platform permission. If camera access is unavailable or denied, support audio first and let the user opt in to video from a visible activity. Set the required foreground service types and permissions for the actual features in use. Follow background-start and while-in-use restrictions. [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
4. Keep the same coordinator and room across screen rotation, notification/foreground transitions, in-app minimized mode, video focus and OS PiP. When the remote ends or a local action ends the call, converge on one terminal state and stop ring, audio focus/route, media capture, foreground service and notification. Use authenticated SSE for prompt foreground cleanup and expiry/current state revalidation for background notifications. Treat swipe-away, process death, OEM battery restrictions, OS force-stop and revoked notification permissions as separate test scenarios. High priority assists timely delivery. It cannot guarantee notification behavior under every device condition. [FCM message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority)
5. Register and refresh the FCM token after successful auth and in `onNewToken`. Bind a pending token only after the matching account signs in. Save the server registration ID, remove the old registration after a confirmed replacement, and remove it before sign-out while auth still works. Validate 409 ownership conflicts instead of reusing another account's registration.

### C. Audio route picker, PiP, and call polish

- **Audio picker.** Tap the speaker/audio icon to open a Compose sheet with the live endpoints reported by Core-Telecom, such as phone earpiece, speaker, Bluetooth and wired/USB headset when available. Observe `currentCallEndpoint`, `availableEndpoints` and mute. Switch with `requestEndpointChange()`, show the actual selected route, and follow the OS fallback when an endpoint disconnects. Telecom should own routing and focus. Configure LiveKit with `AudioOptions` and a compatible audio handler, such as `NoAudioHandler` when Telecom owns routing and focus. Verify SDK signatures and call-appropriate audio attributes. [CallControlScope](https://developer.android.com/reference/androidx/core/telecom/CallControlScope) · [LiveKit Android AudioOptions](https://docs.livekit.io/reference/client-sdk-android/livekit-android-sdk/io.livekit.android/-audio-options/index.html)
- **Native PiP.** Use Android activity PiP from API 26 on devices that support it. Use `PictureInPictureParams`, source rect and eligible auto-enter on API 31+. Show the remote video with a clean aspect ratio. Keep chat inputs and full controls in the expanded UI. Optionally include supported mute/hang-up `RemoteAction` controls using the same coordinator. Opening PiP, expanding or rotating must preserve the same LiveKit room. Stop PiP presentation when the call ends. Keep normal back navigation in audio calls. [Android PiP guide](https://developer.android.com/develop/ui/views/picture-in-picture)
- **Video.** Retain tap-to-focus and compact call controls from the web. Prefer hardware H.264 where supported, use VP8 fallback when needed, allow Auto up to 720p, Smooth 360p and Audio-only. Recover subscriptions and keep audible audio first during packet loss, backgrounding or thermal throttling. Only restore camera if the user's last choice, lifecycle and permissions permit it. Share screen via Android MediaProjection with fresh user consent, proper service type and stop cleanup. Share playback audio only where the OS and source app permit. [MediaProjection](https://developer.android.com/media/grow/media-projection)
- **Call diagnostics.** Keep privacy-safe local diagnostics for push received/ring displayed, answer-to-audio time, room reconnect and route selection. Track transport and quality in aggregate. Keep message bodies, authorization headers, FCM tokens, email codes, file URLs and media credentials out of logs.

### D. Optional in-call Secure Mode

This is a planned Android and backend feature. Every new call starts with Secure Mode off. Show a lock icon on active audio and video calls. Either participant can enable it, and it applies to both participants. The first successful enable request establishes the owner for that activation. Only that owner can turn it off. Once turned off, either participant can enable it again and become the new owner. Ending the call clears the restriction for the next call. Show both participants who enabled it and explain the locked control to the other participant.

**Proposed server contract.** Add call-scoped capability reporting, an owner user ID, an enabled state, and a monotonic revision to the existing call model. Require both active call clients to support this feature before offering the control. For the first release, enable it only between compatible Android clients. Treat browser and older app participants as unsupported. Update the shared state through a participant-authenticated route such as `PUT /api/app/calls/{id}/secure-mode` with `{enabled,expectedRevision}`. In one transaction, check the active call, participant and session/device binding, revision, and ownership rule. Return the authoritative state and reject conflicts without changing ownership. Publish changes through the existing calls SSE feed, and refetch on reconnect or resume. Add a participant acknowledgement for each applied revision, bound to that call and device. An acknowledgement reports what the client applied.

**Android behavior.** The initiator applies local capture protection as soon as they tap enable. Both clients apply `WindowManager.LayoutParams.FLAG_SECURE` to windows containing call content before acknowledging the enabled revision. Keep the flag through in-app minimize, video focus, rotation, background and activity recreation. For PiP, verify protection on each supported configuration or hide the video/disable PiP for the protected call. Stop any active in-app screen share before acknowledging and block new screen shares while enabled. For audio playback, apply `ALLOW_CAPTURE_BY_NONE` to the call's playback path where the OS and pinned media SDK support it, and restore the normal policy when protection ends. Verify this separately from visual capture. When off, restore ordinary app behavior subject to the platform's own policies. [Android FLAG_SECURE](https://developer.android.com/security/fraud-prevention/activities) · [Android audio playback capture](https://developer.android.com/media/platform/av-capture)

**Synchronization and limits.** Show an applying state while either acknowledgement is pending, and avoid showing unprotected video during uncertain transitions. Keep an applied restriction during reconnect until fresh server state confirms an owner-authorized off or the call ends. Only display “Secure Mode on for both” after both clients acknowledge the current enabled revision. Ordinary Android screenshots and screen capture are the protection target. A separate camera or recorder, a modified device/client and privileged capture can still record. Secure Mode is a device capture control.

**Acceptance tests.** Use two physical phones with different Android builds. Verify default off, either participant enabling, the other participant denied when attempting to turn it off, simultaneous enables, the owner disabling and re-enabling, and a new call starting off. Test screenshot and built-in screen recording on both sides, an already-running recording, audio capture separately, recent-app previews, PiP, screen sharing, reconnect, rotation and app recreation. Test that an incompatible peer gets an unavailable state and that no affirmative protection claim appears before both acknowledgements.

### E. Extra features and their dependencies

| Priority | Feature | How it fits |
|---|---|---|
| Next, client-side | Recent calls with missed/rejected/ended labels and call-back | Read existing `calls` and honor history cutoff. Use existing call start route |
| Next, client-side | Per-chat notification mute and notification diagnostics | Local preferences and channel state. FCM payload already identifies the conversation. A cross-device preference would require storing server settings |
| Next, client-side | Offline draft/retry, upload progress/cancel, attachment preview, backup-free private cache | Use current send/dedup/file API and account-scoped storage. Keep app offline behavior explicit and retry only the user's authorized send |
| Next, client-side | Notification direct reply as an opt-in | Use Android's notification text reply with the current session and original conversation ID. Submit through the same message route using a stable send ID |
| Later, server contract | Faster dismissal of a background ring on caller cancellation or remote end | Add a versioned terminal/update call push event and handle duplicates and state ordering. The existing client can use TTL, authenticated reads and SSE where available |
| Later, server contract | Message-request and accepted-request notifications | Enqueue explicit events from request/accept flows. The current worker pushes normal posted messages and incoming calls |
| Later, client + server/domain | Verified Android App Links for password reset and email change | Coordinate the URL routing, `assetlinks.json`, signing fingerprint, safe token handling and web fallback. Existing email URLs remain usable |
| Later, architectural | Group chat/calls, cross-device sync, delivered receipts, chat content E2EE | Plan separately. The current schema and media room enforce two participants, auth rotates to one active device, and the API exposes read-through |

### F. Release acceptance

Test with **two distinct test accounts** and at least **two physical Android phones**, ideally from different manufacturers, plus Android↔web on separate accounts. Cover fresh install, signup/password recovery, new-device revocation and history unlock, notification token renewal, duplicate/expired rings, recipient-only answer, cancel-before-answer, busy, foreground/background/screen locked, Doze, app swipe-away and OS force-stop as distinct states. Test permission denial for notifications, full-screen intent, camera and mic. Verify Bluetooth connect/disconnect, headset button callbacks, earpiece and speaker fallback, Wi-Fi↔mobile handover, in-call chat on both platforms, PiP entry/exit/rotation and hang-up, protected attachments and shared-screen permission/stop. Measure actual notification and answer latency on the physical devices and document what was measured. An emulator or Firebase validate-only response is useful early feedback and leaves physical-device verification pending.

Package a clean Gradle project, installation/run instructions, a debug APK for internal testing, an environment/Firebase setup guide, and a list of any remaining device or server dependencies. AI Studio's documented browser emulator lacks camera, Bluetooth and Google Play services, and Android project export uses ZIP. Use the emulator for UI/state fixtures and continue hardware and FCM testing on physical devices. [Google AI Studio Android documentation](https://aistudio.google.com/docs/aistudio-android)

