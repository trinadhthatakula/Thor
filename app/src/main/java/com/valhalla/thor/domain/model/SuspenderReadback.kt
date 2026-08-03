// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * The identity every shell-uid caller is stuck with.
 *
 * Shizuku runs Thor's commands as uid 2000 and Dhizuku's `pm suspend` lands there too, and
 * `PackageManagerService.enforceCanSetPackagesSuspendedAsUser` only lets a shell caller name a
 * package that *is* the shell (`allowedShell = callingUid == SHELL_UID && isCallerSameApp(...)`).
 * So a suspension made in Shizuku or Dhizuku mode is recorded under this name and no other — which
 * is also why root, and only root, can lift it (see [canLiftSuspension]).
 */
const val SHELL_SUSPENDER_IDENTITY = "com.android.shell"

/**
 * Thor's release `applicationId`, and what the root reflection path passes as `callingPackage`.
 *
 * This is deliberate and must stay: the system derives the user-visible "managed by Thor" line on
 * the paused-app dialog from the suspending package name, so recording our own name is what makes
 * attribution work. See `ThorRootService.setAppSuspendedAs`.
 */
const val THOR_SUSPENDER_IDENTITY = "com.valhalla.thor"

/**
 * What a root *shell* `pm suspend` records: uid 0 has no package, so the platform stores the literal
 * string "root", which is not an installed package at all.
 *
 * Thor stopped writing this in GH#239 — tapping such an app crashes `SuspendedAppActivity` with
 * "Package root does not exist" — but suspensions written by older builds, by the pre-API-29 root
 * path that had no reflection available, and by Shizuku *running as root* are still out there on
 * users' devices, so the unsuspend path has to keep clearing it.
 */
const val LEGACY_ROOT_SUSPENDER_IDENTITY = "root"

/** Identities Thor may have recorded across its own history and privilege modes. */
val THOR_SUSPENDER_IDENTITIES: Set<String> =
    setOf(THOR_SUSPENDER_IDENTITY, LEGACY_ROOT_SUSPENDER_IDENTITY)

/**
 * [THOR_SUSPENDER_IDENTITIES] plus the package name this build actually runs under.
 *
 * The debug build carries `applicationIdSuffix = ".debug"`, so it records `com.valhalla.thor.debug`
 * as the suspender and would not recognise its own suspensions in the constant set above. Anywhere
 * a real `Context` is in reach, pass `context.packageName` through here rather than trusting the
 * hardcoded release name.
 */
fun thorSuspenderIdentities(ownPackageName: String): Set<String> =
    THOR_SUSPENDER_IDENTITIES + ownPackageName

/**
 * `Build.VERSION_CODES.R`, spelled out.
 *
 * The API level arrives as a parameter so a test can name a device instead of being one, and
 * `Build.VERSION.SDK_INT` reads 0 under the mockable `android.jar` anyway — a default argument
 * reading it would silently put every unit test below this boundary while looking like it asked the
 * device. Same reasoning as `PlatformPermissionGroups.TIRAMISU`.
 */
private const val R = 30

/**
 * Whether a caller at the current privilege can lift a suspension recorded by [recordedSuspender].
 *
 * The asymmetry this encodes is the whole point of the readback:
 *
 * - **Below API 30 ownership does not exist.** `PackageSettingBase.setSuspended(false)` clears the
 *   single suspension slot regardless of who set it (android-9.0.0_r1
 *   `PackageSettingBase.java:399-407`), so any caller can undo any suspension and the recorded name
 *   is decoration.
 * - **Root may impersonate anyone.** `PackageManagerService.enforceCanSetPackagesSuspendedAsUser`
 *   unconditionally early-returns for `Process.ROOT_UID` *before* any suspender-name validation
 *   (android-17.0.0_r1 `PackageManagerService.java:3354-3358`), unchanged from API 28 to main. A
 *   uid-0 binder call naming any `suspendingPackage` is accepted verbatim, which is what lets root
 *   rescue a Shizuku-era suspension.
 * - **Shell may not.** The shell branch of the same check is
 *   `allowedShell = callingUid == SHELL_UID && isCallerSameApp(suspendingPackage, callingUid)`, so
 *   Shizuku and Dhizuku can only ever act as [SHELL_SUSPENDER_IDENTITY]. From API 30 on,
 *   `removeSuspension(callingPackage)` (android-11.0.0_r1 `PackageSettingBase.java:443-452`, carried
 *   into `SuspendPackageHelper` on 13-16) removes only the caller's own entry and leaves `suspended`
 *   true while anyone else's remains.
 *
 * A `false` here is not a reason to try anyway and hope. Attempting it produces
 * `oldSuspendParams == null == newSuspendParams` → `changed == false` → the package is logged
 * "No change is needed" and left *out* of the returned failure array, so the call reports success
 * while the app stays suspended forever. Callers must surface the failure and name
 * [recordedSuspender] instead.
 */
