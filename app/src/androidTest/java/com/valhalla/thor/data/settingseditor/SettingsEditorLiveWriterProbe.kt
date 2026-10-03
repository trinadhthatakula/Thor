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
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * Test-APK-only real root writer. Its sole Settings target is an explicit disposable system key.
 *
 * Launch beneath toybox timeout --foreground -s KILL, inside the existing Odin isolated group.
 * The app precreates its private control directory; this helper never opens fixture.json or any
 * other sibling. Markers contain process identities and counters, never Settings values. A regular,
 * empty, app-owned complete file requests a final synthetic write and ordinary process completion.
 * Killing this helper or observing completed.json is test evidence, not production receipt cleanup.
 */
object SettingsEditorLiveWriterProbe {
    const val INITIAL_VALUE = "live_writer_initial"
    const val COMPLETED_VALUE = "live_writer_completed"
    private val className = SettingsEditorLiveWriterProbe::class.java.name
    private var stage = "request"
    // This test-only root helper deliberately supports only Thor debug in Android user 0.
    @SuppressLint("SdCardPath")
    private const val APP_DIRECTORY = "/data/user/0/com.valhalla.thor.debug"
    private const val CONTROL_PARENT = "/no_backup/settings_live_writer/"
    private val variants = setOf("normal", "ignore_term", "child", "ignore_term_child")
    private val emptyMarkers = setOf(
        "ready.json", "heartbeat.json", "completed.json", "child-ready.json",
        "child-completed.json", "producer-starting.json",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        var probe: Probe? = null
        var exitCode = 1
        try {
            if (args.size != 1 || args[0].length > 8192) throw IllegalArgumentException()
            val request = JSONObject(String(Base64.decode(args[0], Base64.NO_WRAP), StandardCharsets.UTF_8))
            probe = Probe(request)
            probe.run()
            exitCode = 0
        } catch (ignored: Throwable) {
            // Provider exception messages can contain Settings values. Never print them or a stack.
            probe?.reportFailure()
            System.out.println("THOR_SETTINGS_LIVE_ERROR:$stage")
            System.out.flush()
        }
        // Also releases the external Settings provider token on Binder death.
        exitProcess(exitCode)
    }

    private class Probe(private val request: JSONObject) {
        private val key: String
        private val runId: String
        private val variant: String
        private val maxSeconds: Int
        private val child: Boolean
        private val ignoreTerm: Boolean
        private val spawnChild: Boolean
        private val directory: File
        private val ownerUid: Int
        private val ownerGid: Int
        private val identity: Identity
        private val watchdog: Identity
        private val startedElapsedMs = SystemClock.elapsedRealtime()
        private var sequence = 0L
        private var childProcess: java.lang.Process? = null
        private var childReady: JSONObject? = null

