# Tiiny Chat – Android client for Tiiny

Tiiny Chat is a native Android client (Kotlin, Jetpack Compose) for chatting with the language
model on a Tiiny device over its local OpenAI-compatible API. It was built
as a personal client for a Google Pixel 8 Pro running GrapheneOS (Android 17).

**This is not an official Tiiny app.** It has no Google, Firebase or analytics libraries and
sends no telemetry.

**It is 100% AI generated. This app has not been checked for malicious content by a professional developer; use at your own risk!**

## Features

- **Ad-hoc chat** on the start screen (kept in memory only; can be saved into a project).
- **Projects** stored as plain files in a folder you choose (Android folder picker / SAF):
  project instructions (`SOUL.md`, snapshotted per chat), chats, documents. No database, no
  hidden copy on the phone.
- **Documents** per project (text, Markdown, CSV, JSON, code, PDF with a text layer, DOCX);
  switch them on or off per chat. A token limit prevents oversized requests.
- **Web search** directly on DuckDuckGo, Bing, Brave or Yahoo ("Automatic" follows the default
  browser), without WebView or JavaScript. Results go into the request only, never into storage.
- **Reasoning** setting (model default / off / on, effort level), sent only if the model reports
  the capability.
- **Markdown** rendering of model answers.
- **Background answers** (1.0): a running answer continues when the screen locks or you switch
  apps (foreground service with a notification and a Stop action).
- **Network path**: USB tethering to the Tiiny (default, no silent fallback) or the system network.
- **Diagnostics**: network paths, connection test, storage test, connection log (no key, no chat
  content).

## Status: 1.0.0

| Area | Status |
|---|---|
| `core` module (storage, file format, chat session, reconnect, API protocol, reasoning, web search, documents, Markdown) | implemented, automated tests (109) |
| Folder storage, model connection over USB/Wi-Fi, ad-hoc and project chats (0.2–0.3) | in use on the author's device |
| 0.4 features (web search, documents, save ad-hoc chat, Markdown) | in use on the author's device; not every function checked individually |
| 1.0 background answers, signed release build with R8 | implemented, built; **not yet verified on a device** |
| `thinking_enabled` / `reasoning_effort` on the Tiiny's port-80 API | not yet verified |
| Unlocking the Tiiny, images (vision), OCR | not implemented |

See [docs/TEST-REPORT-1.0.md](docs/TEST-REPORT-1.0.md) for what was tested and how.

## Install

Signed APKs are attached to [GitHub Releases](https://github.com/Jenziner/tiiny-chat/releases)
together with a SHA-256 checksum and the signing certificate fingerprint. Setup on the phone:
[docs/SETUP-PIXEL.md](docs/SETUP-PIXEL.md).

## Build

```sh
./gradlew :core:test :app:assembleDebug     # tests + debug APK
```

Details, requirements and the signed release build: [docs/BUILD.md](docs/BUILD.md),
[docs/RELEASE.md](docs/RELEASE.md).

## Repository layout

```
core/      pure Kotlin library (JVM), no Android dependency, with tests
app/       Android app (SAF storage, network paths, key vault, UI, background service)
docs/      documentation (docs/archive: historical German test reports)
.github/   CI (tests + debug build) and release workflow (signed APK)
```

The internal code name is **Nisaba** (package `io.github.jenziner.nisaba`, file format
identifiers `nisaba-*`). It is kept for compatibility with existing project folders.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Folder structure and file format](docs/DATA-FORMAT.md)
- [Snapshot and reconnect logic](docs/SNAPSHOT-AND-RECONNECT.md)
- [Setup on Pixel / GrapheneOS](docs/SETUP-PIXEL.md)
- [Android TV](docs/TV.md)
- [Build](docs/BUILD.md) and [Release signing](docs/RELEASE.md)
- [Decisions](docs/DECISIONS.md)
- [Test report 1.0](docs/TEST-REPORT-1.0.md)
- [Changelog](CHANGELOG.md)

## Known limitations

- **Ad-hoc chats** (start screen) are not stored. They are gone when discarded or when the app
  process ends, unless saved into a project.
- **Privacy:** the Tiiny itself may keep chat logs, independently of this app
  ([third-party notes](https://github.com/thezolon/tiiny-api-notes/blob/main/docs/PRIVACY.md)).
  Document text is sent in full with every request. Web search queries go to the chosen search
  engine.
- **Web search** reads the engine's HTML result page. If the engine changes its layout or asks
  for a captcha, the search fails with a message and nothing is sent to the model.
- **Documents:** token estimate is rough (characters / 3). PDF only with a text layer (no OCR);
  images are not supported yet.
- **Background answers:** Android limits this service type to 6 hours per 24 hours; after a
  "Force stop" or if Android ends the process anyway, a running answer is lost (saved data stays).
- Plain HTTP to the Tiiny is allowed on purpose (the device speaks HTTP only).
- The cause of occasional connection drops seen in 0.2/0.3 is not proven (see changelog).

## License

Source code: [PolyForm Noncommercial License 1.0.0](LICENSE.md) – free for any noncommercial
purpose; commercial use requires separate permission from the licensor.

The **Tiiny name and logo** (app icon, wordmark) belong to Tiiny AI and are **not** covered by
this license. They are used for this client with the intention of handing the project over to
the manufacturer.

Third-party components: OkHttp, kotlinx.coroutines, kotlinx.serialization, AndroidX / Jetpack
Compose (Apache License 2.0), jsoup (MIT), Material Symbols icons (Apache License 2.0).
