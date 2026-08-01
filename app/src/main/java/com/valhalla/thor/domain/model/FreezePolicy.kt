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
 * How a freeze was actually carried out. The two are not interchangeable, and what separates them
 * is the user's data.
 *
 * [DISABLE] is reversible in the sense users expect: the package stays installed, keeps its data,
 * and `pm enable` returns it exactly as it was. [UNINSTALL] is `pm uninstall --user N` *without*
 * `-k`, which removes the package for the current user and takes its data with it —
 * `pm install-existing` brings the app back factory-fresh, with its logins, settings and local
 * content gone.
 *
 * Thor prefers [DISABLE] everywhere it works. [UNINSTALL] exists because on some platform and
 * privilege combinations disabling a system app is not permitted at all, and a freeze that cannot
 * happen is worse than one that costs data the user was warned about — but *only* on those
 * combinations, which is what [destructiveFreezeFallbackAllowed] decides.
 */
enum class FreezeMechanic {
    /** `pm disable`, or the equivalent `setApplicationEnabledSetting` reflection. Keeps data. */
    DISABLE,

    /** `pm uninstall --user N`, no `-k`. Destroys the app's data for the current user. */
    UNINSTALL,
}

/**
 * Android 16 (Baklava). Named rather than inlined because this one number is the entire reason
 * [destructiveFreezeFallbackAllowed] exists, and a bare `36` at a call site reads like a typo.
 *
 * `data/source/local/shizuku/Targets.B` is the data-layer mirror of this check. The duplication is
 * deliberate: `domain` must not import from `data`, and the alternative — passing `Targets.B` down
 * as a boolean — would hide *which* version boundary a caller meant.
 */
const val ANDROID_16_BAKLAVA = 36

/**
 * May a failed [FreezeMechanic.DISABLE] escalate to [FreezeMechanic.UNINSTALL] for this package?
 *
 * This gate is the difference between a fallback chain and a data-loss bug. Without it, *any*
 * transient failure to disable — a busy PackageManager, a binder timeout, an OEM refusing one
 * specific package — silently escalates to destroying that app's data, and the user is told the
 * freeze succeeded. With it, escalation is confined to the one combination where disabling a
 * system app is genuinely not available, and everywhere else a failure to disable stays a
 * *failure*, visible and reportable.
 *
 * Failing loudly is the deliberate choice here. A freeze that reports an error costs the user an
 * annoyance and Thor a bug report; a freeze that quietly wipes an app's data costs the user
 * something they cannot get back and produces no report at all, because from the outside it looked
 * like it worked.
 *
 * @param sdkInt injected rather than read from `Build.VERSION.SDK_INT` so this stays a pure
 *   function that unit tests can drive across the version boundary — the boundary being the whole
 *   point, it is the one thing that must be testable.
 */
fun destructiveFreezeFallbackAllowed(
    isSystem: Boolean,
    privilegeMode: PrivilegeMode,
    sdkInt: Int,
): Boolean = when {
    // A user app disables with `pm enable`/`pm disable` on every supported release, so there is
    // no platform gap to work around and no reason to ever reach for the destructive mechanic.
    !isSystem -> false

    else -> when (privilegeMode) {
        // The reported gap. Under the shell uid, disabling a *system* app stopped being available
        // on Android 16, so uninstall-for-user is the only remaining way to freeze one and
        // refusing to escalate would mean Shizuku users on 16+ simply cannot freeze system apps.
        PrivilegeMode.SHIZUKU -> sdkInt >= ANDROID_16_BAKLAVA

        // Root can disable any package on any release, so a failure here is a real failure worth
        // surfacing rather than a platform restriction worth working around. This is a behaviour
        // change: root used to freeze system apps by uninstalling them unconditionally, so a
        // package that root cannot disable now reports an error where it previously "worked" by
        // destroying data.
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
