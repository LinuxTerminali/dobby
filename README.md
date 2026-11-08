# Dobby

A personal assistant that lives on your phone. Local model, your data, your tools.

Everything runs on the phone: a local LLM (LiteRT-LM), connectors exposed as MCP tools,
and a chat screen. Pluggable: a connector is one Kotlin class; a model is one registry row.

## Status
Builds on CI; runs on a Pixel 9 with Gemma 4 E4B. The pure Kotlin parts (harness loop, router,
validator, compaction, parsers, metrics) are unit-tested; Android-side code is reviewed and exercised on
the device through the eval and real use.

## Build and updates
- Models: grant "All files access" (Models tab banner) and they live in `Download/Dobby/models`,
  which survives uninstall. Without it they sit in the app's private folder and go with the app.
- Debug builds are signed with the committed `app/debug.keystore` (standard Android debug password),
  so `adb install -r` upgrades in place. The first install after this change needs one uninstall.
- Every push to `main` publishes a GitHub Release `build-N` with the APK; `N` is the app's versionCode.
  Settings → Check for updates downloads it. Play Protect may block the install; ADB never is.
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
             LlmEngine, AgentEngine (harness), ChatBackend, LiteRtLmBackend, RemoteBackend, EngineHolder
finance/     SmsTemplates (bank SMS → records), Ledger (SQLite), MoneyMath (anomaly/due rules)
chat/        ChatViewModel, ChatScreen
ui/          Connectors, Models, Settings screens
eval/        EvalRunner — runs assets/golden.json through the selected model
memory/      MemoryStore (SQLite facts + profile), PromptBuilder (profile in system prompt, relevant facts per message)
location/    Places with geofence rules (Play services), GeofenceReceiver → AssistantJob; last 200 enter/exit events only
reminders/   AlarmManager reminders (exact, Doze-safe), boot reschedule, AssistantJob (WorkManager)
             that wakes the model for reminders that carry a prompt
```

## Engines
`ChatBackend` is the only runtime-specific surface (open / stream / oneShot / reset / close). `AgentEngine`
holds the whole harness (tool loop, router, validator, approvals, retries, context accounting) over any
backend and is unit-tested with a scripted fake. Backends:
- `LiteRtLmBackend`: on-device `.litertlm` files; plain chat only (the runtime's native tool path corrupted
  output on the Pixel 9 and is not used).
- `RemoteBackend`: any OpenAI-compatible server (`POST {base}/chat/completions`, SSE streaming) — vLLM,
  llama.cpp server, Ollama, LM Studio or a hosted API. Models → Add endpoint (base URL ending in `/v1`,
  model id, optional key). Conversations to a remote server leave the phone; keep it on your own network.
  Plain `http://` endpoints are allowed (`usesCleartextTraffic`), intended for Tailscale/LAN servers; use
  `https://` for anything beyond that (Tailscale can issue a certificate with `tailscale cert`).

## Money
`money.bills_due`, `money.bill_anomalies` (>1.3× the median of the last 6 bills and >₹200), `money.balances`,
`money.spend`, `money.rescan`. Bank and biller SMS are parsed by regex (`finance/SmsTemplates`, tested on
HDFC/SBI/BESCOM/Airtel-style wording; extend per bank) into a local SQLite ledger; the model never parses
money text. A daily check: "every day at 8 check bills due this week and unusual bills" → a reminder with a prompt.

## Capacity
Limits scale with the model (`model/Capacity`): on-device = small (1.5K tool data, 5 steps, compaction at
10K, 3 research pages); remote = large (8K, 10 steps, 60K, 5 pages, longer passages, more search results).

## Web tools
`web.search` (DuckDuckGo HTML, or Brave Search with a key in Settings), `web.fetch` (readable main text,
JS pages rendered in a hidden WebView), `web.extract(url, query)` (the passages that match a question,
for long pages and tables), `web.research(question)` (search → read top pages → relevant passages with
numbered sources, in one call). Pages are cached per session. `TextExtract` and `SearchParse` are pure
Kotlin with tests.

## Scheduled checks
`reminders.create` with `every_minutes` (min 15) and a `prompt` runs that prompt with tools on schedule;
the job notifies only when the answer is not "NOTHING"/"no change". The protocol tells the model it can
do this, so "check this page every 15 minutes" becomes a tool call.

## Validator
Code checks the model's output every turn (`model/Validator`, pure Kotlin, tested):
- A numeric tool argument that looks like a mangled copy of a number the user typed (365 → 35) blocks the
  call; the model is told the user's value and asked again.
- Numbers and clock times in a reply must appear in the user's message, the tool results, or today's date;
  malformed times (23:000) always fail. String args that look like a word the user typed with characters
  dropped (moengage → engage) block the call; otherwise the
  reply is regenerated once with the offending numbers named, and if still unverifiable it is shown with
  "⚠ Could not verify: …".
- An empty tool result tells the model to try another listed tool before giving up.
- After two GPU runtime errors in a session the model is switched to CPU with a note.

## Compaction
After ~2.5K tokens of conversation (and at least 4 turns), older turns are summarised by a tool-less one-shot
call into a ≤120-word note; lasting facts the user stated are saved to memory (source `compaction`); the
conversation restarts with the note and the last two exchanges as context. The chat shows a small
"Earlier turns summarised" line. `Compactor` is pure Kotlin with tests.

## Runtime errors
LiteRT-LM on the Pixel 9 GPU degrades as a conversation grows (dropped digits, then `Status Code: 9 …
state entries` / `Status Code: 13`). The engine reports these as `RuntimeError`; the chat compacts, resets and
retries the message once, with a visible note. Tool data fed to the model is capped at 1.5K chars.

## Permissions
Tools declare an effect: READ, WRITE, DESTRUCTIVE. Destructive calls (`memory.forget`, `reminders.delete`,
`places.delete`) pause the turn and show Allow / Always allow / Deny in chat; a denied call returns an error the
model must report. Always-allow rules live in Settings → Permissions. Evals auto-approve; scheduled jobs deny.

## Tool results
Every tool returns a `summary` sentence in human form (dates as "Sun 26 Oct, 9:01 PM", amounts with Indian
grouping, durations as "10 min") plus the structured data. The model sees the summary first and copies it,
instead of rewriting raw timestamps. `util/Fmt` is pure Kotlin and unit-tested.

## Tool routing
`ToolRouter` picks 3–6 tools per turn from keyword groups (calls, sms, gmail, docs, web, device, reminders,
places, memory); `memory.recall` is always included; no match → all tools. The system prompt holds only the
protocol; the chosen catalog travels with each user message, so the cached prefix stays small. The chat trace
shows `router: offered n/N`.

## Eval
- `assets/golden.json`: 32 synthetic cases across calls, sms, gmail, docs, sheets, web, device, reminders, places,
  memory, general. Every tool call is answered from the case's `mocks`, so runs are deterministic and need no
  permissions or network. Real data never goes in this file.
- Run from the app (Models → Run eval) or from a computer: `scripts/eval.sh [model-id]`.
- Output: `eval/<model>-<stamp>.csv` (per-case metrics) and `.txt` (summary + failures) under the app's files dir.
- Metrics: first tool correct, args match, trajectory match, calls to tools not offered, reply contains, numbers
  grounded in prompt/tool output, time to first token, total latency, load time, PSS, battery.
- `EvalMetrics` is pure Kotlin and unit-tested (`./gradlew testDebugUnitTest`, also run in CI before the APK build).
- Record the baseline score in a PR before changing the router, prompts or model.

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
