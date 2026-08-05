package com.smali_generator.patches;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private static final String SAVE_SUBDIR =
            "Android/data/com.whatsapp/files/.once_media";

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
            // Common WhatsApp message columns (present across many versions).
            // We request only what we need; missing columns are handled below.
            cursor = db.query(
                    "message",
                    new String[]{"key_remote_jid", "key_from_me",
                            "timestamp", "data", "media_name"},
                    whereClause,
                    whereArgs,
                    null, null,
                    "timestamp ASC"
            );

            if (cursor == null || cursor.getCount() == 0) return;

            StringBuilder sb = new StringBuilder();
            String chatJid = null;

            // Column indices — guard against missing columns
            int colJid       = safeColumn(cursor, "key_remote_jid");
            int colFromMe    = safeColumn(cursor, "key_from_me");
            int colTimestamp = safeColumn(cursor, "timestamp");
            int colData      = safeColumn(cursor, "data");
            int colMedia     = safeColumn(cursor, "media_name");

            SimpleDateFormat sdf =
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

            while (cursor.moveToNext()) {
                String jid      = colJid       >= 0 ? cursor.getString(colJid)       : "?";
                int    fromMe   = colFromMe    >= 0 ? cursor.getInt(colFromMe)        : 0;
                long   ts       = colTimestamp >= 0 ? cursor.getLong(colTimestamp)    : 0;
                String text     = colData      >= 0 ? cursor.getString(colData)       : null;
                String media    = colMedia     >= 0 ? cursor.getString(colMedia)      : null;

                if (chatJid == null && jid != null) chatJid = jid;

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

            writeToFile(sb.toString(), chatJid);

        } catch (Exception e) {
            Log.e(TAG, "DeletedMessageSaver: query error: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** Returns a column index or -1 if the column doesn't exist. */
    static int safeColumn(Cursor cursor, String name) {
        try {
            return cursor.getColumnIndexOrThrow(name);
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** Appends or creates a text file in the hidden folder. */
    static void writeToFile(String content, String chatJid) {
        try {
            File sdcard = Environment.getExternalStorageDirectory();
            File saveDir = new File(sdcard, SAVE_SUBDIR);
            if (!saveDir.exists()) {
                saveDir.mkdirs();
                new File(saveDir, ".nomedia").createNewFile();
            }

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
