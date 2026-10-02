// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.data.settingseditor;

import android.content.ContentProviderClient;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Test-APK-only real root writer. Its sole Settings target is an explicit disposable system key.
 *
 * Launch beneath toybox timeout --foreground -s KILL, inside the existing Odin isolated group.
 * The app precreates its private control directory; this helper never opens fixture.json or any
 * other sibling. Markers contain process identities and counters, never Settings values. A regular,
 * empty, app-owned complete file requests a final synthetic write and ordinary process completion.
 * Killing this helper or observing completed.json is test evidence, not production receipt cleanup.
 */
public final class SettingsEditorLiveWriterProbe {
    public static final String INITIAL_VALUE = "live_writer_initial";
    public static final String COMPLETED_VALUE = "live_writer_completed";
    static final String CLASS_NAME = SettingsEditorLiveWriterProbe.class.getName();
    static String stage = "request";
    // This test-only root helper deliberately supports only Thor debug in Android user 0.
    @android.annotation.SuppressLint("SdCardPath")
    static final String APP_DIRECTORY = "/data/user/0/com.valhalla.thor.debug";
    static final String CONTROL_PARENT = "/no_backup/settings_live_writer/";
    static final Set<String> VARIANTS = new HashSet<>(Arrays.asList(
            "normal", "ignore_term", "child", "ignore_term_child"));
    static final Set<String> EMPTY_MARKERS = new HashSet<>(Arrays.asList(
            "ready.json", "heartbeat.json", "completed.json", "child-ready.json",
            "child-completed.json", "producer-starting.json"));

    public static void main(String[] args) {
        Probe probe = null;
        int exitCode = 1;
        try {
            if (args.length != 1 || args[0].length() > 8192) throw new IllegalArgumentException();
            JSONObject request = new JSONObject(new String(
                    Base64.decode(args[0], Base64.NO_WRAP), StandardCharsets.UTF_8));
            probe = new Probe(request);
            probe.run();
            exitCode = 0;
        } catch (Throwable ignored) {
            // Provider exception messages can contain Settings values. Never print them or a stack.
            if (probe != null) probe.reportFailure();
            System.out.println("THOR_SETTINGS_LIVE_ERROR:" + stage);
            System.out.flush();
        }
        // Also releases the external Settings provider token on Binder death.
        System.exit(exitCode);
    }

    static final class Probe {
        final String key;
        final String runId;
        final String variant;
        final int maxSeconds;
        final boolean child;
        final boolean ignoreTerm;
        final boolean spawnChild;
        final File directory;
        final int ownerUid;
        final int ownerGid;
        final Identity identity;
        final Identity watchdog;
        final long startedElapsedMs = SystemClock.elapsedRealtime();
        final JSONObject request;
        long sequence;
        java.lang.Process childProcess;
        JSONObject childReady;

        Probe(JSONObject request) throws Exception {
            this.request = request;
            if (Process.myUid() != 0) throw new IllegalStateException();
            key = request.getString("key");
            if (!key.matches("thor_sett_live_[0-9a-f]{32}")) throw new IllegalArgumentException();
            String suffix = key.substring("thor_sett_live_".length());
            runId = UUID.fromString(suffix.substring(0, 8) + "-" + suffix.substring(8, 12) + "-"
                    + suffix.substring(12, 16) + "-" + suffix.substring(16, 20) + "-"
                    + suffix.substring(20)).toString();
            if (request.getInt("userId") != 0) throw new IllegalArgumentException();
            variant = request.getString("variant");
            if (!VARIANTS.contains(variant)) throw new IllegalArgumentException();
            maxSeconds = request.getInt("maxSeconds");
            if (maxSeconds < 30 || maxSeconds > 180) throw new IllegalArgumentException();
            String role = request.optString("role", "producer");
            if (!role.equals("producer") && !role.equals("child")) throw new IllegalArgumentException();
            child = role.equals("child");
            ignoreTerm = variant.startsWith("ignore_term");
            spawnChild = !child && variant.endsWith("child");

            // /data/user/0 itself can be Android's /data/data alias. Below the fixed app root,
            // every component must be an app-owned real directory, never a symlink.
            stage = "control_directory";
            File app = new File(APP_DIRECTORY);
            StructStat appStat = Os.lstat(app.getAbsolutePath());
            if (!OsConstants.S_ISDIR(appStat.st_mode) || appStat.st_uid < 10000
                    || appStat.st_uid >= 100000 || (appStat.st_mode & 0002) != 0) throw new IllegalStateException();
            ownerUid = appStat.st_uid;
            ownerGid = appStat.st_gid;
            File canonicalApp = app.getCanonicalFile();
            String expected = APP_DIRECTORY + CONTROL_PARENT + runId + "/control";
            directory = new File(expected);
            String supplied = request.optString("controlDir", expected);
            String canonicalExpected = canonicalApp.getAbsolutePath() + CONTROL_PARENT + runId + "/control";
            if ((!supplied.equals(expected) && !supplied.equals(canonicalExpected))
                    || !directory.getCanonicalPath().equals(canonicalExpected)) throw new IllegalArgumentException();
            // Android creates no_backup with mode 0771. Its existing app-owned group access is
            // platform policy; only the fixture's own descendants require no group write.
            validateDirectory(new File(app, "no_backup"), true);
            validateDirectory(new File(app, "no_backup/settings_live_writer"));
            validateDirectory(directory.getParentFile());
            validateDirectory(directory);
            if (!child) validateFreshMarkers();

            stage = "process_identity";
            identity = identity(Process.myPid());
            watchdog = identity(identity.ppid);
            if (identity.uid != 0 || identity.pgid <= 1 || watchdog.uid != 0
                    || watchdog.pgid != identity.pgid) throw new IllegalStateException();
            stage = "watchdog";
            requireWatchdog();
            stage = "term_disposition";
            if (termIgnored() != ignoreTerm) throw new IllegalStateException();
            if (child) {
                Identity producer = identity(request.getInt("producerPid"));
                if (producer.startTicks != request.getLong("producerStartTicks")
                        || producer.uid != 0 || producer.pgid != identity.pgid
                        || !producer.bootId.equals(identity.bootId)) throw new IllegalStateException();
            }
        }

