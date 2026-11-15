# Decisions

"Confirmed" = decided by the project owner. "Implementation decisions" = small, reversible
choices made during implementation.

## Confirmed (specification, stage 1)

1. Kotlin, Jetpack Compose (Material 3), OkHttp; native, open source; no Google, Firebase or
   analytics libraries.
2. No voice control and no vault in V1.
3. Chats as files in the project folder; folder structure as in DATA-FORMAT.md.
4. Model choice is global in Settings; the answering model is stored per answer.
5. Plain HTTP to the Tiiny accepted. No `FLAG_SECURE`.
6. Attachments are stored in the project/chat; storing them does not make them model context.
7. The unlock password is entered every time and never stored.
8. `reasoning_content` is not stored.
9. USB before Wi-Fi; API address configurable, default `http://172.19.178.109/v1`; no silent
   switch between USB and Wi-Fi; Diagnostics shows the path actually used.
10. Plain layout with system colours so the system dark mode applies.

## Confirmed (0.3)

- Projects are deleted permanently (no trash), with checks and confirmation.
- Project settings in `project.json` version 2.
- UI in English with as many icons as possible; app name "Tiiny Chat"; logo from dev.tiiny.ai.
- Start in the ad-hoc chat; side menu with projects and settings.
- Project instructions take precedence over app instructions where they conflict.
- (Web search through an own SearXNG instance – replaced in 0.4.)

## Confirmed (0.4)

- Web search directly on the search engine; default "Automatic", choice of DuckDuckGo, Bing,
  Brave, Yahoo. No WebView. SearXNG removed.
- Ad-hoc chats may be saved explicitly into a project; they then have no project instructions.
- No "Not saved" hint any more; all chats on the start screen are ad-hoc and not stored, as
  documented.
- A project can only be deleted when it is empty (delete documents first, "Select all").
- Formats: text, Markdown, CSV, JSON, code, PDF via Android text extraction (API 35), DOCX with
  an own parser, no OCR. Images only if the model reports vision.
- Document limit: half the context size, otherwise project value, otherwise app setting,
  default 100'000 tokens.
- `chat.json` version 2 with the document selection per chat.
- Model answers rendered as standard Markdown, without elaborate interpretation.

## Confirmed (1.0)

