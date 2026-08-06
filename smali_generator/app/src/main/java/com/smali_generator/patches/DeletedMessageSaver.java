package com.smali_generator.patches;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * DeletedMessageSaver
 *
 * Hooks SQLiteDatabase.delete() — the Android OS call WhatsApp uses to remove
 * rows from its message database — and saves the content of those messages to
 * a plain-text file before the deletion is executed.
 *
 * Covers three scenarios transparently (the user sees normal WhatsApp behavior):
 *  1. "Eliminar mensaje" — a single message deleted by the sender or locally.
 *  2. "Vaciar chat"     — all messages in a conversation wiped.
 *  3. "Eliminar chat"   — entire conversation removed.
 *
 * Output:  /sdcard/Android/data/com.whatsapp/files/.once_media/
 *          YYYYMMDD_HHmmss_<contact>.txt
 *
 * The folder already has a .nomedia file (created by ViewOnceSaver) so the
 * gallery never indexes it.
 *
 * Thread-safety: a ThreadLocal flag prevents re-entrant hook calls when we
 * query the database inside the hook itself.
 */
public class DeletedMessageSaver implements Hook {

    private static final String TAG = "PATCH";
    // Same visible folder ViewOnceSaver uses: inside WhatsApp's own media tree,
    // writable without special permissions on Android 11+ and reachable from
    // file managers / gallery. Keeps everything the patch rescues in one place.
    private static final String SAVE_SUBDIR =
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Once Media";

    // Prevent re-entrant calls: our own db.query() inside the hook must not
    // trigger the hook again.
    private static final ThreadLocal<Boolean> IN_HOOK =
            ThreadLocal.withInitial(() -> false);

    // ------------------------------------------------------------------
    // Hook: SQLiteDatabase.delete(String table, String where, String[] args)
    // ------------------------------------------------------------------

    /** Backup slot for ArtHooks — holds the original method pointer. */
    static int delete_hook_backup(SQLiteDatabase db, String table,
                                  String whereClause, String[] whereArgs) {
        return 0;
    }