        init {
            if (Process.myUid() != 0) throw IllegalStateException()
            key = request.getString("key")
            if (!key.matches(Regex("thor_sett_live_[0-9a-f]{32}"))) throw IllegalArgumentException()
            val suffix = key.substring("thor_sett_live_".length)
            runId = UUID.fromString(suffix.substring(0, 8) + "-" + suffix.substring(8, 12) + "-" +
                suffix.substring(12, 16) + "-" + suffix.substring(16, 20) + "-" + suffix.substring(20)).toString()
            if (request.getInt("userId") != 0) throw IllegalArgumentException()
            variant = request.getString("variant")
            if (variant !in variants) throw IllegalArgumentException()
            maxSeconds = request.getInt("maxSeconds")
            if (maxSeconds !in 30..180) throw IllegalArgumentException()
            val role = request.optString("role", "producer")
            if (role != "producer" && role != "child") throw IllegalArgumentException()
            child = role == "child"
            ignoreTerm = variant.startsWith("ignore_term")
            spawnChild = !child && variant.endsWith("child")

            // /data/user/0 itself can be Android's /data/data alias. Below the fixed app root,
            // every component must be an app-owned real directory, never a symlink.
            stage = "control_directory"
            val app = File(APP_DIRECTORY)
            val appStat = Os.lstat(app.absolutePath)
            if (!OsConstants.S_ISDIR(appStat.st_mode) || appStat.st_uid < 10000 || appStat.st_uid >= 100000 ||
                (appStat.st_mode and OsConstants.S_IWOTH) != 0) throw IllegalStateException()
            ownerUid = appStat.st_uid
            ownerGid = appStat.st_gid
            val canonicalApp = app.canonicalFile
            val expected = "$APP_DIRECTORY$CONTROL_PARENT$runId/control"
            directory = File(expected)
            val supplied = request.optString("controlDir", expected)
            val canonicalExpected = "${canonicalApp.absolutePath}$CONTROL_PARENT$runId/control"
            if ((supplied != expected && supplied != canonicalExpected) || directory.canonicalPath != canonicalExpected) {
                throw IllegalArgumentException()
            }
            // Android creates no_backup with mode 0771. Its existing app-owned group access is
            // platform policy; only the fixture's own descendants require no group write.
            validateDirectory(File(app, "no_backup"), platformDirectory = true)
            validateDirectory(File(app, "no_backup/settings_live_writer"))
            validateDirectory(directory.parentFile!!)
            validateDirectory(directory)
            if (!child) validateFreshMarkers()

            stage = "process_identity"
            identity = identity(Process.myPid())
            watchdog = identity(identity.ppid)
            if (identity.uid != 0 || identity.pgid <= 1 || watchdog.uid != 0 || watchdog.pgid != identity.pgid) {
                throw IllegalStateException()
            }
            stage = "watchdog"
            requireWatchdog()
            stage = "term_disposition"
            if (termIgnored() != ignoreTerm) throw IllegalStateException()
            if (child) {
                val producer = identity(request.getInt("producerPid"))
                if (producer.startTicks != request.getLong("producerStartTicks") || producer.uid != 0 ||
                    producer.pgid != identity.pgid || producer.bootId != identity.bootId) throw IllegalStateException()
            }
        }

        fun run() {
            stage = "markers"
            if (child) {
                writeMarker("child-ready.json", marker("LIVE", 0))
                while (!completionRequested()) {
                    requireWithinDeadline()
                    Thread.sleep(100)
                }
                writeMarker("child-completed.json", marker("COMPLETED", 0))
                return
            }

            // This exact identity is available even if acquiring the provider or first write fails.
            writeMarker("producer-starting.json", marker("STARTING", 0))
            // A bare app_process has no application binding to prepare its main looper.
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
            stage = "provider"
            settingsClient().use { client ->
                writeAndVerify(client, INITIAL_VALUE)
                var writes = 1L
                if (spawnChild) startChild()
                writeMarker("heartbeat.json", marker("LIVE", writes))
                writeMarker("ready.json", marker("LIVE", writes))
                while (!completionRequested()) {
                    requireWithinDeadline()
                    Thread.sleep(1000)
                    if (completionRequested()) break
                    writeAndVerify(client, INITIAL_VALUE)
                    writeMarker("heartbeat.json", marker("LIVE", ++writes))
                }
                childProcess?.let { process ->
                    if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw IllegalStateException()
                    val completed = readMarker("child-completed.json")
                    if (!sameIdentity(childReady!!, completed) || completed.getString("phase") != "COMPLETED") {
                        throw IllegalStateException()
                    }
                }
                writeAndVerify(client, COMPLETED_VALUE)
                writeMarker("completed.json", marker("COMPLETED", ++writes))
            }
        }