fun canLiftSuspension(recordedSuspender: String, isRoot: Boolean, sdkInt: Int): Boolean = when {
    sdkInt < R -> true
    isRoot -> true
    else -> recordedSuspender == SHELL_SUSPENDER_IDENTITY
}

/** A package block header — `  Package [com.example.app] (a1b2c3):`. */
private const val PACKAGE_HEADER = "Package ["

/** The API 30+ block header, trimmed. */
private const val SUSPEND_PARAMS_HEADER = "Suspend params:"

private const val SUSPENDED_KEY = "suspended="
private const val SUSPENDING_PACKAGE_KEY = "suspendingPackage="

/** `    User 0: ceDataInode=… installed=true …` — the per-user state section. */
private val USER_SECTION = Regex("""^User (\d+):""")

/**
 * The API 35+ `UserPackage.toString()` prefix: `"<" + userId + ">" + packageName`
 * (android-15.0.0_r1 `services/core/java/com/android/server/pm/UserPackage.java`).
 */
private val USER_PACKAGE_PREFIX = Regex("""^<(\d+)>""")

/**
 * Every package name currently recorded as suspending the dumped package for [userId].
 *
 * **An empty result means "unknown", never "not suspended".** A package that is not suspended, a
 * dump truncated by a broken pipe, a `Permission Denial:` line from a process without
 * `android.permission.DUMP`, and an OEM ROM whose dump format nobody has seen all produce exactly
 * the same empty set. A caller that reads empty as "nothing to do, report success" reintroduces the
 * silent-success bug this whole readback exists to kill: an app that stays suspended forever while
 * Thor says it unsuspended it. Treat empty as "not verified" and fail closed.
 *
 * [dumpsysOutput] must be the output of `dumpsys package <pkg>` for a **single** package. There is
 * no package name to filter by here, so a whole-system dump would union the suspenders of every
 * package in it.
 *
 * ### The four shapes
 *
 * The field moved twice and its key changed twice, so all four have to parse on a range that starts
 * at Thor's minSdk of 28. Line quoting below is from `Settings.dumpPackageLPr`:
 *
 * | API | Shape |
 * |---|---|
 * | 28 (P) | inline on the `User N:` line: `… suspended=true suspendingPackage=<pkg> dialogMessage=…` (android-9.0.0_r61 `Settings.java:4775-4778`) |
 * | 29 (Q) | same inline, trailing key renamed to `dialogInfo=` (android-10.0.0_r47 `Settings.java:4757-4759`) |
 * | 30-34 | a `Suspend params:` block, one `suspendingPackage=<pkg> dialogInfo=…` line per suspender — `PackageUserState.suspendParams` became a map, so a package can have several at once |
 * | 35-37 | the same block, but the map key is now `UserPackage.toString()` — `suspendingPackage=<0>com.android.shell dialogInfo=null quarantined=false` |
 *
 * **The 35+ trap:** a regex written against the 30-34 shape captures the literal `<0>` prefix, and
 * the name then goes straight into a `pm unsuspend` / `setPackagesSuspendedAsUser` argument as a
 * package called `<0>com.android.shell`, which exists nowhere. Nothing is removed and, because
 * naming a non-existent suspender is indistinguishable from naming one that owns nothing, nothing
 * fails either. The prefix is stripped here and its digits are used to filter by [userId].
 *
 * ### Why a state machine, and why indentation is not part of it
 *
 * `Suspend params:` carries no user id of its own; it belongs to whichever `User N:` section it
 * follows, which is the only thing keeping a work-profile suspension from being reported as user
 * 0's. That is state across lines, so this is a line-oriented machine rather than one regex.
 *
 * Every line is trimmed first, deliberately. `dumpPackageLPr` takes its indentation as a `prefix`
 * parameter and the `Hidden system packages:` section dumps at a deeper one, so column counts are a
 * property of the call site rather than of the format — matching on them would drop entries on a
 * dump that is otherwise perfectly well formed.
 */
