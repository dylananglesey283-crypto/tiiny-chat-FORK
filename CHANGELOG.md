# Changelog

## Unreleased

- **Android TV:** launcher entry and banner, remote-friendly focus rings, Menu button opens the
  drawer, TV safe-area margins. App storage (plain folder) for TVs without a folder picker.
  Missing pickers no longer crash the app. See [docs/TV.md](docs/TV.md). Not yet tested on a TV.

## 1.0.0 – 2026-11-15

- **Background answers:** a running answer (including web search and saving) continues when
  the screen locks or you switch apps. While it runs, a silent notification "Answer in
  progress…" is shown; tap opens the app, **Stop** cancels. Foreground service of type
  `dataSync` with a time-limited partial wake lock, active only while a request runs.
- New permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`
  (asked on the first send; optional), `WAKE_LOCK`.
- Settings → **Background**: status of notifications and battery use, link to App info.
- Diagnostics log shows when the background protection starts and stops.
- **Release build:** R8 minification and resource shrinking (APK about 3 MB), signing from
  environment variables, GitHub Actions for CI and signed releases.
- Release package `io.github.jenziner.nisaba` (without `.debug`); installs next to earlier test
  builds. File format unchanged.
- Clean-up: unused icons removed, Gradle project name `tiiny-chat`.
- License: PolyForm Noncommercial 1.0.0. Documentation and README in English.

## 0.4.1 – 2026-11-15

- **Fix:** crash on every web search (test in Settings and in chats, with a chosen engine as
  well as with "Automatic"). Cause: the engine's response was read on the main thread
  (`NetworkOnMainThreadException`, found on the device). Reading and parsing now always run in
  the background (`Dispatchers.IO`).
- New regression test H10: the response body is never read on the calling thread (verified to
  fail without the fix).

## 0.4.0 – 2026-11-15

- **Direct web search** on a search engine instead of SearXNG (SearXNG removed; a stored address
  is deleted on first start). Choice: Automatic (default), DuckDuckGo, Bing, Brave, Yahoo.
  "Automatic" detects the default browser and uses its factory engine (Vanadium → DuckDuckGo);
  Google cannot be used without a browser, then DuckDuckGo. The result page is read as HTML
  (jsoup), without WebView or JavaScript. "Test search" in Settings.
- **Ad-hoc chat:** "Not saved" hints removed. New icon "Save to project" moves the chat into a
  chosen project (without project instructions); the trash icon discards it (with confirmation).
- **Project documents:** upload with the paper clip in the chat's chip row (activates the
  document) or in project settings. Stored in the project's `files/`. Each document appears as a
  chip in the chat; the selection is per chat and stored in `chat.json`. Formats: text,
  Markdown, CSV, JSON, code, PDF (text layer, Android 15+), DOCX; no OCR, no images. At most
  25 MB per file.
- **Document limit:** half the context size if the model reports it; otherwise project value,
  otherwise app value (default 100'000 estimated tokens). If exceeded or a document is missing,
  nothing is sent and nothing is saved.
- **Project settings:** document list with size and token estimate, delete one by one or with
  "Select all", upload, project document limit. A project can only be deleted when it has no
  documents.
- **Markdown rendering** of model answers (headings, bold, italic, strikethrough, code, code
  blocks, lists, quotes, rules, tables, links). Own messages stay plain text. The raw text is
  stored.
- Formats: `project.json` version 3 (`documentLimitTokens`), `chat.json` version 2 (`origin`,
  `documents`). Older versions are read and rewritten only on change.
- New dependency: jsoup 1.24.1 (MIT) in `core`.

## 0.3.0 – 2026-11-15

- App renamed "Tiiny Chat" (package id unchanged `io.github.jenziner.nisaba`). App icon and
  wordmark from the Tiiny logo (dev.tiiny.ai), white on black.
- UI in English, more icons instead of text.
- Starts directly in the ad-hoc chat; "start over" discards it after confirmation.
- Side menu: all projects, new, reload, gear per project, Settings and Diagnostics; model status
  with refresh.
- Project settings: instructions (SOUL.md), reasoning, web search, delete project permanently
  (with checks and name confirmation).
- Reasoning: model default / off / on, level as reported by the model list; sent only if the
  model reports the capability (`thinking_enabled`, `reasoning_effort`).
- Web search through an own SearXNG instance (replaced in 0.4); results only in the request,
  sources shown below the answer (memory only).
- Precedence rule: project instructions before general app instructions.
- `project.json` format version 2 with `settings`; version 1 still read.
- Connection drops: no connection reuse, one retry of `GET /models`, automatic status check
  (60 s; after errors 5/10/20/40/60 s), re-check after network change, connection log in
  Diagnostics.

## 0.2.1-etappe2 – 2026-11-14

- Device finding: connections to the Tiiny timed out over USB and Wi-Fi while the browser
  reached it. Cause very likely Android 17's local network restriction for target SDK 37.
- New permission `ACCESS_LOCAL_NETWORK` with in-app request. Without it, no request goes to
  local addresses and the app says so instead of waiting 10 s for a timeout.
- Diagnostics: trace per request (path, addresses, socket, phases, HTTP status, header, start of
  body; key only as length). Comparison test without key on `device.json` (port 39218). Hints
  for EPERM (GrapheneOS "Network") and missing networks.

## 0.2.0-etappe2 – 2026-11-14

- UI: start screen, project view, editor for project instructions (SOUL.md), chat screen for
  project and ad-hoc chats, settings, diagnostics.
- System colours (Material You) and system dark mode.
- API key encrypted with the Android Keystore (AES-GCM).
- Network path: USB (default, no fallback to Wi-Fi) or system network.
- New permissions: `INTERNET`, `ACCESS_NETWORK_STATE`; plain HTTP allowed.
- `core`: creating the chat and saving the history are not cancellable (`NonCancellable`).

## 0.1.0-etappe1 – 2026-11-13

- Project skeleton: Gradle 9.9.1, AGP 9.5.1, Kotlin 2.4.21, modules `core` and `app`.
- `core`: storage interface, safe replace, file format version 1, project store, SOUL snapshot,
  chat session with storage loss and reconnect, ad-hoc chat, request builder, OpenAI-compatible
  protocol (OkHttp), storage test.
- `app`: folder picker with persistent grant, storage test, copy report.
- Documentation in `docs/`.
