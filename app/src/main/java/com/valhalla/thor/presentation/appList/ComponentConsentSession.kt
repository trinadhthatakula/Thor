// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import org.koin.core.annotation.Single

/**
 * Whether the component-disable disclaimer has been silenced **for this launch of Thor**.
 *
 * Disabling a component is the one action in Thor that can break an app without the app ever
 * appearing changed — it stays installed, enabled and launchable while some part of it silently
 * stops working. The disclaimer is the only warning the user gets, so it asks **every time** by
 * default, and silencing it is opt-in per session rather than a permanent answer.
 *
 * Deliberately not persisted, and that is the whole design:
 *
 * - **A permanent "don't ask again" disarms the warning forever** on the strength of one tap that
 *   may have been a mis-tap. A session is long enough to cover the real use case — one sitting spent
 *   trimming several apps — and short enough that the warning comes back for the next one.
 * - **Nothing to read means nothing to wait for.** A persisted flag has to be loaded before the gate
 *   can be evaluated, which leaves a window where the answer is *unknown*; the previous version of
 *   this gate defaulted that window to "accepted" and so skipped the disclaimer entirely if a
 *   disable was requested before DataStore had emitted. An in-memory flag that starts `false` cannot
 *   have that bug: unknown does not exist, and the default is to ask.
 * - **No clock.** A 24-hour or calendar-day window would have to trust the device clock and time
 *   zone, both of which can move under it.
 *
 * A `@Single` rather than state on the ViewModel because [ComponentControlViewModel] is keyed per
 * package: holding the flag there would re-ask for every app the user opens, which is the annoyance
 * the checkbox exists to prevent. Process death clears it, which is exactly the intended lifetime.
 *
 * The extension-manager consent is a different decision and stays persisted — that one is a
 * grant-and-forget liability acknowledgement for a whole feature, not a per-action warning.
 */
@Single
class ComponentConsentSession {

    /** True once the user has ticked "don't ask again" and confirmed, until the process dies. */
    var isDisclaimerSilenced: Boolean = false
        private set

    fun silenceForSession() {
        isDisclaimerSilenced = true
    }
}
