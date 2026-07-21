package com.valhalla.thor.data.gateway

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.valhalla.superuser.ipc.RootService
import com.valhalla.superuser.ShellUtils
import com.valhalla.thor.rootservice.IThorRootService
import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.domain.gateway.SystemGateway
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Single
import java.io.File
import kotlin.coroutines.resume

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
private val USER_ID_REGEX = Regex("^\\d+$")

// Upper bound for the RootService bind handshake. A null binder or a callback that never
// arrives must not pin connectionMutex forever and deadlock every later privileged op (H2).
private const val ROOT_SERVICE_BIND_TIMEOUT_MS = 10_000L

/**
 * Modern implementation of SystemGateway using the reactive ShellRepository.
 * No more static blocking calls.
 */
@Single
class RootSystemGateway(
    private val context: Context,
    private val shellRepository: ShellRepository,
    private val preferenceRepository: PreferenceRepository
) : SystemGateway {

    private var rootService: IThorRootService? = null
    private val connectionMutex = Mutex()
    private var isDaemonReset = false
    private var activeConnection: ServiceConnection? = null

    private suspend fun getRootService(): IThorRootService? = connectionMutex.withLock {
        if (!isDaemonReset) {
            isDaemonReset = true
            // Kill any old daemon to make sure the newly compiled suCore is loaded and executed
            runCatching {
                shellRepository.runCommand("pkill -f ${context.packageName}:root")
            }
        }

        rootService?.let { binder ->
            if (binder.asBinder().isBinderAlive) {
                return binder
            } else {
                rootService = null
                activeConnection?.let { oldConn ->
                    runCatching { RootService.unbind(oldConn) }
                    activeConnection = null
                }
            }
        }

        // Clean up any stale connection before creating a new one
        activeConnection?.let { oldConn ->
            runCatching {
                RootService.unbind(oldConn)
            }
            activeConnection = null
        }

        // Bind under a timeout so a null binder or a callback that never arrives can't hold
        // connectionMutex forever (H2). withTimeoutOrNull RETURNS null on timeout — it does not
        // throw — so on every path (success, null-binding, or timeout) withLock unwinds and the
        // mutex is released. On timeout the child coroutine is cancelled, which fires
        // invokeOnCancellation below to unbind the stale connection; the caller then falls back.
        withTimeoutOrNull(ROOT_SERVICE_BIND_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val intent = Intent(context, com.valhalla.thor.rootservice.ThorRootService::class.java)
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            val binder = IThorRootService.Stub.asInterface(service)
                            // Publish/resume only if the bind hasn't already timed out. A late
                            // connect (continuation cancelled by withTimeoutOrNull) would otherwise
                            // cache a service whose ServiceConnection is about to be unbound by
                            // invokeOnCancellation, leaving rootService dangling (-> intermittent
                            // DeadObjectException on the next call).
                            if (continuation.isActive) {
                                rootService = binder
                                continuation.resume(binder)
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            rootService = null
                            // A dead service can no longer answer '--user <id>'; drop the cached
                            // user id so a reconnect re-reads the (possibly switched) user (#34).
                            cachedUserId = null
                            userIdGeneration++
                            if (activeConnection === this) {
                                activeConnection = null
                            }
                        }

                        // The root process returned a null binder — the service refused to bind.
                        // Resume with null (and unbind) instead of hanging until the timeout fires.
                        override fun onNullBinding(name: ComponentName?) {
                            rootService = null
                            runCatching { RootService.unbind(this) }
                            if (activeConnection === this) {
                                activeConnection = null
                            }
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }

                    activeConnection = conn

                    continuation.invokeOnCancellation {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            runCatching {
                                RootService.unbind(conn)
                            }
                            if (activeConnection === conn) {
                                activeConnection = null
                            }
                        }
                    }

                    RootService.bind(intent, conn)
                }
            }
        }
    }

    // A root check is strictly asynchronous. Blocking the thread for this is unacceptable.
    override suspend fun isRootAvailable(): Boolean {
        return shellRepository.isRootGranted()
    }

    override suspend fun isShizukuAvailable(): Boolean = false
    override suspend fun isDhizukuAvailable(): Boolean = false

    // killBackgroundProcesses' KILL_BACKGROUND_PROCESSES is satisfied via elevated privilege
    // (root shell) rather than a manifest grant.
    @SuppressLint("MissingPermission")
    override suspend fun forceStopApp(packageName: String): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = ShellUtils.escapedString(packageName)
        val shellResult = runCommand("am force-stop $escapedPackage")
        if (shellResult.isSuccess) return shellResult

        // Unprivileged check/fallback
        val isStopped = getApplicationInfoCompat(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        } ?: false
        if (isStopped) return Result.success(Unit)

        runCatching {
            val am =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.killBackgroundProcesses(packageName)
        }

        val postCheck = getApplicationInfoCompat(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        } ?: false
        if (postCheck) return Result.success(Unit)

        return Result.failure(Exception("Root force stop failed. Shell command failed and app is still running."))
    }

    override suspend fun clearCache(packageName: String): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = ShellUtils.escapedString(packageName)
        val command = "rm -rf /data/data/$escapedPackage/cache /sdcard/Android/data/$escapedPackage/cache"
        return runCommand(command)
    }

    override suspend fun clearAppData(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = ShellUtils.escapedString(packageName)
        val shellResult = runCommand("pm clear $escapedPackage")
        if (shellResult.isSuccess) return@withContext shellResult

        // Fallback to ThorRootService AIDL daemon
        val service = getRootService()
        if (service != null) {
            val aidlResult = runCatching {
                service.clearAppData(packageName)
            }.onFailure { e ->
                Logger.e("RootSystemGateway", "AIDL clearAppData failed", e)
            }
            if (aidlResult.isSuccess) {
                return@withContext Result.success(Unit)
            }
        }

        return@withContext Result.failure(Exception("Root clear app data failed. Shell command and AIDL both failed."))
    }

    override suspend fun setAppDisabled(packageName: String, isDisabled: Boolean): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val appInfo = getApplicationInfoCompat(packageName)
        val isSystem = appInfo != null && (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val escapedPackage = ShellUtils.escapedString(packageName)
        val currentUser = getCurrentUserId()

        val shellResult = if (isSystem) {
            if (isDisabled) {
                runCommand("pm uninstall --user $currentUser $escapedPackage")
            } else {
                @Suppress("SENSELESS_COMPARISON")
                if (appInfo != null) {
                    val currentInstalled = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
                    val currentEnabled = appInfo.enabled && currentInstalled
                    if (currentInstalled && !currentEnabled) {
                        runCommand("pm enable $escapedPackage")
                    }
                }
                runCommand("pm install-existing --user $currentUser $escapedPackage")
            }
        } else {
            val state = if (isDisabled) "disable" else "enable"
            runCommand("pm $state $escapedPackage")
        }

        if (shellResult.isSuccess) return shellResult

        // Check if already in the target state
        if (appInfo != null) {
            val currentInstalled = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
            val currentEnabled = appInfo.enabled && currentInstalled
            val currentDisabled = !currentEnabled
            if (currentDisabled == isDisabled) return Result.success(Unit)
        }

        // Try unprivileged API as fallback (only for non-system apps)
        if (!isSystem) {
            val newState = if (isDisabled) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            val unprivilegedResult = runCatching {
                context.packageManager.setApplicationEnabledSetting(packageName, newState, 0)
            }
            if (unprivilegedResult.isSuccess) {
                val postAppInfo = getApplicationInfoCompat(packageName)
                if (postAppInfo != null) {
                    val postInstalled = (postAppInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
                    val postEnabled = postAppInfo.enabled && postInstalled
                    val postDisabled = !postEnabled
                    if (postDisabled == isDisabled) return Result.success(Unit)
                }
            }
        }

        return Result.failure(Exception("Root setAppDisabled failed."))
    }

    override suspend fun setAppSuspended(packageName: String, isSuspended: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val hasReflection = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val escapedPackage = ShellUtils.escapedString(packageName)

        fun isCurrentlySuspended() = getApplicationInfoCompat(packageName)?.run {
            (flags and android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
        } ?: false

        if (isSuspended) {
            // SUSPEND via the reflection path only: setPackagesSuspendedAsUser(caller = our app id).
            // A root-shell `pm suspend` (uid 0) records the suspender as "root", a non-existent
            // package, so tapping the paused app crashes SuspendedAppActivity
            // ("IllegalArgumentException: Package root does not exist"). We never fall back to the
            // shell for suspend — a broken suspension is worse than a reported failure. GH#239.
            if (hasReflection) {
                val service = getRootService()
                if (service != null) {
                    val taskResult = runCatching {
                        service.setAppSuspended(packageName, true)
                        true
                    }.onFailure { e ->
                        Logger.e("RootSystemGateway", "AIDL suspend failed", e)
                    }.getOrDefault(false)
                    if (taskResult || isCurrentlySuspended()) return@withContext Result.success(Unit)
                }
                return@withContext Result.failure(Exception("Root suspend failed via AIDL for $packageName."))
            }
            // API < 29 has no SuspendDialogInfo reflection path.
            val shell = runCommand("pm suspend $escapedPackage")
            return@withContext if (shell.isSuccess) shell
            else Result.failure(Exception("Root suspend failed for $packageName."))
        }

        // UNSUSPEND: a suspension can only be lifted by the package that set it, so clear BOTH
        // possible owners — our own app (reflection, for suspensions this app set) AND
        // "root"/"shell" (shell `pm unsuspend`, for any legacy suspension left by older builds).
        // A root-shell `pm unsuspend` alone reports success yet leaves an app suspended by us still
        // suspended. GH#239.
        var cleared = false
        if (hasReflection) {
            val service = getRootService()
            if (service != null) {
                cleared = runCatching {
                    service.setAppSuspended(packageName, false)
                    true
                }.onFailure { e ->
                    Logger.e("RootSystemGateway", "AIDL unsuspend failed", e)
                }.getOrDefault(false)
            }
        }
        val shell = runCommand("pm unsuspend $escapedPackage")
        cleared = cleared || shell.isSuccess
        return@withContext if (cleared || !isCurrentlySuspended()) Result.success(Unit)
        else Result.failure(Exception("Root unsuspend failed for $packageName."))
    }

    override suspend fun setAppRestricted(
        packageName: String,
        isRestricted: Boolean
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val escapedPackage = ShellUtils.escapedString(packageName)
        val state = if (isRestricted) "ignore" else "allow"
        return runCommand("appops set $escapedPackage RUN_ANY_IN_BACKGROUND $state")
    }

    override suspend fun rebootDevice(reason: String): Result<Unit> {
        val escapedReason = ShellUtils.escapedString(reason)
        // executeResult returns success if ANY of the commands succeed in the chain logic
        return runCommand("svc power reboot $escapedReason || reboot $escapedReason")
    }

    override suspend fun uninstallApp(packageName: String): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }
        val currentUser = getCurrentUserId()
        val escapedPackage = ShellUtils.escapedString(packageName)
        return runCommand("pm uninstall --user $currentUser $escapedPackage")
    }

    override suspend fun installApp(apkPath: String, canDowngrade: Boolean): Result<Unit> {
        return installViaSession(listOf(apkPath), canDowngrade)
    }

    suspend fun installMultipleApks(apkPaths: List<String>, canDowngrade: Boolean): Result<Unit> {
        return installViaSession(apkPaths, canDowngrade)
    }

    /**
     * Install one or more APKs through a PackageInstaller *session*, streaming each
     * file's bytes into the session over stdin (`cat <apk> | pm install-write … -`).
     *
     * The old `pm install <path>` / `pm install-multiple <paths>` handed the system
     * installer a path under the app's private cache (`/data/data/…`). On modern
     * Android the installer can't read another app's private files, so `pm` aborted
     * with exit 255 (GH#159). Here the root shell's own `cat` reads the app-private
     * temp file and pipes the bytes in, so neither `pm` nor `installd` ever opens a
     * `/data/data` path — the install works regardless of where the temp APKs live.
     * On failure the real `pm` reason is routed to stderr so it surfaces in the error.
     */
    private suspend fun installViaSession(
        apkPaths: List<String>,
        canDowngrade: Boolean
    ): Result<Unit> {
        if (apkPaths.isEmpty()) {
            return Result.failure(Exception("No APK paths provided for install"))
        }
        // Abort before opening a session if any APK is missing/unreadable: otherwise a
        // 0-byte File.length() below would stream `-S 0` into pm install-write and only
        // fail later at commit with a cryptic reason.
        apkPaths.firstOrNull { File(it).length() == 0L }?.let {
            return Result.failure(Exception("APK file is missing or empty: $it"))
        }
        val currentUser = getCurrentUserId()
        val downgrade = if (canDowngrade) " -d" else ""
        
        val installerArg = preferenceRepository.getInstallerArg()

        val sb = StringBuilder()
        // Run the whole thing in a subshell so our `exit` codes exit the SUBSHELL, not
        // libsu's long-lived root shell. Exiting the parent shell would kill it before
        // libsu appends its end-marker, leaving it unable to read the real exit code
        // (it then falls back to code 1) — and would break every later root command.
        sb.append("(\n")
        // pipefail so a failed `cat` (missing/unreadable APK) in the install-write
        // pipeline below propagates to the || abort branch instead of being masked by
        // pm install-write's exit code.
        sb.append("set -o pipefail\n")
        // Create the session (targeting the current user, like every other pm command in
        // this gateway); capture stdout+stderr so a failure reason isn't lost, then pull
        // the numeric id out of "…created install session [<id>]".
        sb.append("CREATE_OUT=\$(pm install-create -r -g").append(installerArg).append(" --user ").append(currentUser).append(downgrade).append(" 2>&1)\n")
        sb.append("SID=\$(printf '%s\\n' \"\$CREATE_OUT\" | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p')\n")
        sb.append("if [ -z \"\$SID\" ]; then echo \"pm install-create failed: \$CREATE_OUT\" 1>&2; exit 101; fi\n")
        // Stream each APK's bytes into the session via stdin.
        for (path in apkPaths) {
            val size = File(path).length()
            val escPath = ShellUtils.escapedString(path)
            val escName = ShellUtils.escapedString(File(path).name)
            sb.append("WERR=\$(cat ").append(escPath)
                .append(" | pm install-write -S ").append(size)
                .append(" \"\$SID\" ").append(escName).append(" - 2>&1 1>/dev/null)")
                .append(" || { pm install-abandon \"\$SID\" 2>/dev/null;")
                .append(" echo \"pm install-write failed: \$WERR\" 1>&2; exit 102; }\n")
        }
        // Commit; anything but a Success line is a failure — surface pm's reason.
        sb.append("COMMIT=\$(pm install-commit \"\$SID\" 2>&1)\n")
        sb.append("case \"\$COMMIT\" in\n")
        sb.append("  *Success*) exit 0 ;;\n")
        sb.append("  *) pm install-abandon \"\$SID\" 2>/dev/null;")
            .append(" echo \"pm install-commit failed: \$COMMIT\" 1>&2; exit 103 ;;\n")
        sb.append("esac\n")
        sb.append(")\n")
        return runCommand(sb.toString())
    }

    override suspend fun getAppCacheSize(packageName: String): Long {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return 0L
        }
        return try {
            val escapedPackage = ShellUtils.escapedString(packageName)
            val result = shellRepository.runCommand("du -s /data/data/$escapedPackage/cache")
            val outputLine = result.getOrNull()?.firstOrNull() ?: return 0L

            // Output format is usually "12345   /path/to/file"
            // We parse this in Kotlin, not using brittle 'awk' or 'cut'
            val sizeInBlocks =
                outputLine.substringBefore('\t').substringBefore(' ').toLongOrNull() ?: 0L

            // du usually returns 1k blocks
            sizeInBlocks * 1024
        } catch (e: Exception) {
            Logger.e("RootSystemGateway", "Failed to get app cache size for $packageName", e)
            0L
        }
    }

    /**
     * Modernized Reinstall Logic.
     * Replaces the 'sed' and 'tr' pipes with proper Kotlin string manipulation.
     */
    override suspend fun reinstallAppWithGoogle(packageName: String): Result<Unit> {
        if (packageName == BuildConfig.APPLICATION_ID)
            return Result.failure(Exception("Cannot reinstall Thor"))
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package name: $packageName"))
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1. Get the APK path(s)
                val paths = getAppPaths(packageName)
                if (paths.isEmpty()) {
                    return@withContext Result.failure(Exception("Could not find APK path for $packageName"))
                }

                val combinedPath = paths.joinToString(" ") { ShellUtils.escapedString(it) }

                // 2. Get Current User ID
                val currentUser = getCurrentUserId()

                // 3. Execute the reinstallation command
                val command =
                    "pm install -r -d -i \"com.android.vending\" --user $currentUser --install-reason 0 $combinedPath"
                runCommand(command)
            } catch (e: Exception) {
                Logger.e("RootSystemGateway", "Reinstall with Google failed for $packageName", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Copies a file using Root privileges.
     */
    suspend fun copyFile(source: String, destination: String) {
        val escapedSource = ShellUtils.escapedString(source)
        val escapedDest = ShellUtils.escapedString(destination)
        val command = "cp $escapedSource $escapedDest"
        val result = runCommand(command)

        if (result.isFailure) {
            throw Exception("Root copy failed: $command")
        }
    }

    /**
     * Retrieves all APK paths (Base + Splits) for a package.
     */
    suspend fun getAppPaths(packageName: String): List<String> {
        if (!packageName.matches(PACKAGE_NAME_REGEX)) {
            return emptyList()
        }
        val escapedPackage = ShellUtils.escapedString(packageName)
        val result = shellRepository.runCommand("pm path $escapedPackage")
        val lines = result.getOrNull() ?: emptyList()

        return lines
            .filter { it.isNotBlank() }
            .map { it.removePrefix("package:").trim() }
    }


    override suspend fun grantPermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val escapedPackage = ShellUtils.escapedString(packageName)
        val escapedPerm = ShellUtils.escapedString(permissionName)
        return runCommand("pm grant $escapedPackage $escapedPerm")
    }

    override suspend fun revokePermission(
        packageName: String,
        permissionName: String
    ): Result<Unit> {
        if (!packageName.matches(PACKAGE_NAME_REGEX) || !permissionName.matches(PACKAGE_NAME_REGEX)) {
            return Result.failure(IllegalArgumentException("Invalid package or permission name"))
        }
        val escapedPackage = ShellUtils.escapedString(packageName)
        val escapedPerm = ShellUtils.escapedString(permissionName)
        return runCommand("pm revoke $escapedPackage $escapedPerm")
    }

    /**
     * Raw shell execution for extensions, via the root shell.
     */
    override suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>> {
        // Result.failure means the shell could not execute at all (lost root/session).
        // Surface that as a real failure so callers (e.g. ThorShellExecutor) map it to -1,
        // rather than masquerading as a normal command that exited with code 1.
        return shellRepository.runCommand(command).fold(
            onSuccess = { output -> Result.success(0 to output.joinToString("\n")) },
            onFailure = { error -> Result.failure(error) }
        )
    }

    /**
     * Helper to bridge ShellRepository's Result<List<String>> to Result<Unit>
     */
    private suspend fun runCommand(cmd: String): Result<Unit> {
        val result = shellRepository.runCommand(cmd)
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            val exception = result.exceptionOrNull() ?: Exception("Shell command failed: $cmd")
            Logger.e("RootSystemGateway", "Command execution failed: $cmd", exception)
            Result.failure(exception)
        }
    }

    private fun getApplicationInfoCompat(packageName: String): android.content.pm.ApplicationInfo? = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.ApplicationInfoFlags.of(android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
            )
        } else {
            context.packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES
            )
        }
    }.getOrNull()

    // @Volatile guarantees safe publication across threads: getCurrentUserId() runs on the IO
    // dispatcher while onServiceDisconnected() invalidates the cache on the main thread, so a
    // '--user <id>' read always sees a consistent value and a foreground-user switch is picked up
    // after the next reconnect (#34).
    @Volatile
    private var cachedUserId: String? = null

    // Bumped on every cache invalidation (onServiceDisconnected). getCurrentUserId() captures it
    // before the shell read and only commits the result if it is unchanged, so an invalidation that
    // races an in-flight lookup can't be silently overwritten by the stale value the lookup read.
    @Volatile
    private var userIdGeneration = 0

    private suspend fun getCurrentUserId(): String {
        cachedUserId?.let { return it }
        val gen = userIdGeneration
        val userResult = shellRepository.runCommand("am get-current-user")
        val currentUser = userResult.getOrNull()?.firstOrNull()?.trim()
        return if (currentUser != null && currentUser.matches(USER_ID_REGEX)) {
            // Only cache a *successfully* resolved id (caching the "0" fallback would let a transient
            // shell/daemon blip persist the wrong user, so later '--user' commands would target user
            // 0 on multi-user devices), AND only if no invalidation raced this lookup — a user switch
            // that fired onServiceDisconnected mid-read must not re-cache the stale value.
            currentUser.also { if (userIdGeneration == gen) cachedUserId = it }
        } else {
            "0"
        }
    }
}