// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.domain.model.AnimationIntensity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Head start given to the UI before the package scan begins. Scanning every installed package
 * contends for CPU and commits a large list update, which drops frames if it lands while the app is
 * still drawing its first screens.
 *
 * What this actually protects is **app-launch frames**, not a navigation transition. `MainScreen`
 * takes `appListViewModel` as an eager default parameter, so this ViewModel's `init` — and therefore
 * this delay — runs during MainScreen's *first composition*, alongside the splash exit and the Home
 * screen's first frames. No entry animation is running at that moment at any intensity:
 * `HomeActivity` composes `MainScreen` from a plain `when` with no transition, and neither
 * `NavDisplay`'s `AnimatedContent` nor the bottom bar's `AnimatedVisibility` animates on first
 * composition (`initialState == targetState`). By the time the user taps the Apps tab the list is
 * already loaded, so `AppListScreen`'s entry `LaunchedEffect` is gated off and the tab transition
 * pays nothing.
 *
 * [AnimationIntensity] is therefore used as a **proxy for how much the user values smoothness over
 * immediacy**, not as a literal description of which animation is in flight. Someone who turned
 * motion down wants their data sooner; someone on HIGH has asked for polish and can afford to wait.
 * Note the setting's own specs do not distinguish MEDIUM from HIGH for screen entry — `MainScreen`
 * gives both the same spatial/effects specs and only adds shared-element transitions at HIGH, on the
 * list-to-detail path, which never reaches this function. So the 400/800 split is a deliberate
 * preference gradient, not a measured animation length.
 *
 * Only the navigation-entry paths pay this at all. A deliberate pull-to-refresh has nothing to
 * protect and must not wait — see `AppListViewModel.loadApps`.
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
