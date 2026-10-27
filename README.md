# Dobby

A personal assistant that lives on your phone. Local model, your data, your tools.

Everything runs on the phone: a local LLM (LiteRT-LM), connectors exposed as MCP tools,
and a chat screen. Pluggable: a connector is one Kotlin class; a model is one registry row.

## Status
First cut, **not yet compiled**: this tree was written without an Android SDK at hand.
Expect a round of compile fixes on the first CI run, most likely in `LiteRtLmEngine.kt`
(LiteRT-LM API surface) and Compose imports. Everything else is plain Android/Kotlin.

## Build
- CI: `.github/workflows/android.yml` builds `app-debug.apk` on push to `main`
  (no Gradle wrapper needed; it uses `gradle/actions/setup-gradle`).
- Local: Android Studio, or `gradle wrapper && ./gradlew assembleDebug`.
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

## First run
1. Settings → paste a Hugging Face token (Gemma models are gated: accept the licence on HF first).
2. Models → Download Gemma 4 E4B (3.7 GB, Wi-Fi) → Select. Check the file name on the HF repo
   page; if it differs from `assets/models.json`, use "Add by URL" with the exact link.
3. Connectors → switch on Call log, SMS, Device → Grant permission. For a sideloaded app:
   App info → ⋮ → Allow restricted settings first, or the SMS/call-log grant is greyed out.
4. Google connectors: Settings → Google OAuth client id (Android type, package
   `app.dobby`, your signing SHA-1; enable Gmail/Docs/Sheets APIs) → Connectors → Sign in.
5. Chat.

## Layout
```
mcp/         ToolSpec, McpServer, McpRouter   — MCP shapes (tools/list, tools/call), in-process
connectors/  Connector + registry; CallLog, Sms, Gmail, Docs, Sheets, Browser, Device
model/       ModelRegistry (assets/models.json + custom), ModelDownloader (DownloadManager),
             LlmEngine (interface), LiteRtLmEngine (runtime), EngineHolder (one engine at a time)
chat/        ChatViewModel, ChatScreen
ui/          Connectors, Models, Settings screens
eval/        EvalRunner — runs assets/golden.json through the selected model
memory/      MemoryStore (SQLite facts + profile), PromptBuilder (profile in system prompt, relevant facts per message)
location/    Places with geofence rules (Play services), GeofenceReceiver → AssistantJob; last 200 enter/exit events only
reminders/   AlarmManager reminders (exact, Doze-safe), boot reschedule, AssistantJob (WorkManager)
             that wakes the model for reminders that carry a prompt
```

## Add a connector
Implement `Connector` (id, title, requirement, `tools()`, `call()`), add it to
`ConnectorRegistry.all`. Tools appear to the model as `<id>.<tool>`.

## Add a model
Add a row to `assets/models.json`, or in the app: Models → Add by URL.
Any `.litertlm` file works. For a second runtime (llama.cpp/GGUF), implement `LlmEngine`
and switch it in `EngineHolder`.

## Naming
Project, app and package are `Dobby` / `app.dobby`. Keep icons, screenshots and copy free of any
third-party characters or franchises; the name is used in its ordinary-word sense.

## Licence
Not chosen yet. Decide (Apache-2.0 or AGPL-3.0) before the repo goes public and add `LICENSE`.

## Reminders and scheduled checks
Reminders use one AlarmManager alarm each (`setAlarmClock`), so nothing runs between triggers.
A reminder with a prompt enqueues a WorkManager job at trigger time: it loads the selected model,
runs the prompt with the active connectors, posts the answer as a notification, and unloads.
Tell Dobby in chat: "every weekday at 8, check SMS for bills due this week" → `reminders.create`.
If "Allow exact alarms" shows in the Reminders tab, grant it; otherwise alarms fire within ±10 min.

## Memory and places
- Memory tab: profile lines (always in the prompt) and tagged facts; the model can also save facts via `memory.remember`.
- Places: add a place at your current location, attach rules like "exit, weekdays 8–11 → ask about a cab". Needs
  location "Allow all the time" (two-step grant in Tools). No continuous tracking; only enter/exit events are kept.

## Known gaps
- Browser is read-only fetch; no WebView automation yet.
- Tool calls run through LiteRT-LM's automatic loop, which caps at 25 calls per message.
- Streaming assumes each `Message` from `sendMessageAsync` is a delta; if it is cumulative, replace
  `+=` with assignment in `ChatViewModel`.
- No scheduled background jobs yet (watchers from the design doc).
- Keep `golden.json` free of real data if the repo is public.