        void run() throws Exception {
            stage = "markers";
            if (child) {
                writeMarker("child-ready.json", marker("LIVE", 0));
                while (!completionRequested()) {
                    requireWithinDeadline();
                    Thread.sleep(100);
                }
                writeMarker("child-completed.json", marker("COMPLETED", 0));
                return;
            }

            // This exact identity is available even if acquiring the provider or first write fails.
            writeMarker("producer-starting.json", marker("STARTING", 0));
            android.os.Looper.prepareMainLooper();
            stage = "provider";
            try (ContentProviderClient client = settingsClient()) {
                writeAndVerify(client, INITIAL_VALUE);
                long writes = 1;
                if (spawnChild) startChild();
                writeMarker("heartbeat.json", marker("LIVE", writes));
                writeMarker("ready.json", marker("LIVE", writes));
                while (!completionRequested()) {
                    requireWithinDeadline();
                    Thread.sleep(1000);
                    if (completionRequested()) break;
                    writeAndVerify(client, INITIAL_VALUE);
                    writeMarker("heartbeat.json", marker("LIVE", ++writes));
                }
                if (childProcess != null) {
                    if (!childProcess.waitFor(5, TimeUnit.SECONDS) || childProcess.exitValue() != 0)
                        throw new IllegalStateException();
                    JSONObject completed = readMarker("child-completed.json");
                    if (!sameIdentity(childReady, completed) || !completed.getString("phase").equals("COMPLETED"))
                        throw new IllegalStateException();
                }
                writeAndVerify(client, COMPLETED_VALUE);
                writeMarker("completed.json", marker("COMPLETED", ++writes));
            }
        }

