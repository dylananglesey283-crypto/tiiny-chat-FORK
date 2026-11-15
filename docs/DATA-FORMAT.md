# Folder structure and file format

Current versions (1.0): `project.json` version 3, `chat.json` version 2, all other files
version 1. Version 1.0 did not change the file format.

## Folder structure

```
<chosen folder>/
  nisaba-store.json                  store identity
  <project-slug>/
    project.json
    SOUL.md                          optional, Markdown, editable by the user
    skills/                          never changed by the app
    files/                           project documents (original files, since 0.4)
    chats/
      chat-YYYYMMDD-HHMM-xxxx/
        chat.json
        soul-snapshot.json           immutable
        history.json
        attachments/
```

- The project folder name (`project-slug`) is derived once from the project name: lower case,
  ä→ae, ö→oe, ü→ue, ß→ss, é/è→e, à→a, other characters → `-`, at most 40 characters, `-2`, `-3`
  etc. on collision. Projects cannot be renamed.
- The chat folder name is not the identity; `chatId` in the files is.
- Empty chats are not created; the chat folder is created on the first send.

## Common rules

- UTF-8, JSON indented with two spaces, trailing newline.
- Every JSON file has `format` (file kind) and `formatVersion`.
- IDs are UUIDs, timestamps ISO-8601 in UTC (e.g. `2026-11-13T13:05:00Z`).
- Read strictly: unknown fields, wrong file kind, another version or invalid UTF-8 count as
  **damaged or unsupported**. Such files are reported, not read, and **never overwritten**.

## File kinds

### nisaba-store.json
```json
{ "format": "nisaba-store", "formatVersion": 1, "storeId": "…", "createdAt": "…" }
```
Created only if missing and never replaced. `storeId` identifies the storage on reconnect.

### project.json
```json
{ "format": "nisaba-project", "formatVersion": 3, "projectId": "…", "name": "…", "createdAt": "…",
  "settings": { "reasoning": { "mode": "model-default", "effort": null }, "webSearch": false,
                "documentLimitTokens": null } }
```
- `reasoning.mode`: `model-default` (send nothing), `off`, `on`. `effort`: a level reported by
  the model or `null`. `webSearch`: web search for chats of this project (engine from the app
  setting).
- `documentLimitTokens` (since version 3): maximum of active documents per request in estimated
  tokens; `null` = app setting. Applies only if the model does not report a context size;
  otherwise half the context size applies.
- Versions 1 (without `settings`) and 2 (without `documentLimitTokens`) are still read. The file
  is rewritten to version 3 only when project settings are saved (safe replace with conflict
  check). New projects are created as version 3.
- Version 0.3 of the app does not read version 3 (reports "newer format version") and does not
  overwrite it.

### chat.json
```json
{ "format": "nisaba-chat", "formatVersion": 2, "chatId": "…", "projectId": "…",
  "title": "…", "titleSource": "auto", "createdAt": "…",
  "origin": "project", "documents": ["Report.pdf", "notes.md"] }
```
- `titleSource`: `auto` (locally from the first message) or `manual`.
- `origin` (since version 2): `project` (started in the project) or `ad-hoc` (saved from an
  ad-hoc chat; then `soul-snapshot.json` is `missing` and the chat has no project instructions).
- `documents` (since version 2): file names in `files/` that are active for this chat, in
  selection order. If a file has gone missing, the chat shows it as "missing" and nothing is
  sent until it is deselected.
- Version 1 is read (`origin` = `project`, no documents) and rewritten to version 2 only when the
  selection or the title changes.

### files/ (project documents, since 0.4)
- Original files, unchanged, as uploaded. Extracted text is not stored.
- File name from the display name, cleaned (last path segment only; without `: * ? " < > |`,
  control characters and leading dots; at most 120 characters, extension kept). If the name
  exists, nothing is replaced; `name (2).ext`, `name (3).ext` etc. is created.
- At most 25 MB per file. Unreadable files (no text extractable) are rejected, not stored.
- Deletion only from project settings, file by file (multi-select deletes one after another).

### soul-snapshot.json
```json
{ "format": "nisaba-soul-snapshot", "formatVersion": 1, "chatId": "…", "takenAt": "…",
  "state": "present", "content": "…" }
```
`state`: `present` (exists, not empty), `empty` (exists, empty or whitespace only), `missing`
(does not exist; `content` is then `null`). `content` is stored unchanged, also for `empty`.
Created once and never changed.

### history.json
```json
{ "format": "nisaba-history", "formatVersion": 1, "chatId": "…", "revision": 3,
  "messages": [
    { "id": "…", "role": "user", "content": "…", "createdAt": "…",
      "replyTo": null, "status": "complete", "model": null },
    { "id": "…", "role": "assistant", "content": "…", "createdAt": "…",
      "replyTo": "<id of the user message>", "status": "complete", "model": "…" }
  ] }
```
- `role`: `user` or `assistant`. No other roles are invented.
- `status`: `complete`, `incomplete` (stopped, token limit, stream without end), `failed`
  (defined in the format; errors without content are currently not stored as messages).
- `model`: the model reported by the server, otherwise the requested one.
- `revision` increases by 1 with every successful write.
- `reasoning_content` is not stored.

## Safe replace

Existing files are never overwritten in place. For `name`:

1. Read the current content; its sha256 must match the last known state, otherwise conflict.
2. Create the new content as `name.tmp-<op>` and read it back byte for byte.
3. Rename `name` → `name.bak-<op>`.
4. Rename `name.tmp-<op>` → `name`.
5. Delete `name.bak-<op>`.

At every point in time one complete, verified version exists. On reading: if `name` is missing
and there is exactly one matching `bak`/`tmp` pair, `tmp` is renamed into place (`bak` stays).
Any other situation counts as **inconsistent**; nothing is changed and an error is reported.
Atomic renames are **not** assumed or claimed.

## Deleting a project (since 0.3)

Before deleting, the app checks the project folder. It deletes only if the folder contains
nothing but `project.json`, optionally `SOUL.md`, empty folders `files/` and `skills/`, and chat
folders with readable `chat.json`, `history.json`, `soul-snapshot.json`, an empty `attachments/`
and temporary files (`.tmp-`, `.bak-`). Since 0.4 only a project without documents can be
deleted: if `files/` still contains files, the app reports their number and points to
Project settings → Documents. Anything else (unknown entries, unreadable chats) blocks the
deletion with a reason, so no foreign or protected files are ever deleted indirectly. Right
before deleting, the folder is checked again and the project identity compared.

Web search results, sources, reasoning output and extracted document text are never stored.
Ad-hoc chats are never stored unless explicitly saved into a project.

## Format changes

Later versions increase `formatVersion`. An app version reads only the versions it knows and
never overwrites newer ones.
