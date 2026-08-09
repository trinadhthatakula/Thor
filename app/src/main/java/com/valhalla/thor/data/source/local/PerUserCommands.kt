// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.annotation.SuppressLint

/**
 * The privileged commands and data paths that are wrong — silently, and on another user's data —
 * unless an Android user is named in them.
 *
 * `pm` does not default to the caller's user. `PackageManagerShellCommand.runClear` (and the
 * enable/disable pair beside it) seeds `userId = UserHandle.USER_SYSTEM`, so a bare
 * `pm clear <pkg>` issued from user 10 wipes **user 0's** copy of that package and still exits 0 —
 * the same trap `RootSystemGateway.getPackageUserId` already documents for `pm grant`/`pm revoke`.
 * `/data/data` and `/sdcard` are that trap's filesystem half: both are per-user aliases resolved
 * against the *shell's* user, so they name user 0's directories no matter which user Thor runs as.
 *
 * Shared by all three privilege modes, because all three had the same hole in the same places — and
 * because the shell rung and the reflection rung of one operation have to name the same user: a
 * `pm clear` on user 0 followed by a `clearApplicationUserData(…, 10)` fallback is two different
 * wipes wearing one function name.
 *
 * The builders take the user id instead of reading [thorUserId] themselves so that "does this
 * command name a user at all?" is assertable from a plain JVM test — `Process.myUserHandle()` is not
 * callable there. The tests pass a work-profile id, where a missing `--user` is visible, and a 0,
 * where it is not. It also keeps them usable from a privileged process that has to be *told* the
 * user because it cannot read it: the `:root` daemon runs as uid 0, where [thorUserId] would answer
 * 0 for a Thor sitting in user 10.
 *
 * The seed is not one value, and each builder below names the one it defends against for exactly
 * that reason. `pm` seeds `USER_SYSTEM` for clear/enable/disable/path and `USER_ALL` for
 * install/uninstall, which it then widens to *every* user; `am` seeds `USER_ALL` for force-stop;
 * `appops` seeds `USER_CURRENT` and resolves it inside system_server to the globally foreground
 * user, which is neither of the other two. "It defaults to user 0" is true of some of these commands
 * and wrong about the rest, so no builder here inherits another's explanation.
 */

/** `pm clear`, scoped to [userId] instead of to whatever user the privileged shell belongs to. */
internal fun clearAppDataCommand(escapedPackage: String, userId: Int): String =
    "pm clear --user $userId $escapedPackage"

/**
 * The cache directories of [escapedPackage] **for [userId]**: credential-encrypted, then
 * device-encrypted, then external.
 *
 * The set is not a guess — it is the three branches of `InstalldNativeService::clearAppData` under
 * `FLAG_CLEAR_CACHE_ONLY`, which is what the platform itself deletes when a user taps Clear cache
 * in Settings:
 *  - `FLAG_STORAGE_CE` → `create_data_user_ce_package_path(...) + "cache"`
 *  - `FLAG_STORAGE_DE` → `create_data_user_de_package_path(...) + CACHE_DIR_POSTFIX`
 *  - `FLAG_STORAGE_EXTERNAL` → `<ext>/Android/data/<pkg>/cache`
 *
 * The device-encrypted entry is the one this list spent its whole life missing. `/data/user_de` is
 * not an exotic direct-boot-only location: PMS creates a `user_de` package directory for **every**
 * installed app, and anything written through `createDeviceProtectedStorageContext().cacheDir`
 * lands there. Clearing only CE and external therefore left a real, sometimes large, slice of cache
 * behind while reporting the operation complete — and it is invisible in testing precisely because
 * most apps put little there and the number that is left behind was never shown to anyone.
 *
 * Deliberately no `code_cache`, in either encryption. That is a *different* installd flag
 * (`FLAG_CLEAR_CODE_CACHE_ONLY`), Settings' Clear cache does not touch it, and it holds compiled
 * artifacts an app expects to persist. Matching the platform matters more here than freeing the
 * largest possible number of bytes.
 *
 * Deliberately no `/data/data/<pkg>/cache` and no `/sdcard/Android/data/<pkg>/cache`. They are not
 * extra coverage: they are aliases of the CE and external entries *for user 0*, so on a secondary
 * user (work profile, Xiaomi Second Space) a shell that expands them deletes a different user's
 * cache and leaves Thor's own untouched.
 *
 * Only the root gateway can act on all of these. A shell-uid or device-owner caller is refused
 * `/data/user*` outright, which is why per-app cache clearing is root-gated in
 * `SystemRepositoryImpl` rather than attempted and reported as done.
 */
