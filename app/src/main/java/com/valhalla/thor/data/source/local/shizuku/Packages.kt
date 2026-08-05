// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.data.source.local.thorUserId

class Packages(private val app: Context) {

    val myUserId get() = thorUserId

    fun packageUri(packageName: String) = "package:$packageName"

    fun packageUid(packageName: String) = if (Targets.T) app.packageManager.getPackageUid(
        packageName,
        PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
    ) else app.packageManager.getPackageUid(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)

    fun getInstalledApplications(flags: Int = PackageManager.MATCH_UNINSTALLED_PACKAGES): List<ApplicationInfo> =
        if (Targets.T) app.packageManager.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getInstalledApplications(flags)

    fun getUnhiddenPackageInfoOrNull(
        packageName: String, flags: Int = PackageManager.MATCH_UNINSTALLED_PACKAGES
    ) = runCatching {
        if (Targets.T) app.packageManager.getPackageInfo(
            packageName, PackageManager.PackageInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getPackageInfo(packageName, flags)
    }.getOrNull()

    fun getApplicationInfoOrNull(
        packageName: String, flags: Int = PackageManager.MATCH_UNINSTALLED_PACKAGES
    ) = runCatching {
        if (Targets.T) app.packageManager.getApplicationInfo(
            packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong())
        )
        else app.packageManager.getApplicationInfo(packageName, flags)
    }.getOrNull()

    /**
     * The canonical freeze test: a package is "not disabled" only when it is BOTH enabled AND
     * installed for this user.
     *
     * `enabled` on its own — all this used to read — is wrong in the one direction that matters.
     * Thor's other freeze mechanic is `pm uninstall -k --user N`, which clears FLAG_INSTALLED and
     * leaves `enabled` **true**, so a system app frozen that way (this build's gated fallback, and
     * every uninstall-only build before it) read back as *not* disabled. A disable rung verified
     * against that answer can never confirm the freeze it just performed, and an unfreeze can never
     * confirm it finished. Same conjunction as `AppFreezeStateReader.candidateOf` and
     * `Shizuku.setAppDisabledDetailed`, so "frozen" means one thing across Thor.
     *
     * The default lookup flags already carry MATCH_UNINSTALLED_PACKAGES, which is what lets such a
     * package resolve here at all instead of throwing.
     *
     * An unreadable package still answers **false**, unchanged — and that is fail-closed in one
     * direction only, not in both. In the direction that can cost something, a disable rung verified
     * with `isAppDisabled(pkg) == true` reads "I could not read it" as "it did not work" and reports
     * the failure instead of claiming a freeze it cannot see. `Dhizuku.setAppDisabledDetailed`, the
     * caller added alongside this change, runs the same comparison in the *enable* direction too,
     * where an unreadable package satisfies it instead.
     *
     * Left asymmetric on purpose. Flipping the null branch would hand the freeze direction the
     * answer that lets a package Thor cannot see satisfy a disable it never performed — the same
     * direction whose failure can escalate to removing the package for the user. The enable
     * direction has no such cliff: it is only reachable for a package that was readable when the
     * chain started and vanished mid-chain, and both unfreeze paths re-read `ApplicationInfo`
     * themselves before reporting success rather than trusting this predicate.
     */
    fun isAppDisabled(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.let {
            !(it.enabled && (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0)
        } ?: false

    fun isAppStopped(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_STOPPED == ApplicationInfo.FLAG_STOPPED }
            ?: false

    fun isAppUninstalled(packageName: String): Boolean =
        getApplicationInfoOrNull(packageName)?.run { flags and ApplicationInfo.FLAG_INSTALLED != ApplicationInfo.FLAG_INSTALLED }
            ?: true

    fun isPrivilegedApp(packageName: String): Boolean = getApplicationInfoOrNull(packageName)?.let {
        (ApplicationInfo::class.java.getField("privateFlags").get(it) as Int) and 8 == 8
    } ?: false

    // `canUninstallNormally` used to sit here: `FLAG_SYSTEM == 0`, one caller, and its only job was
    // to route ordinary user apps to a `pm uninstall` with no `--user`. That command is
    // DELETE_ALL_USERS in disguise (see `uninstallCommand`), so the predicate is deleted rather than
    // left for the next caller to find — there is no operation for which the answer "this is not a
    // system app" implies "no user needs naming".

    // `forceStopApp`, `setAppDisabled` and `setAppRestricted` used to sit here, and were deleted as
    // one edit because they only made sense as one: `setAppDisabled` was `forceStopApp`'s ONLY
    // caller, and `setAppDisabled` itself had none, so removing either alone leaves a dangling call
    // or a still-dead pair.
    //
    // All three were unprivileged reflection — `ActivityManager.forceStopPackage` and
    // `AppOpsManager.setMode` invoked via `Bypass` from Thor's own uid, with no privilege behind
    // them — wrapped in `runCatching { ...; true }`. That reports **true whenever the call merely
    // did not throw**, which is the failure mode this class exists to avoid: a `SecurityException`
    // is not the only way a call can do nothing, and a caller reading `true` cannot tell the
    // difference between "stopped it" and "was refused quietly". `setAppDisabled` was the only one
    // that read anything back (`isAppDisabled(...) == disabled`), and it still discarded the
    // force-stop's answer entirely.
    //
    // The real implementations are the privileged ones — `Shizuku`/`Dhizuku`/`RootSystemGateway`,
    // each of which runs the operation through a shell or a Device Owner binder and verifies it.
    // What survives on `Packages` is deliberately only the *observers* (`isAppDisabled`,
    // `isAppStopped`, `isAppUninstalled`, `getApplicationInfoOrNull`), which are what those
    // privileged paths call this class for. Do not re-add an unprivileged mutator here.
}