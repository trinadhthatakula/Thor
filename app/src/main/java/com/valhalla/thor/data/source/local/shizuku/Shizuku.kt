// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.valhalla.bypass.Bypass
import com.valhalla.superuser.utils.escapeForShell
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.valhalla.thor.util.Logger

object Shizuku {

    /**
     * Hang backstop for a single privileged command: a stuck child is killed instead of pinning
     * the caller forever. Deliberately generous (5 min) because valid slow operations run through
     * here — notably `pm install` of large/split APKs on slow devices — and must not be killed.
     * This bounds infinite hangs, it does NOT enforce a tight SLA.
     */
    private const val EXECUTE_TIMEOUT_MS = 300_000L

    /** Grace period for reader threads to drain their streams after the process has exited/been destroyed. */
    private const val READER_JOIN_TIMEOUT_MS = 5_000L

    val isRoot get() = Shizuku.getUid() == 0

    private fun asInterface(className: String, original: IBinder): Any {
        val clazz = Class.forName("$className\$Stub")
        return Bypass.invoke(
            clazz,
            null,
            "asInterface",
            arrayOf(IBinder::class.java),
            ShizukuBinderWrapper(original)
        )
    }

    private fun asInterface(className: String, serviceName: String): Any =
        asInterface(className, SystemServiceHelper.getSystemService(serviceName))

    val lockScreen
        get() = runCatching {
            execute("input keyevent 26").first == 0
        }.getOrElse {
            Logger.e("Shizuku", "lockScreen event trigger failed", it)
            false
        }

    // killBackgroundProcesses' KILL_BACKGROUND_PROCESSES permission is satisfied via the elevated
    // Shizuku privilege (root shell / Shizuku), not a manifest grant.
    @SuppressLint("MissingPermission")
    fun forceStopApp(context: Context, packageName: String): Boolean {
        val pkgs = Packages(context)
        val userId = pkgs.myUserId
        // 1. Try shell first
        val result = execute("am force-stop --user $userId $packageName")
        if (result.first == 0) return true

        // 2. Fallback to reflection
        val reflectionResult = runCatching {
            val am = asInterface("android.app.IActivityManager", Context.ACTIVITY_SERVICE)
            Bypass.invoke<Any?>(
                am::class.java, am, "forceStopPackage", packageName, userId
            )
            true
        }.getOrElse {
            Logger.e("Shizuku", "forceStopApp reflection failed for $packageName", it)
            false
        }
        if (reflectionResult) return true

        // 3. Unprivileged fallback (re-query PM to observe post-mutation state)
        if (pkgs.isAppStopped(packageName)) return true
        runCatching {
            val am =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.killBackgroundProcesses(packageName)
        }
        return pkgs.isAppStopped(packageName)
    }

    fun setAppDisabled(context: Context, packageName: String, disabled: Boolean): Boolean {
        val pkgs = Packages(context)
        pkgs.getApplicationInfoOrNull(packageName) ?: return false
        val userId = pkgs.myUserId

        // 1. Try shell first
        val command = if (disabled) {
            "pm disable-user --user $userId $packageName"
        } else {
            "pm enable --user $userId $packageName"
        }
        val result = execute(command)
        if (result.first == 0 && pkgs.isAppDisabled(packageName) == disabled) {
            return true
        }

        // 2. Fallback to Bypass reflection
        val reflectionResult = runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val newState = when {
                !disabled -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                isRoot -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else -> android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            }
            val caller = if (isRoot) context.packageName else "com.android.shell"
            Bypass.invoke<Any?>(
                pm.javaClass,
                pm,
                "setApplicationEnabledSetting",
                arrayOf(
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java
                ),
                packageName,
                newState,
                0,
                userId,
                caller
            )
            true
        }.getOrDefault(false)

        if (reflectionResult && pkgs.isAppDisabled(packageName) == disabled) {
            return true
        }

        // 3. Unprivileged fallback (re-query PM to observe post-mutation state)
        if (pkgs.isAppDisabled(packageName) == disabled) {
            return true
        }
        val newState = if (disabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        runCatching {
            context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
        }

        return pkgs.isAppDisabled(packageName) == disabled
    }

