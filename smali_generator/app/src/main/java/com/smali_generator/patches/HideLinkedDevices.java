package com.smali_generator.patches;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;


/**
 * Hides linked/companion devices from the "Linked devices" screen (UI only).
 *
 * It hooks the view-model accessor that returns the list the screen renders and
 * forces it to return an empty list. This is a purely local, cosmetic change:
 * it does NOT alter what WhatsApp's servers know, and it does NOT touch the
 * companion-device data used for end-to-end encryption or message routing
 * (that lives on a different code path).
 *
 * The target class/method/signature are discovered at patch time by
 * HideLinkedDevicesFinder and injected via the {{...}} placeholders below.
 */
public class HideLinkedDevices implements Hook {

    // Replacement for the list accessor: always return an empty list.
    static List<?> empty_list(Object self) {
        return new ArrayList<>();
    }

    public void load() {
        Log.i("PATCH", "HideLinkedDevices: Patch loaded");
        try {
            Class<?> target = Class.forName("{{LINKED_DEVICES_CLASS_NAME}}");
            Method replacement = HideLinkedDevices.class.getDeclaredMethod("empty_list", Object.class);
            Executable to_hook = ArtHooks.find_function(
                    target, "{{LINKED_DEVICES_METHOD_NAME}}", "{{LINKED_DEVICES_METHOD_SIG}}");
            ArtHooks.hook_function(to_hook, replacement);
            Log.i("PATCH", "HideLinkedDevices: hook installed");
        } catch (Exception e) {
            Log.e("PATCH", "HideLinkedDevices: Error: " + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "HideLinkedDevices: Patch unloaded");
    }
}