// SdCardPath is suppressed because its advice does not apply: these are not Thor's own directories.
// getExternalCacheDir/getFilesDir answer for the *calling* app, and the whole point of the function
// is to name another package's cache — under another user id — for a privileged shell to delete. The
// literal path is the only way to express that. The same suppression sat on Shizuku.clearCache and
// Dhizuku.clearCache before these strings were lifted out of them; it is inherited, not new.
@SuppressLint("SdCardPath")
internal fun clearCachePaths(escapedPackage: String, userId: Int): List<String> = listOf(
    "/data/user/$userId/$escapedPackage/cache",
    "/data/user_de/$userId/$escapedPackage/cache",
    "/storage/emulated/$userId/Android/data/$escapedPackage/cache",
)

/**
 * `pm uninstall`, scoped to [userId] — the user-facing removal, data and all.
 *
 * This one is not merely wrong-user: `PackageManagerShellCommand.runUninstall` seeds
 * `userId = UserHandle.USER_ALL` and then turns that into `flags |= DELETE_ALL_USERS`. So a bare
 * `pm uninstall <pkg>` does not remove the package for the shell's user, or for user 0 — it removes
 * it, and its data, for **every user on the device**, and exits 0. From a work profile that destroys
 * the personal profile's copy of an app the user never selected, and nothing in the exit code says
 * so.
 *
 * Naming [userId] is also the answer Thor's UI is asking for: the app list is built from packages
 * installed for Thor's own user, so "uninstall this" means "the entry I am looking at". On a
 * single-user device the two agree — PMS fully removes a package when no other user still holds it —
 * so this changes nothing there.
 *
 * Deliberately no `-k` variant. `DELETE_KEEP_DATA` belongs to the system-app *freeze* fallback,
 * which is a different operation behind a different gate, and the three helpers that issue it
 * already name their user.
 */
internal fun uninstallCommand(escapedPackage: String, userId: Int): String =
    "pm uninstall --user $userId $escapedPackage"

/**
 * `pm disable` / `pm enable` for a **user** app, scoped to [userId].
 *
 * `disable` (COMPONENT_ENABLED_STATE_DISABLED) and not `disable-user`, because the only caller is
 * the root gateway and uid 0 may set the stronger state. The Shizuku and Dhizuku helpers build
 * their own `pm disable-user` line rather than calling this — a shell-uid or device-owner caller is
 * refused the stronger state on every release, which `Shizuku.setAppDisabledDetailed` documents
 * with the measurement behind it.
 */
internal fun setAppEnabledCommand(escapedPackage: String, userId: Int, isDisabled: Boolean): String =
    "pm ${if (isDisabled) "disable" else "enable"} --user $userId $escapedPackage"