    // Hidden-API reflection (SuspendDialogInfo) is intentional: it is the core privilege mechanism,
    // guarded by the :bypass VMRuntime unseal.
    @SuppressLint("PrivateApi")
    fun setAppSuspended(context: Context, packageName: String, suspended: Boolean): Boolean {
        val pkgs = Packages(context)
        pkgs.getApplicationInfoOrNull(packageName) ?: return false
        val userId = pkgs.myUserId

        // 1. Try shell first
        val command = if (suspended) {
            "pm suspend --user $userId $packageName"
        } else {
            "pm unsuspend --user $userId $packageName"
        }
        val shellResult = execute(command)
        if (shellResult.first == 0) return true

        // 2. Fallback to reflection
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val reflectionResult = runCatching {
                val pm = asInterface("android.content.pm.IPackageManager", "package")
                val dialogInfoClass = Class.forName("android.content.pm.SuspendDialogInfo")
                val builderClass = Class.forName("android.content.pm.SuspendDialogInfo\$Builder")
                val dialogInfo = if (suspended) {
                    Bypass.newInstance<Any>(builderClass).let { b ->
                        val title = context.getString(com.valhalla.thor.R.string.suspended_app_dialog_title)
                        val message = context.getString(com.valhalla.thor.R.string.suspended_app_dialog_message)
                        Bypass.invoke<Any>(builderClass, b, "setTitle", title)
                        Bypass.invoke<Any>(
                            builderClass,
                            b,
                            "setMessage",
                            message
                        )
                        Bypass.invoke<Any>(builderClass, b, "build")
                    }
                } else {
                    null
                }

                val caller =
                    if (isRoot) com.valhalla.thor.BuildConfig.APPLICATION_ID else "com.android.shell"

                try {
                    // Try Android 13+ (8 args)
                    Bypass.invoke<Array<String>>(
                        pm.javaClass,
                        pm,
                        "setPackagesSuspendedAsUser",
                        arrayOf(
                            Array<String>::class.java,
                            Boolean::class.javaPrimitiveType!!,
                            android.os.PersistableBundle::class.java,
                            android.os.PersistableBundle::class.java,
                            dialogInfoClass,
                            Int::class.javaPrimitiveType!!,
                            String::class.java,
                            Int::class.javaPrimitiveType!!
                        ),
                        arrayOf(packageName),
                        suspended,
                        null, null,
                        dialogInfo,
                        0,
                        caller,
                        userId
                    )
                } catch (_: NoSuchMethodException) {
                    // Try Android 10-12 (7 args)
                    Bypass.invoke<Array<String>>(
                        pm.javaClass,
                        pm,
                        "setPackagesSuspendedAsUser",
                        arrayOf(
                            Array<String>::class.java,
                            Boolean::class.javaPrimitiveType!!,
                            android.os.PersistableBundle::class.java,
                            android.os.PersistableBundle::class.java,
                            dialogInfoClass,
                            String::class.java,
                            Int::class.javaPrimitiveType!!
                        ),
                        arrayOf(packageName),
                        suspended,
                        null, null,
                        dialogInfo,
                        caller,
                        userId
                    )
                }
                true
            }.getOrDefault(false)

            if (reflectionResult) return true
        }

