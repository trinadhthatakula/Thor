// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor

import android.annotation.SuppressLint
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess
import org.json.JSONArray
import org.json.JSONObject

/** Small app_process entry point. No app initialization, logging, or shell-interpreted user data. */
object SettingsEditorBridge {
    // This entry point runs in a fresh shell/root app_process without handleBindApplication.
    // App target-SDK hidden-API enforcement is never enabled here. Fail closed if an OEM changes
    // these internals; no reflective access occurs in Thor's ordinary application process.
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            // A bare app_process has no application binding to prepare its main looper.
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            val request = JSONObject(String(Base64.decode(args[0], Base64.NO_WRAP), StandardCharsets.UTF_8))
            if (request.getString("operation") == "properties") {
                emit(JSONObject().put("status", "ok").put("entries", properties()))
                exitProcess(0)
            }
            val client = settingsClient(request.getInt("userId"))
            val table = request.getString("table")
            if (table != "system" && table != "secure" && table != "global") throw IllegalArgumentException()
            val user = request.getInt("userId")
            if (user < 0 || (table == "global" && user != 0)) throw IllegalArgumentException()
            var entries = read(client, table, user)
            if (request.getString("operation") == "write") {
                val key = request.getString("key")
                if (key.isEmpty() || key.length > 256 || !key.matches(Regex("[A-Za-z0-9_.:-]+"))) {
                    throw IllegalArgumentException()
                }
                val before = state(entries, key)
                val expected = request.getJSONObject("expected")
                if (!same(before, expected)) {
                    emit(JSONObject().put("status", "conflict").put("entries", entries))
                    exitProcess(0)
                }
                val desired = request.getJSONObject("desired")
                if (desired.getBoolean("present")) {
                    val value = if (desired.isNull("value")) null else desired.getString("value")
                    if (value != null && value.length > 65536) throw IllegalArgumentException()
                    val extras = Bundle().apply {
                        putInt("_user", user)
                        putString("value", value)
                    }
                    call(client, "PUT_$table", key, extras)
                } else {
                    val extras = Bundle().apply { putInt("_user", user) }
                    call(client, "DELETE_$table", key, extras)
                }
                entries = read(client, table, user)
            }
            emit(JSONObject().put("status", "ok").put("entries", entries))
            // System.exit releases the external-provider token on Binder death.
        } catch (failure: Throwable) {
            // Never expose an exception message: provider diagnostics can contain settings values.
            try {
                emit(JSONObject().put("status", "error").put("entries", JSONArray()))
            } catch (ignored: Exception) {
                exitProcess(1)
            }
        }
        exitProcess(0)
    }

    @SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi")
    private fun settingsClient(user: Int): ContentProviderClient {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("systemMain").invoke(null)
        val system = threadClass.getMethod("getSystemContext").invoke(thread) as Context
        val context = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
        for (name in arrayOf("mOpPackageName", "mBasePackageName")) {
            context.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(context, "com.android.shell")
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val builder = Class.forName("android.content.AttributionSource\$Builder")
                .getConstructor(Int::class.javaPrimitiveType).newInstance(Process.myUid())
            builder.javaClass.getMethod("setPackageName", String::class.java).invoke(builder, "com.android.shell")
            context.javaClass.getDeclaredField("mAttributionSource").apply { isAccessible = true }
                .set(context, builder.javaClass.getMethod("build").invoke(builder))
        }
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token: IBinder = Binder()
        val holder = if (Build.VERSION.SDK_INT >= 29) {
            am.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                IBinder::class.java, String::class.java).apply { isAccessible = true }
                .invoke(am, "settings", user, token, "ThorSettEdit")
        } else {
            am.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                IBinder::class.java).apply { isAccessible = true }.invoke(am, "settings", user, token)
        }!!
        val provider = holder.javaClass.getField("provider").get(holder)
        val constructor = ContentProviderClient::class.java.getDeclaredConstructor(
            ContentResolver::class.java, Class.forName("android.content.IContentProvider"), Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val client = constructor.newInstance(context.contentResolver, provider, true)
        if (Build.VERSION.SDK_INT < 31) {
            client.javaClass.getDeclaredField("mPackageName").apply { isAccessible = true }.set(client, "com.android.shell")
        } else {
            val source = context.javaClass.getDeclaredField("mAttributionSource").apply { isAccessible = true }
            client.javaClass.getDeclaredField("mAttributionSource").apply { isAccessible = true }.set(client, source.get(context))
        }
        return client
    }

    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    private fun properties(): JSONArray {
        // This is the helper's only child. Bound it separately so it cannot retain Odin's
        // inherited output pipes after the outer watchdog terminates app_process.
        val process = ProcessBuilder("/system/bin/toybox", "timeout", "-s", "KILL", "5", "/system/bin/getprop").start()
        val names = linkedSetOf<String>()
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                val split = line.indexOf("]: [")
                if (line.startsWith("[") && split > 1) names.add(line.substring(1, split))
            }
        }
        if (process.waitFor() != 0) throw IllegalStateException()
        val properties = Class.forName("android.os.SystemProperties")
        val find = properties.getDeclaredMethod("find", String::class.java).apply { isAccessible = true }
        val result = JSONArray()
        for (name in names) {
            // getprop is used only to enumerate names. A real property handle supplies its exact
            // value and rejects fake headers embedded in multiline property values.
            val handle = find.invoke(null, name) ?: continue
            val get = handle.javaClass.getDeclaredMethod("get").apply { isAccessible = true }
            result.put(JSONObject().put("key", name).put("value", get.invoke(handle)))
        }
        return result
    }

    private fun call(client: ContentProviderClient, method: String, key: String?, extras: Bundle): Bundle? =
        if (Build.VERSION.SDK_INT >= 29) client.call("settings", method, key, extras)
        else client.call(method, key, extras)

    private fun read(client: ContentProviderClient, table: String, user: Int): JSONArray {
        val extras = Bundle().apply { putInt("_user", user) }
        val reply = call(client, "LIST_$table", null, extras) ?: throw IllegalStateException()
        val lines = reply.getStringArrayList("result_settings_list") ?: throw IllegalStateException()
        val result = JSONArray()
        val keys = hashSetOf<String>()
        for (line in lines) {
            val split = line.indexOf('=')
            if (split <= 0) throw IllegalStateException()
            val key = line.substring(0, split)
            var value: String? = line.substring(split + 1)
            if (!keys.add(key)) throw IllegalStateException()
            // LIST carries each entry as a separate Bundle string, preserving multiline and equals.
            // Its textual null is ambiguous; GET supplies the actual nullable value.
            if (value == "null") {
                val found = call(client, "GET_$table", key, extras) ?: throw IllegalStateException()
                value = found.getString("value")
            }
            result.put(JSONObject().put("key", key).put("value", value ?: JSONObject.NULL))
        }
        return result
    }

    private fun state(entries: JSONArray, key: String): JSONObject {
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getString("key") == key) {
                return JSONObject().put("present", true).put("value", entry.get("value"))
            }
        }
        return JSONObject().put("present", false).put("value", JSONObject.NULL)
    }

    private fun same(a: JSONObject, b: JSONObject): Boolean =
        a.getBoolean("present") == b.getBoolean("present") && a.opt("value")!!.equals(b.opt("value"))

    private fun emit(response: JSONObject) {
        System.out.println("THOR_SETTINGS:" + Base64.encodeToString(response.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP))
    }
}
