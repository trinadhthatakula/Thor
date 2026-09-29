// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor;

import android.content.Context;
import android.os.Process;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Small app_process entry point. No app initialization, logging, or shell-interpreted user data. */
public final class SettingsEditorBridge {
    // This entry point runs in a fresh shell/root app_process without handleBindApplication.
    // App target-SDK hidden-API enforcement is never enabled here. Fail closed if an OEM changes
    // these internals; no reflective access occurs in Thor's ordinary application process.
    @android.annotation.SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi"})
    public static void main(String[] args) {
        try {
            android.os.Looper.prepareMainLooper();
            JSONObject request = new JSONObject(new String(Base64.decode(args[0], Base64.NO_WRAP), StandardCharsets.UTF_8));
            if (request.getString("operation").equals("properties")) {
                emit(new JSONObject().put("status", "ok").put("entries", properties()));
                System.exit(0);
                return;
            }
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            Context context = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            java.lang.reflect.Field opPackage = context.getClass().getDeclaredField("mOpPackageName");
            opPackage.setAccessible(true);
            opPackage.set(context, "com.android.shell");
            java.lang.reflect.Field basePackage = context.getClass().getDeclaredField("mBasePackageName");
            basePackage.setAccessible(true);
            basePackage.set(context, "com.android.shell");
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                Object builder = Class.forName("android.content.AttributionSource$Builder").getConstructor(int.class).newInstance(Process.myUid());
                builder.getClass().getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
                java.lang.reflect.Field attribution = context.getClass().getDeclaredField("mAttributionSource");
                attribution.setAccessible(true);
                attribution.set(context, builder.getClass().getMethod("build").invoke(builder));
            }
            Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            android.os.IBinder token = new android.os.Binder();
            Object holder;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                java.lang.reflect.Method acquire = am.getClass().getMethod("getContentProviderExternal", String.class, int.class, android.os.IBinder.class, String.class);
                acquire.setAccessible(true);
                holder = acquire.invoke(am, "settings", request.getInt("userId"), token, "ThorSettEdit");
            } else {
                java.lang.reflect.Method acquire = am.getClass().getMethod("getContentProviderExternal", String.class, int.class, android.os.IBinder.class);
                acquire.setAccessible(true);
                holder = acquire.invoke(am, "settings", request.getInt("userId"), token);
            }
            java.lang.reflect.Field providerField = holder.getClass().getField("provider");
            Object provider = providerField.get(holder);
            java.lang.reflect.Constructor<?> constructor = Class.forName("android.content.ContentProviderClient").getDeclaredConstructor(android.content.ContentResolver.class, Class.forName("android.content.IContentProvider"), boolean.class);
            constructor.setAccessible(true);
            android.content.ContentProviderClient client = (android.content.ContentProviderClient) constructor.newInstance(context.getContentResolver(), provider, true);
            if (android.os.Build.VERSION.SDK_INT < 31) {
                java.lang.reflect.Field clientPackage = client.getClass().getDeclaredField("mPackageName");
                clientPackage.setAccessible(true);
                clientPackage.set(client, "com.android.shell");
            } else {
                java.lang.reflect.Field clientAttribution = client.getClass().getDeclaredField("mAttributionSource");
                clientAttribution.setAccessible(true);
                java.lang.reflect.Field source = context.getClass().getDeclaredField("mAttributionSource");
                source.setAccessible(true);
                clientAttribution.set(client, source.get(context));
            }
            String table = request.getString("table");
            if (!table.equals("system") && !table.equals("secure") && !table.equals("global")) throw new IllegalArgumentException();
            int user = request.getInt("userId");
            if (user < 0 || (table.equals("global") && user != 0)) throw new IllegalArgumentException();
            JSONArray entries = read(client, table, user);
            if (request.getString("operation").equals("write")) {
                String key = request.getString("key");
                if (key.isEmpty() || key.length() > 256 || !key.matches("[A-Za-z0-9_.:-]+")) throw new IllegalArgumentException();
                JSONObject before = state(entries, key);
                JSONObject expected = request.getJSONObject("expected");
                if (!same(before, expected)) {
                    emit(new JSONObject().put("status", "conflict").put("entries", entries));
                    System.exit(0);
                    return;
                }
                JSONObject desired = request.getJSONObject("desired");
                if (desired.getBoolean("present")) {
                    String value = desired.isNull("value") ? null : desired.getString("value");
                    if (value != null && value.length() > 65536) throw new IllegalArgumentException();
                    android.os.Bundle extras = new android.os.Bundle();
                    extras.putInt("_user", user);
                    extras.putString("value", value);
                    call(client, "PUT_" + table, key, extras);
                } else {
                    android.os.Bundle extras = new android.os.Bundle();
                    extras.putInt("_user", user);
                    call(client, "DELETE_" + table, key, extras);
                }
                entries = read(client, table, user);
            }
            emit(new JSONObject().put("status", "ok").put("entries", entries));
            // System.exit releases the external-provider token on Binder death.
        } catch (Throwable failure) {
            // Never expose an exception message: provider diagnostics can contain settings values.
            try { emit(new JSONObject().put("status", "error").put("entries", new JSONArray())); }
            catch (Exception ignored) { System.exit(1); }
        }
        System.exit(0);
    }
    @android.annotation.SuppressLint({"PrivateApi", "BlockedPrivateApi"})
    private static JSONArray properties() throws Exception {
        java.lang.Process process = new ProcessBuilder("/system/bin/getprop").start();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf("]: [");
                if (line.startsWith("[") && split > 1) names.add(line.substring(1, split));
            }
        }
        if (process.waitFor() != 0) throw new IllegalStateException();
        Class<?> properties = Class.forName("android.os.SystemProperties");
        java.lang.reflect.Method find = properties.getDeclaredMethod("find", String.class);
        find.setAccessible(true);
        JSONArray result = new JSONArray();
        for (String name : names) {
            // getprop is used only to enumerate names. A real property handle supplies its exact
            // value and rejects fake headers embedded in multiline property values.
            Object handle = find.invoke(null, name);
            if (handle == null) continue;
            java.lang.reflect.Method get = handle.getClass().getDeclaredMethod("get");
            get.setAccessible(true);
            result.put(new JSONObject().put("key", name).put("value", get.invoke(handle)));
        }
        return result;
    }
    private static android.os.Bundle call(android.content.ContentProviderClient client, String method, String key, android.os.Bundle extras) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 29) return client.call("settings", method, key, extras);
        return client.call(method, key, extras);
    }
    private static JSONArray read(android.content.ContentProviderClient client, String table, int user) throws Exception {
        android.os.Bundle extras = new android.os.Bundle();
        extras.putInt("_user", user);
        android.os.Bundle reply = call(client, "LIST_" + table, null, extras);
        if (reply == null) throw new IllegalStateException();
        java.util.ArrayList<String> lines = reply.getStringArrayList("result_settings_list");
        if (lines == null) throw new IllegalStateException();
        JSONArray result = new JSONArray();
        java.util.HashSet<String> keys = new java.util.HashSet<>();
        for (String line : lines) {
            int split = line.indexOf('=');
            if (split <= 0) throw new IllegalStateException();
            String key = line.substring(0, split);
            String value = line.substring(split + 1);
            if (!keys.add(key)) throw new IllegalStateException();
            // LIST carries each entry as a separate Bundle string, preserving multiline and equals.
            // Its textual null is ambiguous; GET supplies the actual nullable value.
            if (value.equals("null")) {
                android.os.Bundle found = call(client, "GET_" + table, key, extras);
                if (found == null) throw new IllegalStateException();
                value = found.getString("value");
            }
            result.put(new JSONObject().put("key", key).put("value", value == null ? JSONObject.NULL : value));
        }
        return result;
    }
    private static JSONObject state(JSONArray entries, String key) throws Exception {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (entry.getString("key").equals(key)) return new JSONObject().put("present", true).put("value", entry.get("value"));
        }
        return new JSONObject().put("present", false).put("value", JSONObject.NULL);
    }
    private static boolean same(JSONObject a, JSONObject b) throws Exception {
        return a.getBoolean("present") == b.getBoolean("present") && a.opt("value").equals(b.opt("value"));
    }
    private static void emit(JSONObject response) {
        System.out.println("THOR_SETTINGS:" + Base64.encodeToString(response.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }
}