        // 3. Unprivileged fallback (re-query PM to observe post-mutation state)
        val currentSuspended = pkgs.getApplicationInfoOrNull(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
        } ?: false
        return currentSuspended == suspended
    }

    // PrivateApi: hidden-API reflection (IPackageDataObserver) is intentional — the core privilege
    // mechanism, guarded by the :bypass VMRuntime unseal.
    // SdCardPath: the absolute /data and /sdcard paths are intentional for privileged/root file ops,
    // not app-scoped storage.
    @SuppressLint("PrivateApi", "SdCardPath")
    fun clearCache(packageName: String): Boolean {
        // 1. Try shell first
        val userId = android.os.Process.myUserHandle().hashCode()
        val paths = listOf(
            "/data/data/$packageName/cache",
            "/data/user/$userId/$packageName/cache",
            "/sdcard/Android/data/$packageName/cache"
        )
        val command = "rm -rf ${paths.joinToString(" ")}"
        val shellResult = execute(command)
        if (shellResult.first == 0) return true

        // 2. Fallback to reflection
        val reflectionResult = runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")

            try {
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFiles",
                    arrayOf(String::class.java, observerClass),
                    packageName,
                    null
                )
            } catch (_: NoSuchMethodException) {
                Bypass.invoke<Any?>(
                    pm.javaClass,
                    pm,
                    "deleteApplicationCacheFilesAsUser",
                    arrayOf(String::class.java, Int::class.javaPrimitiveType!!, observerClass),
                    packageName,
                    userId,
                    null
                )
            }
            true
        }.getOrDefault(false)

        return reflectionResult
    }

    // Hidden-API reflection (IPackageDataObserver) is intentional: it is the core privilege
    // mechanism, guarded by the :bypass VMRuntime unseal.
    @SuppressLint("PrivateApi")
    fun clearAppData(packageName: String): Boolean {
        // 1. Try shell first
        val result = execute("pm clear $packageName")
        if (result.first == 0) return true

        // 2. Fallback to reflection
        return runCatching {
            val pm = asInterface("android.content.pm.IPackageManager", "package")
            val observerClass = Class.forName("android.content.pm.IPackageDataObserver")
            Bypass.invoke<Any?>(
                pm.javaClass,
                pm,
                "clearApplicationUserData",
                arrayOf(String::class.java, observerClass, Int::class.javaPrimitiveType!!),
                packageName,
                null,
                android.os.Process.myUserHandle().hashCode()
            )
            true
        }.getOrElse { false }
    }

    fun getTotalCacheSizeWithShizuku(): Long {
        var totalCacheBytes = 0L
        val result = execute("dumpsys diskstats")

        result.second?.lines()?.forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("Cache Size:")) {
                try {
                    val sizeString = trimmedLine.substringAfter(":").trim()
                    val bytes =
                        NumberFormat.getNumberInstance(Locale.US).parse(sizeString)?.toLong() ?: 0L
                    totalCacheBytes += bytes
                } catch (e: Exception) {
                    Logger.e("Shizuku", "Failed to parse cache size line: $trimmedLine", e)
                }
            }
        }
        return totalCacheBytes
    }

    fun setAppRestricted(context: Context, packageName: String, restricted: Boolean): Boolean {
        // 1. Try shell first
        val result =
            execute("appops set $packageName RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}")
        if (result.first == 0) return true

        // 2. Fallback to reflection
        return runCatching {
            val appops =
                asInterface("com.android.internal.app.IAppOpsService", Context.APP_OPS_SERVICE)
            val uid = Packages(context).packageUid(packageName)
            Bypass.invoke<Any?>(
                appops::class.java,
                appops,
                "setMode",
                arrayOf(
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                    Int::class.javaPrimitiveType!!
                ),
                Bypass.invoke<Int>(
                    android.app.AppOpsManager::class.java,
                    null,
                    "strOpToOp",
                    "android:run_any_in_background"
                ),
                uid,
                packageName,
                if (restricted) android.app.AppOpsManager.MODE_IGNORED else android.app.AppOpsManager.MODE_ALLOWED
            )
            true
        }.getOrElse { false }
    }

    // @Volatile guarantees safe publication across threads: getCurrentUserId() may be invoked from
    // arbitrary threads, and only a *successfully* resolved id is ever cached (the read throws
    // before the assignment on failure), so a transient shell blip can't persist a wrong user (#41,
    // mirrors the RootSystemGateway #34 pattern).
    @Volatile
    private var cachedUserId: String? = null

    fun getCurrentUserId(): String {
        cachedUserId?.let { return it }
        val userResult = execute("am get-current-user")
        val output = userResult.second?.trim()
        if (userResult.first != 0 || output == null || !output.matches(Regex("^\\d+$"))) {
            throw IllegalStateException("Failed to determine current user ID: exitCode=${userResult.first}, output=$output")
        }
        cachedUserId = output
        return output
    }

    fun uninstallApp(context: Context, packageName: String): Boolean {
        // Escape the package identifier before interpolating it into the shell command, mirroring
        // the Dhizuku helper (#40). currentUser is regex-validated numeric, so it needs no escaping.
        val escapedPackage = packageName.escapeForShell()
        val normally = Packages(context).canUninstallNormally(packageName)
        if (normally) {
            return execute("pm uninstall $escapedPackage").first == 0
        }
        return try {
            val currentUser = getCurrentUserId()
            execute("pm uninstall --user $currentUser $escapedPackage").first == 0
        } catch (_: Exception) {
            false
        }
    }

    fun reinstallApp(packageName: String): Boolean {
        return try {
            val currentUser = getCurrentUserId()
            // Escape the package identifier before interpolating it (#40).
            val escapedPackage = packageName.escapeForShell()
            execute("pm install-existing --user $currentUser $escapedPackage").first == 0
        } catch (_: Exception) {
            false
        }
    }

    fun execute(command: String, root: Boolean = isRoot): Pair<Int, String?> = runCatching {
        val binder = Shizuku.getBinder() ?: return -1 to "Shizuku binder is null"
        IShizukuService.Stub.asInterface(binder)
            .newProcess(arrayOf(if (root) "su" else "sh"), null, null)
            .run {
                // Volatile via AtomicReference: the reader threads publish into these, and the
                // timeout path may read them after a join() that timed out (no happens-before),
                // so a plain local var could observe a stale/torn value.
                val output = java.util.concurrent.atomic.AtomicReference("")
                val error = java.util.concurrent.atomic.AtomicReference("")

                // Daemon so a stuck read on a hung child can never keep the process/VM alive.
                val outThread = Thread {
                    runCatching {
                        output.set(inputStream.text)
                    }.onFailure { err ->
                        Logger.e("Shizuku", "Failed to read standard output", err)
                    }
                }.apply { isDaemon = true }

                val errThread = Thread {
                    runCatching {
                        error.set(errorStream.text)
                    }.onFailure { err ->
                        Logger.e("Shizuku", "Failed to read error output", err)
                    }
                }.apply { isDaemon = true }

                var timedOut = false
                try {
                    outThread.start()
                    errThread.start()

                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(outputStream).use {
                            it.write((command + "\nexit\n").toByteArray())
                            it.flush()
                        }
                    }.onFailure { err ->
                        Logger.e("Shizuku", "Failed to write command to process outputStream", err)
                    }

                    // Bounded wait: waitForTimeout is a synchronous binder transact() that does
                    // NOT respond to Thread.interrupt(). The ONLY bound is EXECUTE_TIMEOUT_MS; this
                    // is a hang backstop, not coroutine-cancellation-interruptible. The
                    // InterruptedException catch below is harmless defensive code, not a live path.
                    val exited = try {
                        waitForTimeout(EXECUTE_TIMEOUT_MS, TimeUnit.MILLISECONDS.name)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Logger.e("Shizuku", "Command wait interrupted: $command", e)
                        false
                    }

                    if (exited) {
                        val exitCode = waitFor()
                        // Give the readers a bounded window to drain, then stop waiting on them.
                        outThread.join(READER_JOIN_TIMEOUT_MS)
                        errThread.join(READER_JOIN_TIMEOUT_MS)
                        exitCode to output.get().ifBlank { error.get() }
                    } else {
                        timedOut = true
                        Logger.e(
                            "Shizuku",
                            "Command timed out after ${EXECUTE_TIMEOUT_MS}ms, destroying process: $command"
                        )
                        // Close the FDs first: this unblocks the reader threads immediately,
                        // even if destroy() (a binder call) later hangs. Killing before closing
                        // would block the timeout path on a stuck destroy while readers stay stuck.
                        runCatching { inputStream.close() }
                        runCatching { errorStream.close() }
                        runCatching { outputStream.close() }
                        outThread.interrupt()
                        errThread.interrupt()
                        outThread.join(READER_JOIN_TIMEOUT_MS)
                        errThread.join(READER_JOIN_TIMEOUT_MS)
                        // Readers are already free; now request the (possibly slow) kill.
                        runCatching { destroy() }
                        -1 to "Command timed out after ${EXECUTE_TIMEOUT_MS}ms".let { msg ->
                            output.get().ifBlank { error.get() }.ifBlank { msg }
                        }
                    }
                } finally {
                    // Always tear the process down (idempotent even if already destroyed on timeout).
                    if (!timedOut) runCatching { destroy() }
                    // Close the FDs explicitly (each guarded): releases file descriptors and
                    // unblocks the reader threads even if destroy() (a binder call) hangs or fails.
                    runCatching { inputStream.close() }
                    runCatching { errorStream.close() }
                    runCatching { outputStream.close() }
                }
            }
    }.getOrElse { err ->
        Logger.e("Shizuku", "Command execution failed: $command", err)
        -1 to err.stackTraceToString()
    }

    private val ParcelFileDescriptor.text
        get() = ParcelFileDescriptor.AutoCloseInputStream(this)
            .use { it.bufferedReader().readText() }
}