        void startChild() throws Exception {
            stage = "child_start";
            JSONObject childRequest = new JSONObject(request.toString())
                    .put("role", "child").put("producerPid", identity.pid)
                    .put("producerStartTicks", identity.startTicks);
            String payload = Base64.encodeToString(childRequest.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            // All shell tokens are literals or strict hex/base64. No setsid or setpgid occurs.
            String command = (ignoreTerm ? "trap '' TERM; " : "")
                    + "exec /system/bin/app_process /system/bin --nice-name=thor_sett_live_child_"
                    + key.substring("thor_sett_live_".length()) + " " + CLASS_NAME + " '" + payload + "'";
            childProcess = new ProcessBuilder("/system/bin/toybox", "timeout", "--foreground", "-s", "KILL",
                    Integer.toString(maxSeconds), "/system/bin/sh", "-c", command).inheritIO().start();
            long readyDeadline = SystemClock.elapsedRealtime() + 10000;
            while (true) {
                requireWithinDeadline();
                File marker = new File(directory, "child-ready.json");
                if (marker.length() > 0) break;
                if (!childProcess.isAlive() || SystemClock.elapsedRealtime() >= readyDeadline)
                    throw new IllegalStateException();
                Thread.sleep(50);
            }
            childReady = readMarker("child-ready.json");
            Identity actual = identity(childReady.getInt("pid"));
            if (!runId.equals(childReady.getString("runId")) || !childReady.getString("role").equals("child")
                    || !childReady.getString("phase").equals("LIVE") || actual.pgid != identity.pgid
                    || !sameIdentity(actual.json(), childReady)) throw new IllegalStateException();
        }

        void writeAndVerify(ContentProviderClient client, String value) throws Exception {
            stage = "write_readback";
            requireWithinDeadline();
            Bundle extras = new Bundle();
            extras.putInt("_user", 0);
            extras.putString("value", value);
            call(client, "PUT_system", key, extras);
            Bundle readExtras = new Bundle();
            readExtras.putInt("_user", 0);
            Bundle result = call(client, "GET_system", key, readExtras);
            if (result == null || !value.equals(result.getString("value"))) throw new IllegalStateException();
        }

        void requireWithinDeadline() {
            if (SystemClock.elapsedRealtime() - startedElapsedMs >= maxSeconds * 1000L)
                throw new IllegalStateException();
        }

        void requireWatchdog() throws Exception {
            String[] args = new String(readSmall(new File("/proc/" + watchdog.pid + "/cmdline"), 16384),
                    StandardCharsets.UTF_8).split(String.valueOf((char) 0));
            String[] expected = { "/system/bin/toybox", "timeout", "--foreground", "-s", "KILL", Integer.toString(maxSeconds) };
            if (args.length <= expected.length) throw new IllegalStateException();
            for (int i = 0; i < expected.length; i++) {
                if (!expected[i].equals(args[i])) throw new IllegalStateException();
            }
        }

        JSONObject marker(String phase, long writes) throws Exception {
            JSONObject result = identity.json().put("schema", 1).put("runId", runId).put("key", key)
                    .put("userId", 0).put("role", child ? "child" : "producer").put("variant", variant)
                    .put("phase", phase).put("sequence", ++sequence).put("writes", writes)
                    .put("termIgnored", ignoreTerm).put("watchdogSeconds", maxSeconds)
                    .put("startedElapsedMs", startedElapsedMs).put("elapsedMs", SystemClock.elapsedRealtime())
                    .put("ownerUid", ownerUid).put("watchdog", watchdog.json());
            if (childReady != null) result.put("child", childReady);
            return result;
        }

        void validateDirectory(File file) throws Exception {
            validateDirectory(file, false);
        }

        void validateDirectory(File file, boolean platformDirectory) throws Exception {
            StructStat stat = Os.lstat(file.getAbsolutePath());
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != ownerUid || stat.st_gid != ownerGid
                    || (stat.st_mode & (platformDirectory ? 0002 : 0022)) != 0) throw new IllegalStateException();
        }

        void validateFreshMarkers() throws Exception {
            File[] files = directory.listFiles();
            if (files == null) throw new IllegalStateException();
            for (File file : files) {
                if (!EMPTY_MARKERS.contains(file.getName())) throw new IllegalStateException();
                StructStat stat = validateMarker(file);
                if (stat.st_size != 0) throw new IllegalStateException();
            }
        }

        StructStat validateMarker(File file) throws Exception {
            validateDirectory(directory);
            StructStat stat = Os.lstat(file.getAbsolutePath());
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != ownerUid || stat.st_gid != ownerGid
                    || stat.st_nlink != 1 || (stat.st_mode & 0077) != 0) throw new IllegalStateException();
            return stat;
        }

        boolean completionRequested() throws Exception {
            File complete = new File(directory, "complete");
            try {
                if (validateMarker(complete).st_size != 0) throw new IllegalStateException();
                return true;
            } catch (ErrnoException error) {
                if (error.errno != OsConstants.ENOENT) throw error;
                return false;
            }
        }

        JSONObject readMarker(String name) throws Exception {
            File file = new File(directory, name);
            validateMarker(file);
            return new JSONObject(new String(readSmall(file, 16384), StandardCharsets.UTF_8));
        }

        void writeMarker(String name, JSONObject value) throws Exception {
            stage = "markers";
            validateDirectory(directory);
            File target = new File(directory, name);
            try { validateMarker(target); }
            catch (ErrnoException error) { if (error.errno != OsConstants.ENOENT) throw error; }
            File temporary = new File(directory, "." + name + ".tmp");
            FileDescriptor descriptor = Os.open(temporary.getAbsolutePath(),
                    OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
            try (FileOutputStream output = new FileOutputStream(descriptor)) {
                Os.fchown(descriptor, ownerUid, ownerGid);
                output.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            FileDescriptor directoryDescriptor = Os.open(directory.getAbsolutePath(),
                    OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(directoryDescriptor).st_mode)) throw new IllegalStateException();
                Os.fsync(directoryDescriptor);
            } finally { Os.close(directoryDescriptor); }
        }

