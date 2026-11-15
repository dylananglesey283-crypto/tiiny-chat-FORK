# Setup on Pixel / GrapheneOS

## Install

1. Download `tiiny-chat-<version>.apk` from
   [GitHub Releases](https://github.com/Jenziner/tiiny-chat/releases). Optionally compare the
   SHA-256 checksum (`.sha256` file) and the signing certificate shown in the release notes.
2. Open the APK on the phone and allow the installation. Later releases install as updates
   (same package, same signing key).
3. The release app (`io.github.jenziner.nisaba`) is a separate app from earlier test builds
   (`io.github.jenziner.nisaba.debug`). Both can be installed side by side. Data in the project
   folder is shared; settings, folder grant and API key are set up once in the release app.
   Afterwards the debug app can be uninstalled (its folder grant goes away, the folder content
   does not).

## Permissions

| Permission | Why | If missing |
|---|---|---|
| Network (GrapheneOS) | any network access | "socket failed: EPERM", no networks listed |
| Local network / Nearby devices (Android 17) | reaching the Tiiny over USB or Wi-Fi | no request goes to local addresses; the app asks with "Allow" |
| Notifications | notification while an answer runs in the background (1.0) | answers still continue, only the notification is hidden |
| Folder access | only the folder chosen in Settings (Android folder picker) | no projects |

No access to other storage, contacts, location, camera or microphone.

Battery: the default ("Optimized") is normally sufficient because a running answer uses a
foreground service. If answers still stop with the screen locked, set App info → Battery to
"Unrestricted". Settings → Background shows the current state.

## Settings

- **Project storage:** choose a folder with the Android folder picker; then set up the storage
  from the side menu. This creates only `nisaba-store.json`.
- **Model server / base URL:** default `http://172.19.178.109/v1` (Tiiny over USB). Over Wi-Fi
  use the Tiiny's address in your network, e.g. `http://192.168.1.50/v1`.
- **Network path:** "USB connection" (default, never falls back to Wi-Fi) or "System network".
- **API key:** enter it in the password field and save. It is stored encrypted and never shown
  again.
- **Model:** load the models, then choose one.
- **Answers:** streaming off by default.
- **Background:** status of notifications and battery use; link to App info.
- **Ad-hoc chat:** reasoning and web search for the ad-hoc chat. Projects have their own values
  (side menu → gear icon next to the project).
- **Web search:** "Automatic" (default) or DuckDuckGo, Bing, Brave, Yahoo. "Automatic" shows
  which engine is used and why. "Test search" checks it. Searches go over Wi-Fi or cellular
  directly to the engine, not over USB.
- **Documents:** default document limit in tokens (100'000, roughly 300–400 kB of text); applies
  only if the model does not report a context size.

## Ad-hoc chats and documents

- All chats on the start screen are ad-hoc chats and are **not stored**. The trash icon or the
  end of the app process discards them. The save icon moves the chat into a project of your
  choice (without project instructions).
- Documents exist only in project chats: the paper clip at the left of the chip row uploads and
  activates a document; the chips switch documents on and off for this chat. Delete documents
  permanently in Project settings → Documents.

## Background answers (1.0)

After sending, you can lock the screen or switch apps. A notification "Answer in progress…"
appears while the answer is generated; tapping it opens the app, **Stop** cancels the request
(the partial answer is kept as incomplete). The notification disappears when the answer is done.

## Diagnostics

Side menu → "Diagnostics": networks, chosen path, test of `GET /models` with duration, storage
test, connection log ("Copy log"), "Copy report" (without API key). The log also shows when the
background protection starts and stops. After a connection problem, please copy the log.

## After restarting the Tiiny

If the server answers with HTTP 502, the Tiiny must be unlocked first (not implemented in Tiiny
Chat yet; e.g. with the Tiiny desktop app).
