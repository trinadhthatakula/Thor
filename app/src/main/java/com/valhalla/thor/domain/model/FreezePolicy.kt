// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * How dangerous it is to freeze a package, derived from its UAD (Universal Android Debloater)
 * recommendation.
 *
 * Only system apps have a tier. Freezing a user app is always reversible with `pm enable`, so
 * there is nothing to warn about; a system app is frozen by disabling it where the platform
 * allows that and by removing it for the current user where it does not (see [FreezeMechanic]),
 * and the ones the UAD list marks unsafe can leave the device unable to boot, place calls, or
 * reach the network.
 */
enum class FreezeTier {
    /** No warning: a user app, or a system app UAD considers safe to remove. */
    NORMAL,

    /** Warn loudly, then let the user through — UAD's "Expert" tier. */
    EXPERT,

    /** Never freeze, whatever the surface. UAD's "Unsafe" tier, or no usable UAD data at all. */
    BLOCKED,
}

/**
 * The tier for one package.
 *
 * [isUadLoadFailed] outranks the recommendation on purpose: with no list loaded every system app
 * would read as unclassified, and "unclassified" must fail *closed*. That is the same reasoning
 * the freeze dialogs and the batch paths already use — this function exists so the rule has one
 * home instead of being retyped at each of them, which is how the QS tile ended up freezing what
 * the in-app dialog refuses to.
 */
fun freezeTierOf(
    isSystem: Boolean,
    bloatRecommendation: String?,
    isUadLoadFailed: Boolean,
): FreezeTier = when {
    !isSystem -> FreezeTier.NORMAL
    isUadLoadFailed -> FreezeTier.BLOCKED
    // .lowercase() is load-bearing: uad_lists.json stores the recommendation capitalised
    // ("Unsafe", "Expert"), so comparing against a lowercase literal without it matches nothing
    // and the gate degrades into a silent no-op that looks exactly like "nothing was risky".
    else -> when (bloatRecommendation?.lowercase()) {
        "unsafe" -> FreezeTier.BLOCKED
        "expert" -> FreezeTier.EXPERT
        else -> FreezeTier.NORMAL
    }
}

/** [freezeTierOf] for an app we already hold. */
val AppInfo.freezeTier: FreezeTier
    get() = freezeTierOf(isSystem, bloatRecommendation, isUadLoadFailed)

/**
 * The fail-closed reading of [freezeTier]: may we freeze this app at all?
 *
 * Nullable on purpose. "Could not resolve the app" and "the app is BLOCKED" have to produce the
 * same answer, and re-typing that per call site is how one of them eventually gets written as
 * `app != null && app.freezeTier != BLOCKED` — which reads an unresolvable package as a safe one
 * and freezes it. That is the exact defect PR #287's review caught, and the lookups behind this
 * (`AppRepository.getAppDetails`, a state snapshot a rescan may have dropped) all return null on
 * *any* failure. An unknown tier is not a safe tier.
 *
 * Freeze-only, matching [FreezeCandidate.blockedFromFreeze]: unfreezing must never consult it.
 */
fun isBlockedFromFreeze(app: AppInfo?): Boolean =
    app == null || app.freezeTier == FreezeTier.BLOCKED

/**
 * How a freeze was actually carried out. The two are not interchangeable.
 *
 * [DISABLE] is reversible in the sense users expect: the package stays installed, keeps its data,
 * and `pm enable` returns it exactly as it was.
 *
 * [UNINSTALL] is `pm uninstall -k --user N` — it clears `FLAG_INSTALLED` for the current user, so
 * the package disappears from launchers and from most `PackageManager` queries, and `-k`
 * (`DELETE_KEEP_DATA`) is what keeps `/data/user/N/<pkg>` and `/data/user_de/N/<pkg>` from being
 * destroyed. Measured on a HyperOS device: uninstall-with-`-k` then `pm install-existing` returned
 * the app with byte-identical `ceDataInode` and `deDataInode`. For a system app the data survives
 * indefinitely, because the package record never goes away — the APK is still on the read-only
 * partition. **Without `-k` this mechanic destroys the app's data**, which is what every build
 * before this one did, for every system app, on every release.
 *
 * The residual cost of [UNINSTALL] is not data but per-user package state: runtime permission
 * grants and app-ops are expected not to survive the round trip, so an unfrozen app may have to ask
 * for its permissions again. That is why [DISABLE] is still preferred everywhere it works — which,
 * measurably, is everywhere stock Android runs. [UNINSTALL] exists because some OEM builds refuse
 * to let the shell uid disable their own system packages at all, and it is reached *only* where the
 * platform actually refused, which is what [uninstallFreezeFallbackAllowed] decides.
 */
enum class FreezeMechanic {
    /** `pm disable`, or the equivalent `setApplicationEnabledSetting` reflection. Keeps data. */
    DISABLE,

    /**
     * `pm uninstall -k --user N`. Keeps the app's data directories *and* its runtime permission
     * grants — `-k` retains the whole `PackageUserState`, not just the data. What it does change is
     * `FLAG_INSTALLED`, so the package stops resolving for this user without `MATCH_UNINSTALLED_PACKAGES`.
     *
     * Not reachable at shell uid on API 37: Android 17 answers this command with
     * `Failure [only root can delete system app for a particular user]` where API 36 answers
     * `Success`.
     */
    UNINSTALL,
}