- Version 1.0: clean up, production-ready release, repository
  [Jenziner/tiiny-chat](https://github.com/Jenziner/tiiny-chat) (public).
- Only one new function: answers continue when the phone locks or the user switches apps.
- License: **PolyForm Noncommercial 1.0.0**.
- The Tiiny logo stays; the owner is in contact with the manufacturer and intends to hand the
  code over later.
- Documentation and README in English.

## Implementation decisions (stage 1)

| Decision | Reason |
|---|---|
| Package and applicationId `io.github.jenziner.nisaba`; debug build with suffix `.debug` | GitHub account Jenziner; debug and release installable side by side |
| minSdk 34, compileSdk/targetSdk 37 | Pixel 8 Pro with current GrapheneOS |
| Empty chats are not created | no orphaned folders; the snapshot is taken on the first send anyway |
| Safe replace via tmp/bak and rename, with read-back | SAF offers no guaranteed atomic replace |
| State check via sha256 on every replace | no blind overwrite after changes outside the app |
| Strict reading: unknown fields = damaged | an older app never overwrites data of a newer one |
| SOUL "empty" = whitespace only; content stored unchanged | unambiguous and lossless |
| At most one `system` message | parts stay separate, no invented roles; `system` on the Tiiny unconfirmed |
| Only the last complete answer per user message in the context | no failed or partial answers in the context |
| Errors without content are not stored as messages | clean history; retry without duplicates |
| Setting "General app instructions", empty | separates app and project instructions |
| Title: at most 60 characters, word boundary, "…" | local, no model request |
| Project folder name from the name, umlauts transliterated, max. 40 characters, `-2` on collision | readable in a file manager |
| App stores only the URI of the folder grant | reuse after restart; no project data on the phone |
| `allowBackup=false`, no data extraction | no copy of the settings in cloud backups |
| Storage test works only in its own folder `nisaba-probe-<time>` | existing data is never changed |

## Implementation decisions (stage 2)

| Decision | Reason |
|---|---|
| Network path USB (default) / System; USB binds sockets and DNS to the chosen network | USB before Wi-Fi without silent switching |
| API key encrypted with an AES-GCM key from the Android Keystore | never in plain text, never in project files; UI shows only "stored yes/no" |
| `retryOnConnectionFailure(false)` | no duplicate requests |
| Read timeout 11 min | the Tiiny's abort after about 10 min (third-party source) should arrive as a server answer |
| Streaming off by default | only `stream:false` was confirmed on the device at the time |
| Plain HTTP allowed globally (`network_security_config`) | the Tiiny speaks HTTP only; the address is configurable |
| Exactly one model available and none chosen → chosen automatically | no needless steps |
| Creating the chat and saving the history are not cancellable | "Stop" must not leave a half-saved state |
| New project chat never sent is discarded when leaving | matches "empty chats are not created" |
| General app instructions also apply to the ad-hoc chat | ad-hoc gets no project instructions, but the global ones |
| Navigation without a library | plain, no further dependencies |
| Storage check every 3 s in an open chat | reconnect without user action |
| Keep target SDK 37 and request `ACCESS_LOCAL_NETWORK` | Android 17 requires it for target SDK 37; falling back to 36 would only be temporary |
| Without the permission no request to local addresses; clear message | no 10 s timeout, clear cause |
| Diagnostics comparison on `device.json`, port 39218 | given by the Tiiny itself, needs no key |

## Implementation decisions (0.3)

- Web search results and sources only in memory, never in storage.
- Reasoning "model default" sends no fields; without a reported capability nothing is sent.
- Deletion is blocked for any content that cannot be attributed unambiguously.
- Deletion is confirmed by typing the project name.
- Connections without keep-alive; one retry only for `GET /models`.
- Package id stays `io.github.jenziner.nisaba`, so updates over earlier builds work.

## Implementation decisions (0.4)

- **jsoup 1.24.1 (MIT)** as the only new dependency (`core` only), to read HTML result pages
  robustly.
- Google is not offered: it requires JavaScript; "Automatic" then falls back to DuckDuckGo.
- "Automatic" uses the factory search engine of the default browser (Vanadium → DuckDuckGo,
  Brave → Brave, Edge → Bing, DuckDuckGo browser → DuckDuckGo, otherwise DuckDuckGo).
- Mobile Chrome user agent and system languages as `Accept-Language`.
- Upload limit 25 MB per file, DOCX XML at most 50 MB unpacked.
- Text files strictly as UTF-8, otherwise windows-1252; NUL bytes count as binary.
- Token estimate characters / 3; rough.
- Document text is never stored, only cached in memory (32 entries).
- Unreadable files are rejected on upload instead of stored.
- Saving an ad-hoc chat is blocked while an answer is running.
- Links in answers are clickable only for http, https and mailto.

## Implementation decisions (1.0)

| Decision | Reason |
|---|---|
| Foreground service type `dataSync`, started only from Send/Retry while the app is visible, stopped when no request runs | fits "user-started data transfer"; no permanent background activity |
| Partial wake lock, at most 60 min, only while the service runs | keeps reading the stream with the screen off; bounded |
| Notification channel "Running answers", importance low (silent), with Stop | required for a foreground service; no sound |
| `POST_NOTIFICATIONS` asked once per app start on the first send; answers continue if denied | no prompt at app start; feature works without it |
| No request for "Unrestricted" battery; only status and link in Settings → Background | a foreground service should suffice; avoid needless exemptions |
| No "answer done" notification | not requested; keep scope to one function |
| Release build with R8 and resource shrinking (APK ~3 MB instead of ~32 MB) | production build |
| Signing only from environment variables; GitHub Actions with repository secrets | no secrets in the repository |
| versionCode 100 for 1.0.0 | clear jump from 0.x (versionCode 6) |
| Code name *Nisaba* kept in package and file formats | existing folders and data stay compatible |
| Unused icons removed | clean-up |
| Historical German test reports moved to `docs/archive/` | English documentation; history preserved |
| Wi-Fi address in documentation replaced by an example | public repository |
