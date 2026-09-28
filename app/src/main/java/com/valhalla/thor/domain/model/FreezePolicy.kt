// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * How dangerous it is to freeze a package, derived from its UAD (Universal Android Debloater)
 * recommendation.
 *
 * Only system apps have a tier. Freezing a user app is always reversible with `pm enable`, so
 * there is nothing to warn about; a system app is frozen by disabling it, and where the platform
 * refuses to disable it the freeze now fails rather than removing the package for the current user
 * (see [FreezeMechanic]), and the ones the UAD list marks unsafe can leave the device unable to
 * boot, place calls, or reach the network.
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
 * Must this freeze be confirmed before it runs?
 *
 * The one gate every freeze surface asks before raising `AppRiskDialog`. It lives here rather than
 * beside the dialog for the reason [freezeTierOf] does: the answer is a statement about the tier, and
 * a tier rule retyped per call site is how the QS tile ended up freezing what the in-app dialog
 * refuses to. It is also why the suppression is not a parameter *on* the dialog — a dialog that
 * decides whether to render itself is a dialog whose callers stop agreeing about when it appears, and
 * the confirm button here is load-bearing enforcement on most of these paths.
 *
 * Two rules, and only the second one is new.
 *
 * **A user app is never confirmed.** Freezing one disables a package that `pm enable` returns exactly
 * as it was, so there has never been anything to warn about; [freezeTier] is [FreezeTier.NORMAL] for
 * every user app by construction. This is the gate the call sites already carried inline.
 *
 * **A system app is confirmed unless the user has switched off the routine case.**
 * [skipRoutineConfirmation] is [UserPreferences.skipRoutineFreezeConfirmation], and it reaches
 * exactly [FreezeTier.NORMAL] — a system app the UAD list either recommends removing or says nothing
 * about. That is the tap the setting exists for: someone debloating a fresh device answers the same
 * dialog forty times, and the fortieth answer carries no more information than the first.
 *
 * [FreezeTier.EXPERT] is deliberately outside the setting's reach. Its warning is not routine — it is
 * a per-app verdict from the UAD list saying this specific package breaks something real — and there
 * is no backstop underneath it: `FreezeAppUseCase` refuses [FreezeTier.BLOCKED] and nothing else, so
 * the dialog is the only thing between an EXPERT tap and the freeze. A blanket "don't ask me" that
 * silently covered it would turn a preference about *tedium* into a preference about *risk*.
 *
 * [FreezeTier.BLOCKED] never reaches the question. It still returns `true` here, because the dialog
 * is how a blocked app is refused: it renders no confirm button at all. Suppressing it would not skip
 * a confirmation, it would skip the refusal.
 */
fun freezeNeedsConfirmation(app: AppInfo, skipRoutineConfirmation: Boolean): Boolean =
    app.isSystem && !(skipRoutineConfirmation && app.freezeTier == FreezeTier.NORMAL)

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
 * The residual cost of [UNINSTALL] is narrower than it looks, and narrower than this comment used
 * to claim. What it costs unconditionally is `FLAG_INSTALLED`: `-k` still sets the user's installed
 * state to false, so the package stops resolving for this user unless the caller passes
 * `MATCH_UNINSTALLED_PACKAGES` — which is why every query in the freeze path must pass that flag.
 *
 * It does *not* cost the runtime permission grants, which this comment used to assert it did. That
 * assertion was a guess, and it measured false: at uid 2000 on a stock API 36 emulator, a permission
 * granted before the round trip came back granted with its flags unchanged. Read that for the scope
 * it has — one permission, granted by `pm grant` from the shell rather than by a user tapping Allow,
 * on one platform build. It retires the old blanket claim without earning the opposite one, and it
 * says nothing about app-ops, which were never measured. Nor is `-k` "keep the whole
 * `PackageUserState`": AOSP still clears per-user state on this path regardless of the flag.
 *
 * [DISABLE] is the only mechanic a freeze runs today. [UNINSTALL] exists because some OEM builds
 * refuse to let the shell uid disable their own system packages at all, and it used to be reached
 * automatically wherever the platform refused; [uninstallFreezeFallbackAllowed] now answers `false`
 * for every privilege mode, so nothing escalates into it and a refused disable fails visibly with
 * the package left installed. On API 37 it does not exist at shell uid at all; see [UNINSTALL].
 */
