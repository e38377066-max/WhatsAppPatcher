package com.smali_generator.patches;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import android.util.Log;

import com.arthooks.ArtHooks;

import com.smali_generator.Hook;


public class DecryptProtobuf implements Hook {

    public static Class<?> decrypt_protobuf_class;
    public static Class<?> GeneratedMessageLite;
    public static Method parseFromMethod;
    public static Object default_instance;

    // DIAGNOSTIC: dump the top-level Message protobuf schema exactly once so we
    // can locate the view-once and media fields for THIS obfuscated WhatsApp
    // build. Filter logcat with:  adb logcat -s PATCH
    private static boolean schema_dumped = false;

    static void handle_view_once(Object obj) {
        try {
            Class<?> c = obj.getClass();
            boolean is_message = false;
            try {
                c.getDeclaredField("protocolMessage_");
                is_message = true;
            } catch (NoSuchFieldException ignored) {
            }
            if (!is_message) {
                return;
            }

            // 1) One-time full field dump of the Message class.
            if (!schema_dumped) {
                schema_dumped = true;
                Log.i("PATCH", "DecryptProtobuf: === Message schema (" + c.getName() + ") ===");
                for (Field f : c.getDeclaredFields()) {
                    Log.i("PATCH", "DecryptProtobuf: field " + f.getType().getSimpleName()
                            + " " + f.getName());
                }
                Log.i("PATCH", "DecryptProtobuf: === end schema ===");
            }

            // 2) Per-message: log which media / view-once related fields are
            //    actually populated so we know what an incoming view-once looks
            //    like on this device.
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if (n.contains("viewonce") || n.contains("image")
                        || n.contains("video") || n.contains("audio")
                        || n.contains("document")) {
                    f.setAccessible(true);
                    Object v = null;
                    try {
                        v = f.get(obj);
                    } catch (Exception ignored) {
                    }
                    if (v != null && !(v instanceof Boolean && !((Boolean) v))
                            && !(v instanceof Integer && ((Integer) v) == 0)) {
                        Log.i("PATCH", "DecryptProtobuf: populated " + f.getName()
                                + " = " + v.getClass().getSimpleName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: view_once dump error: " + e.getMessage());
        }
    }

    static void handle_delete_message(Object base_message, Object protocol_message) {
        // Intentionally left empty: we no longer tamper with the message key so
        // WhatsApp deletes the message normally from the UI.
        // The content is saved silently by DeletedMessageSaver, which intercepts
        // SQLiteDatabase.delete() before WhatsApp erases the row.
    }

    static void handle_protocol_message(Class<?> BaseMessage, Object obj) {
        try {
            Field protocol_message_field = BaseMessage.getDeclaredField("protocolMessage_");
            Object protocol_message = protocol_message_field.get(obj);
            if (protocol_message != null) {
                Field protocol_type = protocol_message.getClass().getDeclaredField("type_");
                Object type_object = protocol_type.get(protocol_message);
                if (type_object == null) {
                    return;
                }
                // DIAGNOSTIC: log the protocol type of every control message so we
                // can confirm this hook fires and see the code for "delete for
                // everyone" (revoke) on this WhatsApp build.
                Log.i("PATCH", "DecryptProtobuf: protocolMessage type_=" + type_object);
                switch ((int) type_object) {
                    case 0:
                        Log.i("PATCH", "DecryptProtobuf: REVOKE (delete-for-everyone) detected");
                        handle_delete_message(obj, protocol_message);
                        break;
                }
            }
        } catch (NoSuchFieldException e) {
            Log.i("PATCH", "DecryptProtobuf: NoSuchFieldException: " + e.getMessage());
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: Error: " + e.getMessage());
        }
    }

    static void handle_final_message(Class<?> MessageClass, Object obj) {
        handle_protocol_message(MessageClass, obj);
    }

    static Object decrypt_protobuf_hook(byte[] bArr) {
        Object obj;
        try {
            obj = parseFromMethod.invoke(decrypt_protobuf_class, default_instance, bArr);
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: Error: " + e.getMessage());
            return null;
        }
        handle_view_once(obj);
        try {
            Class<?> MessageClass = obj.getClass();
            MessageClass.getDeclaredField("protocolMessage_");
            // Should check if the receiver method is expecting a specific type of message because of the previous ones.
            handle_final_message(MessageClass, obj);
        } catch (NoSuchFieldException ignored) {
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: Error: " + e.getMessage());
        }
        return obj;
    }

    public void load() {
        Log.i("PATCH", "DecryptProtobuf: Patch loaded");
        try {
            decrypt_protobuf_class = Class.forName("{{DECRYPT_PROTOBUF_CLASS_NAME}}");
            GeneratedMessageLite = Class.forName("com.google.protobuf.GeneratedMessageLite");
            parseFromMethod = GeneratedMessageLite.getDeclaredMethod("parseFrom", GeneratedMessageLite, byte[].class);
            default_instance = decrypt_protobuf_class.getField("DEFAULT_INSTANCE").get(decrypt_protobuf_class);
            Method decrypt_protobuf_hook_method = DecryptProtobuf.class.getDeclaredMethod("decrypt_protobuf_hook", byte[].class);
            Executable to_hook = ArtHooks.find_function(decrypt_protobuf_class, "{{DECRYPT_PROTOBUF_METHOD_NAME}}", "{{DECRYPT_PROTOBUF_METHOD_SIG}}");
            ArtHooks.hook_function(decrypt_protobuf_hook_method, to_hook);
        } catch (Exception e) {
            Log.e("PATCH", "DecryptProtobuf: Error: " + e.getMessage());
        }
    }

    public void unload() {
        Log.i("PATCH", "DecryptProtobuf: Patch unloaded");
    }
}
