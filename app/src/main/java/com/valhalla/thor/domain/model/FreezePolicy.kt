// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * How dangerous it is to freeze a package, derived from its UAD (Universal Android Debloater)
 * recommendation.
 *
 * Only system apps have a tier. Freezing a user app is always reversible with `pm enable`, so
 * there is nothing to warn about; system apps are frozen with `pm uninstall --user`, and the
 * ones the UAD list marks unsafe can leave the device unable to boot, place calls, or reach the
 * network.
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