        private fun startChild() {
            stage = "child_start"
            val childRequest = JSONObject(request.toString()).put("role", "child")
                .put("producerPid", identity.pid).put("producerStartTicks", identity.startTicks)
            val payload = Base64.encodeToString(childRequest.toString().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            // All shell tokens are literals or strict hex/base64. No setsid or setpgid occurs.
            val command = (if (ignoreTerm) "trap '' TERM; " else "") +
                "exec /system/bin/app_process /system/bin --nice-name=thor_sett_live_child_" +
                key.substring("thor_sett_live_".length) + " $className '$payload'"
            val process = ProcessBuilder("/system/bin/toybox", "timeout", "--foreground", "-s", "KILL",
                maxSeconds.toString(), "/system/bin/sh", "-c", command).inheritIO().start()
            childProcess = process
            val readyDeadline = SystemClock.elapsedRealtime() + 10000
            while (true) {
                requireWithinDeadline()
                val marker = File(directory, "child-ready.json")
                if (marker.length() > 0) break
                if (!process.isAlive || SystemClock.elapsedRealtime() >= readyDeadline) throw IllegalStateException()
                Thread.sleep(50)
            }
            val ready = readMarker("child-ready.json")
            childReady = ready
            val actual = identity(ready.getInt("pid"))
            if (runId != ready.getString("runId") || ready.getString("role") != "child" ||
                ready.getString("phase") != "LIVE" || actual.pgid != identity.pgid || !sameIdentity(actual.json(), ready)) {
                throw IllegalStateException()
            }
        }

        private fun writeAndVerify(client: ContentProviderClient, value: String) {
            stage = "write_readback"
            requireWithinDeadline()
            val extras = Bundle().apply {
                putInt("_user", 0)
                putString("value", value)
            }
            call(client, "PUT_system", key, extras)
            val readExtras = Bundle().apply { putInt("_user", 0) }
            val result = call(client, "GET_system", key, readExtras)
            if (result == null || value != result.getString("value")) throw IllegalStateException()
        }

        private fun requireWithinDeadline() {
            if (SystemClock.elapsedRealtime() - startedElapsedMs >= maxSeconds * 1000L) throw IllegalStateException()
        }

        private fun requireWatchdog() {
            val args = Pattern.compile("\u0000").split(String(
                readSmall(File("/proc/${watchdog.pid}/cmdline"), 16384), StandardCharsets.UTF_8,
            ))
            val expected = arrayOf("/system/bin/toybox", "timeout", "--foreground", "-s", "KILL", maxSeconds.toString())
            if (args.size <= expected.size) throw IllegalStateException()
            for (i in expected.indices) {
                if (expected[i] != args[i]) throw IllegalStateException()
            }
        }

        private fun marker(phase: String, writes: Long): JSONObject {
            val result = identity.json().put("schema", 1).put("runId", runId).put("key", key)
                .put("userId", 0).put("role", if (child) "child" else "producer").put("variant", variant)
                .put("phase", phase).put("sequence", ++sequence).put("writes", writes)
                .put("termIgnored", ignoreTerm).put("watchdogSeconds", maxSeconds)
                .put("startedElapsedMs", startedElapsedMs).put("elapsedMs", SystemClock.elapsedRealtime())
                .put("ownerUid", ownerUid).put("watchdog", watchdog.json())
            childReady?.let { result.put("child", it) }
            return result
        }

        private fun validateDirectory(file: File, platformDirectory: Boolean = false) {
            val stat = Os.lstat(file.absolutePath)
            val forbidden = if (platformDirectory) OsConstants.S_IWOTH else OsConstants.S_IWGRP or OsConstants.S_IWOTH
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != ownerUid || stat.st_gid != ownerGid ||
                (stat.st_mode and forbidden) != 0) throw IllegalStateException()
        }

        private fun validateFreshMarkers() {
            val files = directory.listFiles() ?: throw IllegalStateException()
            for (file in files) {
                if (file.name !in emptyMarkers) throw IllegalStateException()
                if (validateMarker(file).st_size != 0L) throw IllegalStateException()
            }
        }

