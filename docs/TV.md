# Android TV

The app installs and starts on Android TV / Google TV (it also still runs on phones).

## What changed for TV

- **Launcher:** the app appears on the TV home screen (`LEANBACK_LAUNCHER`, banner
  `drawable/tv_banner`). Touchscreen and leanback are declared optional.
- **Remote control:** every clickable row shows a visible focus ring; the **Menu** button opens
  the project drawer on the start screen. **Back** goes back as before.
- **Dashboard:** on a TV the app opens on a dashboard instead of the chat: cards for model,
  network, storage and activity, tiles for Chat / New chat / Check connection / Settings /
  Diagnostics, and the project list. Back from the chat returns to it.
- **Safe area:** on a TV the content keeps a margin (48 dp / 27 dp) from the screen edge.
- **Storage:** most TVs have no folder picker, so *Settings → Project storage → Use app storage*
  keeps projects in the app's own folder (`Android/data/<package>/files/projects`). Uses the same
  file format as a normal project folder. Removing the app removes the data.
- **Pickers:** if a device has no folder or file picker the app shows a message instead of
  crashing (document upload then isn't possible on that device).

## Network

The Tiiny is reached over USB tethering by default, which a TV usually can't do. On a TV choose
*Settings → Network path → System network* (Wi-Fi / Ethernet) and set the base address of the
Tiiny.

## Not verified

Not built or run on a real TV or emulator yet. Please check on an Android TV emulator or device:
focus order in chat (input field, send button, menu), typing with the on-screen keyboard, and
the banner.

## Finding and waking the Tiiny

- **Find Tiiny** (dashboard tile, also in Settings → Model server): probes the other 253 addresses
  of the TV's own /24 network with a single `GET /v1/models` on port 80 (short timeouts, no API
  key sent) and lists hosts that answer like an OpenAI-compatible server. Selecting one sets the
  base address and switches to the system network. Needs the local network permission on
  Android 17.
- **Wake Tiiny** (dashboard tile): sends a Wake-on-LAN magic packet (UDP broadcast, ports 9 and 7)
  to the MAC address saved under Find Tiiny, then checks the connection for about a minute.
  Android does not let apps read other devices' MAC addresses, so you enter it once (shown on the
  Tiiny or in your router's device list).
- Limits: only works if the Tiiny supports Wake-on-LAN, has it switched on, and is on the same
  network, usually over Ethernet. Only /24 networks are scanned. Not tested with a real Tiiny.

## Getting it onto a TV

1. On GitHub: **Actions → CI → Run workflow**, pick the branch, then download the `debug-apk`
   artifact from the finished run (unzip it to get `app-debug.apk`).
2. Install it on the TV by one of these:
   - **Downloader app** (from the TV's app store): enter a link to the APK and install. Allow
     "install unknown apps" for Downloader when asked.
   - **ADB over Wi-Fi:** enable developer options and network debugging on the TV, then
     `adb connect <tv-ip>:5555` and `adb install app-debug.apk`.
   - **USB stick** with a file manager app on the TV.
