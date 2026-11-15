# Test report 1.0.0 (2026-11-15)

Environment: Linux sandbox, JDK 21, Gradle 9.9.1, Android SDK 37. No Android device, no
emulator, no connection to a Tiiny.

A successful build and passing tests against simulated storage, a simulated server and saved
search pages are **not** proof of the behaviour on a phone, with a Tiiny, or with the search
engines from the user's network.

## 1. Passed automatically

`./gradlew clean :core:test :app:assembleDebug :app:assembleRelease :app:lintRelease`
→ BUILD SUCCESSFUL, **109 tests, 0 failures, 0 skipped**.

| Group | Content |
|---|---|
| A | project storage: store identity, projects, safe replace, strict reading, format versions |
| B | SOUL.md: four read cases, snapshot on first send, editor with state check |
| C | storage loss and reconnect: pending, conflict, wrong store, "written but not confirmed" |
| D | ad-hoc chat: memory only, discard confirmation, explicit save into a project |
| E | further functions: titles, folder names, context estimate, retry without duplicates |
| F | 0.3: project settings, reasoning, project deletion rules, connection handling |
| G | 0.4: format migration, documents, text extraction, document limit |
| H | 0.4: HTML search for DuckDuckGo/Bing/Brave/Yahoo (saved pages), redirects, captcha, Markdown, H10 body never read on the calling thread |
| API, probe | OpenAI wire format, SSE stream, error mapping; storage test |

The 1.0 background service is Android-only and has **no automated test**; it is covered by
compilation, lint and the manual checks below.

Lint (release): 0 errors; 7 warnings (`InsecureBaseConfiguration` – plain HTTP is intended for
the Tiiny; 6 × `UseKtx` style suggestions).

## 2. Checked manually in the build environment

- Release APK (unsigned build) with `aapt2`: package `io.github.jenziner.nisaba`, versionCode
  100, versionName 1.0.0, about 3.2 MB. Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`,
  `ACCESS_LOCAL_NETWORK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `POST_NOTIFICATIONS`, `WAKE_LOCK` (plus the internal AndroidX receiver permission). Service
  `AnswerService`, `exported=false`, `foregroundServiceType=dataSync`.
- Signing configuration: a release build with a throw-away test key (deleted afterwards) was
  signed and verified with `apksigner`. That APK was not distributed.
- R8 mapping contains the generated kotlinx.serialization serializers.
- Debug APK `tiiny-chat-1.0.0-debug.apk`: package `io.github.jenziner.nisaba.debug`,
  versionCode 100, same debug certificate as 0.4.1 (SHA-256 `ea42c05c…f5f6c6`) → installs as an
  update over 0.4.1.
- The UI was **not** displayed or operated (no emulator).

## 3. Still to be checked on the device

- Debug 1.0.0 over 0.4.1: settings and data kept; notification prompt on the first send.
- Background: send a long question, lock the screen / switch apps; the notification appears,
  the answer completes and is saved; "Stop" in the notification; the notification disappears
  at the end.
- Whether USB path binding and the local network permission keep working with the screen off
  (expected, **confidence about 80 %**).
- Signed release APK built by GitHub Actions: installs next to the debug app, runs identically
  after R8 (confidence about 85 %), setup from scratch.
- Still open from 0.4: web search results from the user's network, documents (PDF, DOCX),
  Markdown details, project deletion with an empty test project.

## 4. Blocked or not implemented

- Tests with the real Tiiny from the sandbox (not reachable).
- Search engines from the user's network (different IP, captcha behaviour may differ).
- Images (vision), OCR, unlocking the Tiiny (not implemented).
- Automated UI tests of the app layer (only compilation, lint and code review).