        private fun validateMarker(file: File): StructStat {
            validateDirectory(directory)
            val stat = Os.lstat(file.absolutePath)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != ownerUid || stat.st_gid != ownerGid ||
                stat.st_nlink != 1L || (stat.st_mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO)) != 0) {
                throw IllegalStateException()
            }
            return stat
        }

        private fun completionRequested(): Boolean {
            val complete = File(directory, "complete")
            return try {
                if (validateMarker(complete).st_size != 0L) throw IllegalStateException()
                true
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ENOENT) throw error
                false
            }
        }

        private fun readMarker(name: String): JSONObject {
            val file = File(directory, name)
            validateMarker(file)
            return JSONObject(String(readSmall(file, 16384), StandardCharsets.UTF_8))
        }

        private fun writeMarker(name: String, value: JSONObject) {
            stage = "markers"
            validateDirectory(directory)
            val target = File(directory, name)
            try {
                validateMarker(target)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ENOENT) throw error
            }
            val temporary = File(directory, ".$name.tmp")
            val descriptor = Os.open(temporary.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
                OsConstants.S_IRUSR or OsConstants.S_IWUSR)
            FileOutputStream(descriptor).use { output ->
                Os.fchown(descriptor, ownerUid, ownerGid)
                output.write((value.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, target.absolutePath)
            val directoryDescriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(directoryDescriptor).st_mode)) throw IllegalStateException()
                Os.fsync(directoryDescriptor)
            } finally {
                Os.close(directoryDescriptor)
            }
        }

        fun reportFailure() {
            val failedStage = stage
            try {
                writeMarker(if (child) "child-error.json" else "error.json", marker("ERROR", -1).put("stage", failedStage))
            } catch (ignored: Throwable) {
                // No exception details or Settings values leave the helper.
            } finally {
                stage = failedStage
            }
        }
    }

    private class Identity(val pid: Int, val ppid: Int, val pgid: Int, val uid: Int, val startTicks: Long, val bootId: String) {
        fun json(): JSONObject = JSONObject().put("pid", pid).put("ppid", ppid).put("pgid", pgid)
            .put("uid", uid).put("startTicks", startTicks).put("bootId", bootId)
    }

    private fun identity(pid: Int): Identity {
        if (pid <= 1) throw IllegalArgumentException()
        val stat = String(readSmall(File("/proc/$pid/stat"), 8192), StandardCharsets.UTF_8).trim { it <= ' ' }
        val end = stat.lastIndexOf(')')
        if (end < 0 || stat.substring(0, stat.indexOf(' ')) != pid.toString()) throw IllegalStateException()
        val fields = Pattern.compile(" +").split(stat.substring(end + 2))
        if (fields.size < 20 || fields[0] == "Z" || fields[0] == "X") throw IllegalStateException()
        var uid = -1
        val status = String(readSmall(File("/proc/$pid/status"), 16384), StandardCharsets.UTF_8)
        for (line in status.split('\n')) {
            if (line.startsWith("Uid:")) uid = Pattern.compile("\\s+").split(line.substring(4).trim { it <= ' ' })[0].toInt()
        }
        val bootId = String(readSmall(File("/proc/sys/kernel/random/boot_id"), 128), StandardCharsets.UTF_8).trim { it <= ' ' }
        if (UUID.fromString(bootId).toString() != bootId || uid < 0) throw IllegalStateException()
        return Identity(pid, fields[1].toInt(), fields[2].toInt(), uid, fields[19].toLong(), bootId)
    }

    private fun sameIdentity(a: JSONObject, b: JSONObject): Boolean =
        a.getInt("pid") == b.getInt("pid") && a.getLong("startTicks") == b.getLong("startTicks") &&
            a.getInt("pgid") == b.getInt("pgid") && a.getInt("uid") == b.getInt("uid") &&
            a.getString("bootId") == b.getString("bootId")

    private fun termIgnored(): Boolean {
        val status = String(readSmall(File("/proc/self/status"), 16384), StandardCharsets.UTF_8)
        for (line in status.split('\n')) {
            if (line.startsWith("SigIgn:")) {
                return (java.lang.Long.parseUnsignedLong(line.substring(7).trim { it <= ' ' }, 16) and (1L shl 14)) != 0L
            }
        }
        throw IllegalStateException()
    }

    private fun readSmall(file: File, limit: Int): ByteArray = FileInputStream(file).use { input ->
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(1024)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (output.size() + count > limit) throw IllegalStateException()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    // Same app_process-only provider attribution setup as SettingsEditorBridge. This probe never
    // enumerates a table: the only calls are PUT_system and GET_system for its validated literal key.
    // Framework reflection executes only in root app_process, never Thor's application runtime.
    @SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
    private fun settingsClient(): ContentProviderClient {
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
                .invoke(am, "settings", 0, token, "ThorSettLiveTest")
        } else {
            am.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                IBinder::class.java).apply { isAccessible = true }.invoke(am, "settings", 0, token)
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

    private fun call(client: ContentProviderClient, method: String, key: String, extras: Bundle): Bundle? =
        if (Build.VERSION.SDK_INT >= 29) client.call("settings", method, key, extras)
        else client.call(method, key, extras)
}