fun parseSuspendingPackages(dumpsysOutput: String, userId: Int = 0): Set<String> {
    val suspenders = LinkedHashSet<String>()

    // The user id of the `User N:` section we are inside, or null before the first one.
    var sectionUserId: Int? = null

    // That section's `suspended=` flag; null when the line did not carry one.
    var sectionSuspended: Boolean? = null

    // True while consuming the entries of a `Suspend params:` block we care about.
    var inSuspendParams = false

    for (rawLine in dumpsysOutput.lineSequence()) {
        val line = rawLine.trim()

        // A new package block cannot inherit the previous one's user section. Only reachable on a
        // dump that carries more than one — `Hidden system packages:` after an updated system app.
        if (line.startsWith(PACKAGE_HEADER)) {
            sectionUserId = null
            sectionSuspended = null
            inSuspendParams = false
            continue
        }

        val userMatch = USER_SECTION.find(line)
        if (userMatch != null) {
            sectionUserId = userMatch.groupValues[1].toIntOrNull()
            sectionSuspended = line.fields().tokenValue(SUSPENDED_KEY)?.toBooleanStrictOrNull()
            inSuspendParams = false
            // API 28/29: the suspender is on this very line, printed only when suspended is true.
            if (sectionUserId == userId && sectionSuspended != false) {
                line.fields().tokenValue(SUSPENDING_PACKAGE_KEY)
                    ?.let { suspenderName(it, userId) }
                    ?.let(suspenders::add)
            }
            continue
        }

        if (line == SUSPEND_PARAMS_HEADER) {
            // An explicit `suspended=false` disowns any block that follows it: AOSP prints the block
            // only inside `if (ps.getSuspended(userId))`, so text surviving a false flag is stale.
            // A *missing* flag is unknown rather than false — the block's own existence is then the
            // best evidence there is, and dropping it would hide a real suspension.
            inSuspendParams = sectionUserId == userId && sectionSuspended != false
            continue
        }

        if (inSuspendParams) {
            val key = line.fields().tokenValue(SUSPENDING_PACKAGE_KEY)
            if (key == null) {
                // The block ends at the first line that is not one of its entries; anything after it
                // belongs to the package or user state again.
                inSuspendParams = false
                continue
            }
            suspenderName(key, userId)?.let(suspenders::add)
        }
    }

    return suspenders
}

/**
 * The part of a dump line that is still `key=value` pairs.
 *
 * `dialogMessage` (28) and `dialogInfo` (29+) are the only free text on these lines — a message the
 * suspending app chose, or a `SuspendDialogInfo.toString()` — and both are printed *after*
 * `suspendingPackage`, so cutting there removes every value that could contain a space or forge a
 * key. Without this, an app whose pause dialog says "suspended=false" would edit Thor's answer.
 */
private fun String.fields(): String =
    substringBefore(" dialogMessage=").substringBefore(" dialogInfo=")

/**
 * The value of a whitespace-delimited `key=value` token, or null if the line has no such key.
 *
 * Prefix-matching whole tokens rather than searching the raw string is what keeps `suspended=` from
 * matching inside `suspendingPackage=`, which shares its first eight characters and sits on the same
 * line in the API 28 and 29 shapes.
 */
private fun String.tokenValue(key: String): String? = splitToSequence(' ', '\t')
    .firstOrNull { it.startsWith(key) }
    ?.substring(key.length)
    ?.takeIf { it.isNotEmpty() }

/**
 * A recorded suspender's package name, or null if this entry is not [userId]'s.
 *
 * On API 35+ the map key is a `UserPackage`, so it arrives as `<0>com.android.shell`: the digits are
 * the user the *suspending* package lives in. They are stripped from the returned name — a
 * `<0>`-prefixed string is not a package name and must never reach a `pm` argument — and used to
 * drop entries belonging to another user, so a work profile's DPC is not reported as suspending
 * user 0's copy of the app. On 28-34 there is no prefix and the key is already the package name.
 *
 * A literal `null` is what the platform prints for an absent value; it is not a package.
 */
private fun suspenderName(key: String, userId: Int): String? {
    val prefix = USER_PACKAGE_PREFIX.find(key)
    val name = if (prefix == null) {
        key
    } else {
        if (prefix.groupValues[1].toIntOrNull() != userId) return null
        key.substring(prefix.value.length)
    }
    return name.takeIf { it.isNotEmpty() && it != "null" }
}
