# Architecture

## Principles

- The model server (Tiiny) and the project storage (the chosen folder) are separate roles with
  separate status displays and separate error types (`ApiError` versus `StorageError`).
- Project data lives only in the chosen folder. There is no database and no hidden copy on the
  phone. App-private storage holds only configuration (base URL, network path, model, streaming,
  general app instructions, search engine, document limit, URI of the folder grant) and the
  encrypted API key.
- The core logic (`core`) is pure Kotlin without Android dependencies and is tested on the JVM.
  The app (`app`) provides the Android bindings (SAF storage, network paths, key vault,
  background service) and the UI.

## Modules and packages

| Package (`io.github.jenziner.nisaba…`) | Content |
|---|---|
| `core.storage` | `StorageBackend` (minimal file interface without overwrite), `RelPath`, `StorageError`, `SafeReplace` |
| `core.format` | file kinds, `Codec` (strict JSON), `decodeUtf8Strict` |
| `core.store` | `ProjectStore`: store identity, projects, SOUL.md, chats, documents, identity checks, project deletion |
| `core.chat` | `ChatSession` (state machine for project and ad-hoc chats), `RequestBuilder`, `ContextEstimate`, `ReasoningResolver`, `DocumentBudget` |
| `core.api` | `ChatApi`, `TiinyHttpApi` (OkHttp), `OpenAiWire` (request, response, SSE stream, error mapping) |
| `core.search` | `SearchEngine`, `HtmlSearch` (result pages of DuckDuckGo, Bing, Brave, Yahoo with jsoup), `SearchQuery` |
| `core.docs` | `DocumentText` (text extraction for text/DOCX, PDF through the `PdfText` interface), token estimate |
| `core.markdown` | `Markdown` (own parser for standard Markdown → blocks and inline elements) |
| `core.diagnostics` | `StorageProbe` (storage test in its own probe folder) |
| `core` | `Env` (clock, IDs, time zone), `Titles`, `FolderNames` |
| `app.storage` | `SafStorageBackend` (DocumentsContract) |
| `app.settings` | `SettingsRepository` (SharedPreferences `einstellungen`), `KeyVault` (Android Keystore, AES-GCM, alias `nisaba-api-schluessel`) |
| `app.net` | `NetworkPaths` (list networks, choose path), `ConfiguredApi` (binds every request to the chosen path), `LocalNetworkAccess`, `ConnectionLog` |
| `app.search` | `EngineChoices` (search engine from setting or default browser) |
| `app.docs` | `Documents` (upload, in-memory cache, source per project), `AndroidPdfText` |
| `app.background` | `AnswerService` (foreground service while requests run, 1.0) |
| `app` | `NisabaApp` / `AppGraph` (process-wide state, open sessions, model status), `MainActivity` (navigation stack) |
| `app.ui` | screens Home (ad-hoc chat + drawer), Project, Project settings, SOUL, Chat, Settings, Diagnostics; `NisabaTheme` (system colours) |

Internal identifiers (preference keys, key alias, file format names) partly use German words or
the code name *Nisaba*. They are kept unchanged so that existing data stays readable.

## Storage interface

`StorageBackend` only knows: check root, list, stat, read, create a **new** file, create folder,
rename (target must not exist), delete. There is deliberately no operation that overwrites
existing content. Replacing goes through `SafeReplace` only (see DATA-FORMAT.md).

`SafStorageBackend` resolves paths by display name on every access (no cached document IDs),
reports a name changed by the provider (e.g. `x (1)`) as `NameChanged` and in that case removes
only the document it has just created itself.

## Chat session

`ChatSession` exposes the visible state (`SessionState`) as a `StateFlow`: messages with their
save status, the live answer, errors separated by model and storage, `canSend`,
`retryableUserMessage`, `discardNeedsConfirmation`. A send runs as follows:

1. Check whether sending is allowed (nothing running, nothing unsaved, storage OK, documents
   loadable and within the limit).
2. On the first send: create chat folder, SOUL snapshot, `chat.json`, `history.json`
   (revision 1). If a step fails, the folder is removed where possible and **nothing** is sent.
3. Otherwise save the user message in `history.json`. If that fails, it stays marked as pending
   and nothing is sent.
4. Optional web search; then build the request (`RequestBuilder`) and send it; show the answer
   live.
5. Save the answer. If the storage has gone away meanwhile, the answer is shown, marked as
   "not saved yet", and further sending is blocked (see SNAPSHOT-AND-RECONNECT.md).

Creating the chat and saving the history are not cancellable (`NonCancellable`), so "Stop" never
leaves a half-written state.

## Model request

- At most one `system` message: general app instructions (setting, empty by default), then
  `# Projektanweisungen` with the snapshot content, then `# Dokumente` (active documents), then
  `# Websuche` (search results). If app instructions exist, a sentence states that project
  instructions take precedence. Whether the Tiiny handles the `system` role as expected is
  **not yet verified**.
- Then the history in order. Per user message only the last **complete** answer is included;
  failed and incomplete answers are not.
- Nothing is ever truncated silently. The context estimate (characters / 3) warns from 80 % of
  `context_window` if the server reports that value.
- `reasoning_content` is shown live but not stored.
- `thinking_enabled` and `reasoning_effort` are sent only if set, and only if the model reports
  the capability (`ModelInfo.thinking`); nothing is guessed.
- The API key is set only as an `Authorization: Bearer …` header and never written to files or
  logs.