/**
 * `pm install` / `pm install-multiple`, scoped to [userId] — [uninstallCommand]'s trap entered from
 * the other side.
 *
 * `PackageManagerShellCommand.makeInstallParams` opens with `params.userId = UserHandle.USER_ALL`
 * and leaves it there when the option loop never sees a `--user`; the session is then created with
 * `params.userId = UserHandle.USER_SYSTEM; sessionParams.installFlags |= INSTALL_ALL_USERS`. A bare
 * `pm install <apk>` therefore does not install for the shell's user, and does not install only for
 * user 0: it installs the package for **every user on the device**, and exits 0. That is the same
 * `USER_ALL` seed and the same silent widening to all users that [uninstallCommand] documents,
 * reached from the opposite direction — which is why the two now sit in one file. They are a pair,
 * and a pair that drifts is how a profile ends up holding an app nobody there chose to install.
 *
 * The verb follows `escapedApkPaths.size` rather than being a parameter of its own. The four
 * installer call sites already branched on that count and built two separate command strings from
 * it, so `--user` would have had to be added twice per site; one operation reaching `pm` down two
 * hand-written paths is precisely where a flag ends up present on one of them only.
 *
 * [installerArg] arrives pre-formatted from `PreferenceRepository.getInstallerArg` — either empty or
 * `" -i com.android.vending"` — and is re-spaced here so that a caller passing it untrimmed cannot
 * emit `-g-i com.android.vending`, which `pm` rejects with a usage error the installer would report
 * as an install failure. [canDowngrade] is `-d`: permissive-only, it allows a lower versionCode to
 * replace a higher one and changes nothing otherwise. `-r` and `-g` are constants at all six call
 * sites, so they are not parameters.
 *
 * Deliberately no `--install-reason` and no hard-coded `-i com.android.vending`. That is the Fix
 * Store line — a different operation, meaning "re-attribute this app to Play" — and all three
 * gateways already name a user in it.
 *
 * An empty [escapedApkPaths] is a programming error, not a runtime condition: every caller returns
 * early when the copy-to-temp step produced no files, and a command with no APK argument would
 * otherwise be handed to a privileged shell to fail on.
 */
internal fun installCommand(
    escapedApkPaths: List<String>,
    userId: Int,
    canDowngrade: Boolean = false,
    installerArg: String = "",
): String {
    require(escapedApkPaths.isNotEmpty()) { "installCommand needs at least one APK path" }
    val verb = if (escapedApkPaths.size == 1) "install" else "install-multiple"
    val downgrade = if (canDowngrade) " -d" else ""
    val installer = installerArg.trim().let { if (it.isEmpty()) "" else " $it" }
    return "pm $verb --user $userId -r -g$downgrade$installer ${escapedApkPaths.joinToString(" ")}"
}

/**
 * `pm path`, scoped to [userId] — *whose* copy of the package the answer describes.
 *
 * `PackageManagerShellCommand.runPath` seeds `UserHandle.USER_SYSTEM` and then asks
 * `getPackageInfo(pkg, …, userId)`, so a bare `pm path <pkg>` answers for **user 0's** copy no
 * matter which user the shell belongs to. Its one caller is the Fix Store / reinstall-with-Google
 * path, which feeds the result straight into a `pm install … --user <thorUserId>`: the two halves of
 * a single operation read one user and write another, and every command in the chain exits 0 either
 * way, so nothing in the result says so.
 *
 * The APK bytes themselves are device-wide — one `/data/app` copy serves every user — so what a
 * user id selects here is *visibility*, and that is what makes the mismatch silent in both
 * directions. A work-profile-only app answers nothing for user 0, so Fix Store stops with "Could not
 * find APK path" for an app the user is looking at; an app installed for user 0 but not for Thor's
 * user answers with paths, and Fix Store reinstalls off a record for a copy this user does not have.
 * Naming [userId] makes the empty answer mean what the caller already assumed it meant.
 */
internal fun pmPathCommand(escapedPackage: String, userId: Int): String =
    "pm path --user $userId $escapedPackage"

/**
 * `am force-stop`, scoped to [userId].
 *
 * `ActivityManagerShellCommand.runForceStop` seeds `UserHandle.USER_ALL`, so a bare
 * `am force-stop <pkg>` kills the package for **every user on the device** rather than for the
 * shell's — the same seed and the same unnamed-user class as [uninstallCommand] and
 * [installCommand].
 *
 * The stakes are lower here and this should not pretend otherwise: force-stop destroys no data and
 * the process returns on the next start, so the cost of the bare form is another profile's app
 * losing live state — a scheduled alarm, a foreground service, an unsaved draft. It is fixed because
 * it is the same defect and because the Shizuku and Dhizuku helpers already pass `--user` on this
 * exact command: root was the odd one out, and one gateway spelling an operation differently from
 * the other two is how "that is fixed" quietly stops being true.
 */