    /** Replacement for SQLiteDatabase.delete(). */
    static int delete_hook(SQLiteDatabase db, String table,
                           String whereClause, String[] whereArgs) {
        try {
            if (!IN_HOOK.get() && isMessageTable(table)) {
                IN_HOOK.set(true);
                try {
                    saveMessages(db, whereClause, whereArgs);
                } finally {
                    IN_HOOK.set(false);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "DeletedMessageSaver: hook error: " + e.getMessage());
        }
        return delete_hook_backup(db, table, whereClause, whereArgs);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** WhatsApp's main message table is named "message" or "messages". */
    static boolean isMessageTable(String table) {
        return "message".equalsIgnoreCase(table)
                || "messages".equalsIgnoreCase(table);
    }

    /**
     * Queries the DB for the rows that are about to be deleted, formats them
     * as a human-readable text, and writes the result to the hidden folder.
     */
    static void saveMessages(SQLiteDatabase db, String whereClause,
                             String[] whereArgs) {
        Cursor cursor = null;
        try {
            // WhatsApp's schema changes between versions (older builds used
            // key_remote_jid/data/media_url on the "message" table; newer builds
            // use from_me/text_data and move the jid to a separate table). To be
            // version-proof we select ALL columns (null projection) and resolve
            // each field by trying several candidate column names below.
            cursor = db.query(
                    "message",
                    null,
                    whereClause,
                    whereArgs,
                    null, null,
                    null
            );

            if (cursor == null || cursor.getCount() == 0) return;

            StringBuilder sb = new StringBuilder();
            String chatJid = null;

            // Column indices — try several candidate names so we survive the
            // schema differences between WhatsApp versions.
            int colJid       = safeColumn(cursor, "key_remote_jid", "remote_jid", "chat_row_id");
            int colFromMe    = safeColumn(cursor, "key_from_me", "from_me");
            int colTimestamp = safeColumn(cursor, "timestamp", "received_timestamp");
            int colData      = safeColumn(cursor, "data", "text_data", "message_text");
            int colMedia     = safeColumn(cursor, "media_name", "file_path");
            int colMediaUrl  = safeColumn(cursor, "media_url", "file_path", "media_local_path");

            SimpleDateFormat sdf =
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

            // Resolve save directory once for the whole batch
            File sdcard  = Environment.getExternalStorageDirectory();
            File saveDir = new File(sdcard, SAVE_SUBDIR);
            if (!saveDir.exists()) {
                saveDir.mkdirs();
            }

            while (cursor.moveToNext()) {
                String jid       = colJid       >= 0 ? cursor.getString(colJid)       : "?";
                int    fromMe    = colFromMe    >= 0 ? cursor.getInt(colFromMe)        : 0;
                long   ts        = colTimestamp >= 0 ? cursor.getLong(colTimestamp)    : 0;
                String text      = colData      >= 0 ? cursor.getString(colData)       : null;
                String media     = colMedia     >= 0 ? cursor.getString(colMedia)      : null;
                String mediaUrl  = colMediaUrl  >= 0 ? cursor.getString(colMediaUrl)   : null;

                if (chatJid == null && jid != null) chatJid = jid;

                // Try to copy the media file before WhatsApp deletes it
                if (mediaUrl != null && !mediaUrl.isEmpty()) {
                    copyMediaFile(mediaUrl, saveDir, ts);
                }

                // Determine display content
                String content;
                if (text != null && !text.isEmpty()) {
                    content = text;
                } else if (media != null && !media.isEmpty()) {
                    content = "[media: " + media + "]";
                } else {
                    content = "[sin contenido]";
                }

                // Determine sender label
                String sender = (fromMe == 1) ? "Yo"
                        : (jid != null ? jid.split("@")[0] : "?");

                String dateStr = ts > 0 ? sdf.format(new Date(ts)) : "??:??:??";
                sb.append("[").append(dateStr).append("] ")
                  .append(sender).append(": ")
                  .append(content).append("\n");
            }

            if (sb.length() == 0) return;

            writeToFile(sb.toString(), chatJid, saveDir);

        } catch (Exception e) {
            Log.e(TAG, "DeletedMessageSaver: query error: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * Returns the index of the first column whose name matches one of the
     * candidates, or -1 if none exist. getColumnIndex() returns -1 (rather than
     * throwing) for missing columns, so we can try each candidate in order.
     */
    static int safeColumn(Cursor cursor, String... names) {
        for (String name : names) {
            int idx = cursor.getColumnIndex(name);
            if (idx >= 0) {
                return idx;
            }
        }
        return -1;
    }

    /**
     * Writes the text log to a new .txt file in saveDir.
     * saveDir is assumed to already exist (created in saveMessages).
     */
    static void writeToFile(String content, String chatJid, File saveDir) {
        try {
            // Use contact name as part of the filename (strip domain)
            String contact = "chat";
            if (chatJid != null && !chatJid.isEmpty()) {
                contact = chatJid.split("@")[0].replaceAll("[^a-zA-Z0-9_\\-+]", "_");
            }

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            File dest = new File(saveDir, ts + "_" + contact + ".txt");

            try (FileOutputStream out = new FileOutputStream(dest, false)) {
                out.write(content.getBytes("UTF-8"));
            }

            Log.i(TAG, "DeletedMessageSaver: saved → " + dest.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "DeletedMessageSaver: write error: " + e.getMessage());
        }
    }

    /**
     * Copies a media file referenced by mediaUrl (local filesystem path stored
     * in the WhatsApp DB) into saveDir.  The destination filename is prefixed
     * with a millisecond timestamp so multiple files never collide.
     *
     * Silently skips if the source does not exist or is not readable.
     */
    static void copyMediaFile(String mediaUrl, File saveDir, long messageTs) {
        try {
            // mediaUrl is usually an absolute path; strip "file://" if present
            String path = mediaUrl.startsWith("file://")
                    ? mediaUrl.substring(7) : mediaUrl;

            File src = new File(path);
            if (!src.exists() || !src.isFile() || !src.canRead()) {
                Log.w(TAG, "DeletedMessageSaver: media not found or unreadable: " + path);
                return;
            }

            // Build destination name: <ts>_<originalName>
            String prefix = (messageTs > 0)
                    ? new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(messageTs))
                    : new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dest = new File(saveDir, prefix + "_" + src.getName());

            // Copy bytes
            try (InputStream in  = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dest, false)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }

            Log.i(TAG, "DeletedMessageSaver: media saved → " + dest.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "DeletedMessageSaver: media copy error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Hook lifecycle
    // ------------------------------------------------------------------

    @Override
    public void load() {
        Log.i(TAG, "DeletedMessageSaver: loading patch");
        try {
            Method hookMethod = DeletedMessageSaver.class.getDeclaredMethod(
                    "delete_hook",
                    SQLiteDatabase.class, String.class, String.class, String[].class);
            Method backupMethod = DeletedMessageSaver.class.getDeclaredMethod(
                    "delete_hook_backup",
                    SQLiteDatabase.class, String.class, String.class, String[].class);
            Method originalDelete = SQLiteDatabase.class.getDeclaredMethod(
                    "delete", String.class, String.class, String[].class);

            ArtHooks.hook_function(originalDelete, hookMethod, backupMethod);
            Log.i(TAG, "DeletedMessageSaver: patch loaded OK");
        } catch (Exception e) {
            Log.e(TAG, "DeletedMessageSaver: load error: " + e.getMessage());
        }
    }

    @Override
    public void unload() {
        Log.i(TAG, "DeletedMessageSaver: patch unloaded");
    }
}