/**
 * May a failed [FreezeMechanic.DISABLE] escalate to [FreezeMechanic.UNINSTALL] for this package?
 *
 * Without this gate, *any* failure to disable — a busy PackageManager, a binder timeout, a package
 * that happened to be mid-update — silently escalates to removing the package for the user, who is
 * told the freeze succeeded. With it, escalation is confined to the case where the platform has
 * *refused* to disable the package, and everywhere else a failure to disable stays a *failure*,
 * visible and reportable.
 *
 * Failing loudly is the deliberate choice. A freeze that reports an error costs the user an
 * annoyance and Thor a bug report; a freeze that quietly swaps one mechanic for another produces no
 * report at all, because from the outside it looked like it worked. Before `-k` was added to the
 * fallback the price of getting this wrong was the user's data; it is now their per-user permission
 * grants, which is smaller but still not something to spend on a binder timeout.
 *
 * ### Why this is not a version check
 *
 * It was, briefly: `sdkInt >= 36`, on the report that shell-uid disabling of system apps "stopped
 * working on Android 16". That boundary does not exist, and the measurement is not close:
 *
 *  - On a **stock AOSP Android 16 (API 36) emulator**, as uid 2000, `pm disable-user --user 0`
 *    succeeds on system apps — verified on `com.android.egg`, `com.android.printspooler`,
 *    `com.android.wallpaper.livepicker` and `com.android.traceur`, each landing on `enabled=3`
 *    with `installed=true`, and each reversed by `pm enable`.
 *  - AOSP's shell guard in `PackageManagerService.setEnabledSettings` is **byte-identical** across
 *    android14-, android15-, android16-, android16-qpr1- and android16-qpr2-release. The 15→16
 *    diff of that method contains tracing and metrics changes and no security logic at all.
 *  - The restriction users actually hit is **Xiaomi's**, not Android's: a vendor
 *    `PackageManagerServiceImpl.shouldRestrictEnabledSettingsChange` — a class that does not exist
 *    in AOSP, and which 404s at every `android.googlesource.com` tag from 13 to 17 — throws
 *    `SecurityException("Cannot disable system packages.")` for `callingUid == 2000` on a system
 *    package. It was first reported on HyperOS running **Android 14**, roughly a year before
 *    Android 16 shipped, and is not tied to an API level in either direction. Reproduced on
 *    `25053PC47G` (HyperOS OS3.0, build `BP2A.250605.031.A3`): `pm disable-user --user 0` exits 255
 *    on `/system/app`, `/system/priv-app`, `/product/app` and `UPDATED_SYSTEM_APP` packages alike,
 *    while third-party packages disable normally.
 *
 * So a version test is wrong in *both* directions at once: it would strip the wrong mechanic onto a
 * Pixel that could have disabled the app, and it would refuse to freeze at all on the Xiaomi
 * devices that are the entire reason the fallback exists. What actually distinguishes the two cases
 * is not which release the device runs but whether the platform refused — so that is what this
 * asks.
 *
 * One thing the shell uid genuinely cannot do, on every release since API 25 and still true on 17:
 * set `COMPONENT_ENABLED_STATE_DISABLED` (state 2, what `pm disable` sends). It may only set
 * `DEFAULT`, `ENABLED` or `DISABLED_USER`. That is a *command* constraint, not a version one, and
 * Thor already satisfies it by sending `pm disable-user` from the Shizuku path.
 *
 * @param disableRefusedByPolicy true only when a privileged rung was refused by the platform —
 *   a `SecurityException` from `PackageManagerService`, not merely a non-zero exit or a read that
 *   came back unreadable. Passed in rather than inferred so this stays a pure function, and so the
 *   one decision that can cost a user their data is reachable from a plain JVM test.
 */
fun uninstallFreezeFallbackAllowed(
    isSystem: Boolean,
    privilegeMode: PrivilegeMode,
    disableRefusedByPolicy: Boolean,
): Boolean = when {
    // A user app disables under every privilege mode on every supported release, so there is no
    // platform gap to work around and no reason to ever reach for the destructive mechanic.
    !isSystem -> false

    // Nothing refused us, so nothing is unavailable. Whatever went wrong is a failure to report,
    // not a restriction to work around — and this is the branch that stops a binder timeout from
    // costing someone their app data.
    !disableRefusedByPolicy -> false

    else -> when (privilegeMode) {
        // The real gap, and the only one measured: an OEM that refuses to let the shell uid
        // disable its system packages. Uninstall-for-user is then the only remaining way to
        // freeze one, and refusing to escalate would mean those users cannot freeze system apps
        // at all — which is the state this whole change is trying to get *out* of.
        PrivilegeMode.SHIZUKU -> true

        // Root is uid 0, and the two refusals this fallback was built for — AOSP's shell guard and
        // Xiaomi's vendor one — both key on the *shell* uid (2000), so neither can reach root.
        //
        // One refusal genuinely can: `ProtectedPackages` (device provisioning package, device or
        // profile owner, DPM owner-protected) throws `Cannot disable a protected package: <pkg>`
        // and is *not* uid-gated, so it refuses root identically. Reported on Amazon Fire and on
        // Infinix XOS. That case still answers false, and deliberately: a package the platform
        // protects this hard is one where "uninstall it for the user instead" is the wrong reading
        // of the refusal, not a workaround for it. Surface it and let the user decide.
        //
        // This is a behaviour change either way: root used to freeze system apps by uninstalling
        // them unconditionally.
        PrivilegeMode.ROOT -> false

        // Dhizuku does not consult this policy yet — DhizukuSystemGateway still uninstalls system
        // apps unconditionally. This branch is what it *should* get, but flipping it on without a
        // device check would risk removing Dhizuku's only working system-app freeze, so the
        // gateway is deliberately left unconverted rather than half-converted here.
        PrivilegeMode.DHIZUKU -> false

        // No privilege means no freeze at all; nothing can reach the destructive rung from here.
        PrivilegeMode.NONE -> false
    }
}