        void reportFailure() {
            String failedStage = stage;
            try { writeMarker(child ? "child-error.json" : "error.json", marker("ERROR", -1).put("stage", failedStage)); }
            catch (Throwable ignored) { /* No exception details or Settings values leave the helper. */ }
            finally { stage = failedStage; }
        }
    }

    static final class Identity {
        final int pid;
        final int ppid;
        final int pgid;
        final int uid;
        final long startTicks;
        final String bootId;

        Identity(int pid, int ppid, int pgid, int uid, long startTicks, String bootId) {
            this.pid = pid;
            this.ppid = ppid;
            this.pgid = pgid;
            this.uid = uid;
            this.startTicks = startTicks;
            this.bootId = bootId;
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("pid", pid).put("ppid", ppid).put("pgid", pgid)
                    .put("uid", uid).put("startTicks", startTicks).put("bootId", bootId);
        }
    }

    static Identity identity(int pid) throws Exception {
        if (pid <= 1) throw new IllegalArgumentException();
        String stat = new String(readSmall(new File("/proc/" + pid + "/stat"), 8192), StandardCharsets.UTF_8).trim();
        int end = stat.lastIndexOf(')');
        if (end < 0 || !stat.substring(0, stat.indexOf(' ')).equals(Integer.toString(pid)))
            throw new IllegalStateException();
        String[] fields = stat.substring(end + 2).split(" +");
        if (fields.length < 20 || fields[0].equals("Z") || fields[0].equals("X")) throw new IllegalStateException();
        int uid = -1;
        String status = new String(readSmall(new File("/proc/" + pid + "/status"), 16384), StandardCharsets.UTF_8);
        for (String line : status.split("\n")) {
            if (line.startsWith("Uid:")) uid = Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
        }
        String bootId = new String(readSmall(new File("/proc/sys/kernel/random/boot_id"), 128), StandardCharsets.UTF_8).trim();
        if (!UUID.fromString(bootId).toString().equals(bootId) || uid < 0) throw new IllegalStateException();
        return new Identity(pid, Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), uid,
                Long.parseLong(fields[19]), bootId);
    }

    static boolean sameIdentity(JSONObject a, JSONObject b) throws Exception {
        return a.getInt("pid") == b.getInt("pid") && a.getLong("startTicks") == b.getLong("startTicks")
                && a.getInt("pgid") == b.getInt("pgid") && a.getInt("uid") == b.getInt("uid")
                && a.getString("bootId").equals(b.getString("bootId"));
    }

    static boolean termIgnored() throws Exception {
        String status = new String(readSmall(new File("/proc/self/status"), 16384), StandardCharsets.UTF_8);
        for (String line : status.split("\n")) {
            if (line.startsWith("SigIgn:"))
                return (Long.parseUnsignedLong(line.substring(7).trim(), 16) & (1L << 14)) != 0;
        }
        throw new IllegalStateException();
    }

    static byte[] readSmall(File file, int limit) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IllegalStateException();
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    // Same app_process-only provider attribution setup as SettingsEditorBridge. This probe never
    // enumerates a table: the only calls are PUT_system and GET_system for its validated literal key.
    // Framework reflection executes only in root app_process, never Thor's application runtime.
    @android.annotation.SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi"})
    static ContentProviderClient settingsClient() throws Exception {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        for (String name : new String[] { "mOpPackageName", "mBasePackageName" }) {
            java.lang.reflect.Field field = context.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(context, "com.android.shell");
        }
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
            holder = acquire.invoke(am, "settings", 0, token, "ThorSettLiveTest");
        } else {
            java.lang.reflect.Method acquire = am.getClass().getMethod("getContentProviderExternal", String.class, int.class, android.os.IBinder.class);
            acquire.setAccessible(true);
            holder = acquire.invoke(am, "settings", 0, token);
        }
        Object provider = holder.getClass().getField("provider").get(holder);
        java.lang.reflect.Constructor<?> constructor = Class.forName("android.content.ContentProviderClient").getDeclaredConstructor(
                android.content.ContentResolver.class, Class.forName("android.content.IContentProvider"), boolean.class);
        constructor.setAccessible(true);
        ContentProviderClient client = (ContentProviderClient) constructor.newInstance(context.getContentResolver(), provider, true);
        if (android.os.Build.VERSION.SDK_INT < 31) {
            java.lang.reflect.Field packageName = client.getClass().getDeclaredField("mPackageName");
            packageName.setAccessible(true);
            packageName.set(client, "com.android.shell");
        } else {
            java.lang.reflect.Field attribution = client.getClass().getDeclaredField("mAttributionSource");
            attribution.setAccessible(true);
            java.lang.reflect.Field source = context.getClass().getDeclaredField("mAttributionSource");
            source.setAccessible(true);
            attribution.set(client, source.get(context));
        }
        return client;
    }

    static Bundle call(ContentProviderClient client, String method, String key, Bundle extras) throws Exception {
        if (android.os.Build.VERSION.SDK_INT >= 29) return client.call("settings", method, key, extras);
        return client.call(method, key, extras);
    }
}
