// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.domain.model.AnimationIntensity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Head start given to the screen-entry animation before the package scan begins. Scanning every
 * installed package while the navigation/bottom-bar transition is still running contends for CPU
 * and commits a large list update mid-animation, which drops frames.
 *
 * Scaled to the animation the user actually gets, per `MainScreen`'s entry/exit specs:
 * - [AnimationIntensity.LOW] uses `snap()`, i.e. no animation at all, so there is nothing to settle
 *   and the scan must start immediately.
 * - [AnimationIntensity.MEDIUM] runs the spatial/effects motion specs.
 * - [AnimationIntensity.HIGH] runs those *plus* shared-element transitions
 *   (`SharedTransitionLayout`), which animate bounds across destinations and are the most sensitive
 *   to a competing scan — hence the longest head start.
 *
 * Only the navigation-entry paths pay this. A deliberate pull-to-refresh has no transition to
 * protect, so it must not wait at all.
 */
fun settleDelayFor(intensity: AnimationIntensity): Duration = when (intensity) {
    AnimationIntensity.LOW -> Duration.ZERO
    AnimationIntensity.MEDIUM -> 400.milliseconds
    AnimationIntensity.HIGH -> 800.milliseconds
}

/**
 * Minimum time the pull-to-refresh indicator stays on screen after a manual refresh.
 *
 * This throttles the *indicator*, never the scan — see `AppListViewModel.holdRefreshIndicator`.
 * It is deliberately not scaled by [AnimationIntensity]: a refresh is a direct manipulation whose
 * result the user is waiting on, so the feedback must be legible regardless of how much motion
 * they have asked for elsewhere.
 */
val REFRESH_INDICATOR_MIN_VISIBLE: Duration = 600.milliseconds