enum class FreezeMechanic {
    /** `pm disable`, or the equivalent `setApplicationEnabledSetting` reflection. Keeps data. */
    DISABLE,

    /**
     * `pm uninstall -k --user N`. Keeps the app's data directories, and — measured once, on a
     * shell-granted permission at API 36 — its runtime permission grants with them. What it changes
     * unconditionally is `FLAG_INSTALLED`, so the package stops resolving for this user without
     * `MATCH_UNINSTALLED_PACKAGES`.
     *
     * Not reachable at shell uid on API 37: Android 17 answers this command with
     * `Failure [only root can delete system app for a particular user]` where API 36 answers
     * `Success`. That restriction is specific to *this* mechanic and does not touch [DISABLE] —
     * on the same Android 17 build, at the same uid, `pm disable-user --user 0` and
     * `pm suspend --user 0` both succeed on a system package. "Android 17 blocks freezing system
     * apps" is not what was measured; "Android 17 reserves removing them for uid 0" is.
     */
    UNINSTALL,
}

/**
 * May a failed [FreezeMechanic.DISABLE] escalate to [FreezeMechanic.UNINSTALL] for this package?
 *
 * Disabled by default. Only Shizuku may use this fallback, after a platform refusal and
 * explicit device-local consent in Settings. Removal keeps data files but changes package
 * registration and may remove accounts. It applies to foreground and background freezes;
 * Unfreeze restores the package with install-existing. Root and Dhizuku never use this fallback.
 *
 * ### Why this is not a version check
 *
 * Kept because it is what a future consent path may and may not key on, and because the `sdkInt`
 * gate this replaced is the most likely thing for someone to reach for again.
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
 *  - **Android 17 did not change that either**, whatever the API 37 note on [FreezeMechanic.UNINSTALL]
 *    might suggest at a glance. On a stock API 37 emulator (`CE2A.260420.019`), as uid 2000,
 *    `pm disable-user --user 0 com.android.wallpaperbackup` reports "new state: disabled-user" and
 *    reads back `enabled=3 installed=true`, and `pm suspend --user 0` reports "new suspended state:
 *    true" and reads back `suspended=true`. What Android 17 *did* add is a guard on a different
 *    command — `PackageManagerShellCommand.java:2281-2293` now requires uid 0 for `--user` on a
 *    `FLAG_SYSTEM` package — which costs [FreezeMechanic.UNINSTALL] and leaves
 *    [FreezeMechanic.DISABLE] alone. `Flags.protectSystemRequiredPackages()` is not live on that
 *    build either: `device_config get package_manager_service protect_system_required_packages`
 *    reads null. So a version check keyed on 37 would be the same mistake as the one keyed on 36.
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
 * asks, and it is still worth asking with the gate shut: the gateways spend the same flag on
 * telling the user "this device refused" apart from "something went wrong".
 *
 * One thing the shell uid genuinely cannot do, on every release since API 25 and still true on 17:
 * set `COMPONENT_ENABLED_STATE_DISABLED` (state 2, what `pm disable` sends). It may only set
 * `DEFAULT`, `ENABLED` or `DISABLED_USER`. That is a *command* constraint, not a version one, and
 * Thor already satisfies it by sending `pm disable-user` from the Shizuku path.
 *
 * @param disableRefusedByPolicy true only when a privileged rung was refused by the platform —
 *   a `SecurityException` from `PackageManagerService`, not merely a non-zero exit or a read that
 *   came back unreadable. Passed in rather than inferred so this stays a pure function, and so the
 *   one decision that can remove a package for a user is reachable from a plain JVM test. Still
 *   load-bearing with the gate shut: the gateways read it to choose which failure the user is told
 *   about, and it is the only signal an explicit removal path would be allowed to key on.
 */
fun uninstallFreezeFallbackAllowed(
    isSystem: Boolean,
    privilegeMode: PrivilegeMode,
    disableRefusedByPolicy: Boolean,
    removalFallbackConsent: Boolean = false,
): Boolean = isSystem && disableRefusedByPolicy && removalFallbackConsent && when (privilegeMode) {
    PrivilegeMode.SHIZUKU -> true
    PrivilegeMode.ROOT, PrivilegeMode.DHIZUKU, PrivilegeMode.NONE -> false
}
