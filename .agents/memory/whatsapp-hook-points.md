---
name: WhatsApp patch hook points (view-once & deleted messages)
description: Real runtime facts about where modern WhatsApp stores view-once media and how it deletes rows, discovered from on-device logcat.
---

# WhatsApp hook points (discovered from device logcat, Aug 2026 build)

Confirmed by running the patched APK on a real device and capturing `adb logcat -s PATCH`.

## View-once media (ViewOnceSaver)
- The hook on `java.io.File.delete()` **does** fire on this build. Interception via `File.delete()` is valid — no need to move to protobuf-level interception.
- Received view-once media lives at:
  `/data/user/0/com.whatsapp/files/ViewOnce/<IMG-...>.jpg`
  It is NOT under the classic `/Private/` or `/Sent/` media folders, so detection must match `/ViewOnce/` (case-insensitive).
- `/data/user/0/com.whatsapp/files/.Shared/...` also gets deleted but is normal shared media, not view-once — do not blanket-save it.
- **Why:** the original patch matched only `/Private/` and `/Sent/`, so it silently saved nothing on this build.

## Deleted / revoked messages (DeletedMessageSaver)
- The hook on `android.database.sqlite.SQLiteDatabase.delete(table, where, args)` **does** fire; WhatsApp deletes with `whereClause = "_id=?"`.
- The `message` table schema changed: **no `key_remote_jid`, no `data`, no `media_url`**. A projection naming those columns throws `no such column`. Modern columns include `from_me`, `text_data`, `timestamp`; the jid moved out of `message` (reachable via `chat_row_id` -> chat -> jid).
- **How to apply:** query with a `null` projection (SELECT *) and resolve each field by trying multiple candidate column names; never hardcode a projection list against WhatsApp's message table.

## DecryptProtobuf note
- `DecryptProtobuf` loads and hooks the protobuf parse of every incoming message, but its `handle_view_once`/`handle_delete_message` were intentionally emptied in favor of the File.delete + SQLite hooks (which are confirmed working). `FMessage.init()` fails with `com.whatsapp.jid.DeviceJid` not found — that class name is hardcoded and stale on this build, but our two savers don't depend on FMessage.

## Save location convention
- Both savers write to `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Once Media` (external, app-writable without permission on Android 11+, gallery-visible, no `.nomedia`). User requirement: WhatsApp UI stays natural; media/messages just get copied to this one folder.
