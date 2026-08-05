package com.smali_generator.patches;

import android.os.Environment;
import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ViewOnceSaver
 *
 * Hooks File.delete() to detect when WhatsApp is about to erase a view-once
 * media file (images / videos stored in the "Private" folder).
 * Before the deletion happens, the file is copied to a hidden directory:
 *
 *   /sdcard/Android/data/com.whatsapp/files/.once_media/
 *
 * That directory contains a .nomedia file so Android's MediaScanner ignores
 * it and the gallery never shows its contents.
 *
 * Works for both received and sent view-once media, because WhatsApp always
 * cleans up the local copy through File.delete() after the message is opened.
 */
public class ViewOnceSaver implements Hook {

    private static final String TAG = "PATCH";

    /** Relative path inside external storage where copies are saved. */
    private static final String SAVE_SUBDIR =
            "Android/data/com.whatsapp/files/.once_media";

    // ------------------------------------------------------------------
    // Hook: File.delete()
    // ------------------------------------------------------------------

    /**
     * Backup slot required by ArtHooks — never called directly by our code,
     * but the framework uses it to store the original method pointer.
     */
    static boolean file_delete_hook_backup(File file) {
        return false;
    }

    /**
     * Replacement for File.delete().
     * If the file looks like WhatsApp view-once media, save a copy first.
     * Then let the original deletion proceed normally.
     */
    static boolean file_delete_hook(File file) {
        try {
            if (isViewOnceMedia(file)) {
                saveToHiddenFolder(file);
            }
        } catch (Exception e) {
            Log.e(TAG, "ViewOnceSaver: hook error: " + e.getMessage());
        }
        // Delegate to the original File.delete() via the backup slot
        return file_delete_hook_backup(file);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Returns true when the file is a media file inside WhatsApp's "Private"
     * folder — the folder WhatsApp uses exclusively for view-once media that
     * has been downloaded for display.
     *
     * Typical paths:
     *   …/WhatsApp/Media/WhatsApp Images/Private/<file>.jpg
     *   …/WhatsApp/Media/WhatsApp Video/Private/<file>.mp4
     */
    static boolean isViewOnceMedia(File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return false;
        }
        String path = file.getAbsolutePath();

        // Must be inside a "Private" directory
        if (!path.contains("/Private/")) {
            return false;
        }

        // Must be a recognised media extension
        String lower = path.toLowerCase(Locale.US);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".webp")
                || lower.endsWith(".gif")
                || lower.endsWith(".mp4")
                || lower.endsWith(".opus")
                || lower.endsWith(".aac")
                || lower.endsWith(".3gp");
    }

    /**
     * Copies {@code source} to the hidden save directory, renaming it with a
     * timestamp so multiple saves never collide.
     */
    static void saveToHiddenFolder(File source) {
        try {
            // Resolve save directory on external storage
            File sdcard = Environment.getExternalStorageDirectory();
            File saveDir = new File(sdcard, SAVE_SUBDIR);

            if (!saveDir.exists()) {
                saveDir.mkdirs();
                // .nomedia tells Android MediaScanner to ignore this folder
                // → files never appear in the gallery
                new File(saveDir, ".nomedia").createNewFile();
                Log.i(TAG, "ViewOnceSaver: created save dir: " + saveDir.getAbsolutePath());
            }

            // Build a unique destination filename
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date());
            String name = source.getName();
            String ext = name.contains(".")
                    ? name.substring(name.lastIndexOf('.'))
                    : "";
            File dest = new File(saveDir, ts + ext);

            // Stream-copy
            try (FileInputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }

            Log.i(TAG, "ViewOnceSaver: saved → " + dest.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "ViewOnceSaver: copy error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Hook lifecycle
    // ------------------------------------------------------------------

    @Override
    public void load() {
        Log.i(TAG, "ViewOnceSaver: loading patch");
        try {
            Method hookMethod   = ViewOnceSaver.class
                    .getDeclaredMethod("file_delete_hook", File.class);
            Method backupMethod = ViewOnceSaver.class
                    .getDeclaredMethod("file_delete_hook_backup", File.class);
            Method originalDelete = File.class.getDeclaredMethod("delete");

            ArtHooks.hook_function(originalDelete, hookMethod, backupMethod);
            Log.i(TAG, "ViewOnceSaver: patch loaded OK");
        } catch (Exception e) {
            Log.e(TAG, "ViewOnceSaver: load error: " + e.getMessage());
        }
    }

    @Override
    public void unload() {
        Log.i(TAG, "ViewOnceSaver: patch unloaded");
    }
}
