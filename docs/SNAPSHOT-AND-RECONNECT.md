# Snapshot and reconnect logic

## SOUL.md snapshot

- SOUL.md (project instructions) is optional. Reading distinguishes four cases: present (not
  empty), empty (whitespace only), missing, **read error** (access, I/O, invalid UTF-8). A read
  error is never treated as "missing".
- The snapshot is taken on the **first send** of a chat, not when it is opened. Changes to
  SOUL.md before the first send therefore still apply; changes afterwards do not.
- Order on the first send: read SOUL.md → create chat folder → `soul-snapshot.json` →
  `chat.json` → `history.json` (revision 1 with the user message) → only then the model request.
  On a read error, or if a step cannot be saved, **nothing is sent** and the folder just created
  is removed where possible.
- The snapshot is never changed. A chat started without SOUL.md stays without project
  instructions even if SOUL.md is created later. Chats saved from an ad-hoc chat have a
  `missing` snapshot.
- The SOUL editor saves through safe replace with a state check (sha256): if SOUL.md was changed
  outside the app meanwhile, it does not save. After a read error, saving is blocked. Before
  saving it notes that changes apply to new chats only; existing chats keep their instructions.

## Storage loss during a project chat

1. A running answer keeps being shown and is received completely.
2. If it cannot be saved, it is marked "not saved yet" (`PersistState.Pending`) and the storage
   as unreachable.
3. Sending is blocked while anything is unsaved or the storage is missing. Typed text is kept.
4. If a **user message** cannot be saved, no request goes to the model. After reconnecting it is
   saved; the answer is then requested deliberately with "Request answer" (`retry`), not
   automatically.

## Reconnect

When the app reports the storage as reachable again (`setStorageReachable(true)`), the session
saves automatically (`reconcile`), but only after these checks:

| Check | on mismatch |
|---|---|
| `storeId` in `nisaba-store.json` = storage of the chat | `WrongStore`, nothing written |
| `projectId` and `chatId` in `chat.json` match | `Conflict`, nothing written |
| `history.json` matches the last state saved by the app (sha256) | `Conflict`, nothing written |

Special case "write succeeded, confirmation missing": if the storage already holds exactly the
revision that was to be written (same `chatId`, `revision` = last + 1, identical messages), it is
accepted as saved. This avoids duplicates.

Nothing is ever merged or overwritten blindly. A conflict stays visible as an error until the
user reopens the chat.

## Retry

- Errors without content are not stored as messages; an error hint with "Request answer" appears.
- Retry is possible only for the last unanswered user message and does not send it again as a
  new message (no duplicates).
- Stopped or incomplete answers are stored with `status: incomplete` and are not part of the
  context of later requests.

## Ad-hoc chat

Kept in memory only, without project instructions. Discarding a chat with content needs a
confirmation (`discardNeedsConfirmation`). Since 0.4 it can be saved explicitly into a project
(`ProjectStore.importAdHoc`): a new project chat with all messages, `origin: "ad-hoc"` and a
`missing` snapshot; the start screen then begins a new ad-hoc chat. Saving is blocked while an
answer is running.