## Network

- **Path:** mode USB picks a network without Wi-Fi/cellular, preferring one whose address range
  contains the server, otherwise one with transport USB/Ethernet; sockets and DNS are bound to
  it. If it is missing, the request fails with a hint (no fallback). Mode System uses the default
  network. The path actually used is shown in Diagnostics.
- **Local network (Android 17):** with target SDK 37, `ACCESS_LOCAL_NETWORK` is required
  (`LocalNetworkAccess`). Without it, `ConfiguredApi` sends nothing to local addresses (private,
  link-local, `.local`, 100.64/10, fc00::/7) and reports this as a connection error.
- **HTTP:** connect timeout 10 s, read timeout 11 min (the Tiiny aborts after about 10 min
  according to a third-party source), no automatic retries (`retryOnConnectionFailure(false)`),
  no connection reuse (`ConnectionPool(0, 1 s)`). Only `GET /models` is retried once after 1 s.
- **Monitoring:** while the app is visible, `AppGraph.startMonitor` checks the model list every
  60 s, after errors at 5/10/20/40/60 s, and 1.5 s after a network change. `ConnectionLog` keeps
  the last 300 events (no key, no chat content).

## Web search

`HtmlSearch` fetches the result page (DuckDuckGo: `POST https://html.duckduckgo.com/html/`,
Bing: `GET https://www.bing.com/search?q=`, Brave: `GET https://search.brave.com/search?q=&source=web`,
Yahoo: `GET https://search.yahoo.com/search?p=`) with a mobile browser user agent and
`Accept-Language` from the system languages. Redirect links are resolved (DuckDuckGo `uddg`,
Bing `u=a1…` Base64, Yahoo `RU=`). Captcha pages, HTTP 429 and pages without readable hits count
as failures; then no model request is made. At most 5 results. The search uses
`ConfiguredApi.searchClient` (system network, no Authorization header). The response body is read
and parsed on `Dispatchers.IO` regardless of the calling thread (since 0.4.1).

"Automatic": `EngineChoices` asks for the default browser via
`resolveActivity(ACTION_VIEW https://…)` (manifest `<queries>`) and uses its factory search
engine. The engine configured inside the browser cannot be read by other apps.

## Documents

Original files are stored in `files/` (`addDocument` never replaces, it numbers `name (2).ext`).
The text is read and extracted again for every request (in-memory cache keyed by content hash,
never on disk). PDF uses `PdfRenderer.Page.getTextContents()` (API 35) with a temporary file in
the app cache that is deleted immediately. `ChatSession` loads the documents before saving the
user message; errors or exceeding the limit → `Rejected`, nothing saved, nothing sent. The
selection is stored in `chat.json` (`documents`), before the first send only in memory.

Document limit (`DocumentBudget.limit`): half of `context_window`, otherwise the project value,
otherwise the app value (100'000 estimated tokens).

## Markdown

`core.markdown.Markdown` parses the answer; `ui/MarkdownText` renders it with Compose
(AnnotatedString, links via `LinkAnnotation.Url`, only http/https/mailto). Incomplete markup
during streaming stays visible as text. The raw text is what gets stored.

## App layer: sessions and background (1.0)

- **Sessions:** `AppGraph` keeps open chat sessions in memory so a running answer survives
  screen changes. Sends run in the app scope (`SupervisorJob` on the main thread; I/O on
  `Dispatchers.IO`), not in the screen. At most one ad-hoc chat. A new project chat that was
  never sent is discarded when leaving it.
- **Background:** every user-started request (Send, Retry) is registered with
  `AppGraph.runProtected(job)`. This increments `AppGraph.running` and starts `AnswerService`
  while the app is visible (user action). The service:
  - runs as a foreground service of type `dataSync` with a silent notification
    ("Answer in progress…", tap opens the app, **Stop** cancels all running requests);
  - holds a partial wake lock (at most 60 min) so the response stream keeps being read with the
    screen off;
  - stops itself as soon as `running` drops to 0;
  - handles `onTimeout` (Android 15+, 6 h per 24 h for `dataSync`) by stopping; requests then
    continue without protection.

  Processes with a foreground service keep network access in Doze and are not treated as idle
  ([Android: Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)).
  Saving to the folder (SAF) needs no foreground state. If Android ends the process anyway
  (e.g. "Force stop"), running answers are lost; saved data stays.
- **Notifications:** `POST_NOTIFICATIONS` is requested once per app start on the first send. If
  denied, answers still continue in the background; only the notification is hidden.
- **Storage monitoring:** the chat screen checks the root folder every 3 s and reports
  reachability to the session; on reconnect the session saves by itself (identity checks, see
  SNAPSHOT-AND-RECONNECT.md).
- **Model status** (`Unknown`, `Checking`, `Ok`, `Err`) is separate from the storage status.
  Sending is blocked if no model is selected or the selected model is missing from the last
  loaded list. If none is selected and exactly one is available, it is selected.
- **Input:** the input field is cleared on send; if the message is not accepted or not saved,
  the text comes back (anything typed meanwhile is appended).
- **Stop** cancels the model request; the partial answer is saved as incomplete.

## Project deletion

`planProjectDeletion` → `deleteProject` (re-check, then recursive delete through the storage
provider); open sessions of the project are closed. See DATA-FORMAT.md for the rules.

## Not implemented

Unlocking the Tiiny, images (vision), OCR.