internal fun forceStopCommand(escapedPackage: String, userId: Int): String =
    "am force-stop --user $userId $escapedPackage"

/**
 * `appops set … RUN_ANY_IN_BACKGROUND`, scoped to [userId] — the background-restriction toggle.
 *
 * `appops` is not `pm`, and none of the reasoning above carries over: nothing here defaults to
 * user 0 and nothing here fans out to all users. `AppOpsService.Shell.parseUserPackageOp` seeds
 * `UserHandle.USER_CURRENT` and, once the option loop ends, resolves it with
 * `ActivityManager.getCurrentUser()` — evaluated inside system_server, so the target is the
 * **globally foreground user**: not the caller's, and not user 0.
 *
 * That is why the bare form failed the way it did. On a managed profile the foreground user is the
 * parent, so a restriction set from the work profile landed on the personal profile's copy of the
 * app. In a Xiaomi Second Space the space you switched into *is* the foreground user, so the same
 * command happened to be right. The defect is therefore not "it targets the wrong user" — it is that
 * which user it targeted depended on who was in the foreground at the moment the command ran, a
 * value that changes while Thor is alive and can differ between the write and the read-back that
 * confirms it.
 *
 * `ignore` restricts and `allow` lifts the restriction, matching the three call sites this replaces.
 * `--user` precedes the package because `parseUserPackageOp` consumes its options before the package
 * and op positionals.
 */
internal fun backgroundRestrictionCommand(
    escapedPackage: String,
    userId: Int,
    restricted: Boolean,
): String = appOpsCommand(
    escapedPackage = escapedPackage,
    userId = userId,
    op = "RUN_ANY_IN_BACKGROUND",
    mode = if (restricted) "ignore" else "allow",
)

/**
 * `appops set … GET_USAGE_STATS allow`, scoped to [userId] — the silent grant behind
 * `UsageAccessManager.tryGrantViaPrivilege`.
 *
 * The same `USER_CURRENT` seed [backgroundRestrictionCommand] documents, but it went wrong in a
 * different shape and it is worth saying which, because the two are not interchangeable.
 *
 * Here the package is **Thor's own**, and the grant is confirmed by `UsageAccessManager.isGranted`,
 * an in-process `AppOpsManager.unsafeCheckOpNoThrow` for `Process.myUid()` — which answers for
 * *Thor's* user, always. So the write went to whoever was in the foreground and the read asked
 * Thor's own user, and on a managed profile those are different users. The failure is therefore not
 * a false success: `isGranted()` still returns false and `tryGrantViaPrivilege` still reports
 * failure, so Thor falls back to the Settings deep-link and the user is not lied to. What it does
 * instead is grant usage access to the **parent profile's** copy of Thor — a permission nobody
 * asked for, on a profile the request did not come from — and then leave the work profile's copy
 * without the op forever, because the retry issues exactly the same command again.
 *
 * `maybeAutoGrant` latches only on success, so this was a wasted privileged command on every call
 * rather than once. Naming [userId] makes the write land where the read is already looking.
 */
internal fun usageStatsGrantCommand(escapedPackage: String, userId: Int): String =
    appOpsCommand(escapedPackage, userId, op = "GET_USAGE_STATS", mode = "allow")

/**
 * The one place that knows how an `appops set` line is spelled.
 *
 * Private on purpose. The file's contract is the *named* operations above, not a generic emitter
 * that would let a caller pass any op and any mode — an arbitrary-op builder is a per-user command
 * literal again, with one extra function call in front of it. A third app-op belongs here as a
 * third named builder, which is also what gets it covered by the reflective sweep in
 * `PerUserCommandsTest` for free; a private helper is deliberately invisible to that sweep.
 *
 * `--user` precedes the package because `parseUserPackageOp` consumes its options before the
 * package and op positionals: written after the package, `--user` is parsed as the *op* and the
 * command fails with "Unknown operation string" rather than with a usage error.
 */
private fun appOpsCommand(escapedPackage: String, userId: Int, op: String, mode: String): String =
    "appops set --user $userId $escapedPackage $op $mode"
