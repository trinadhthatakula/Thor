// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * Which tab Thor opens on at launch.
 *
 * A deliberate mirror of the presentation layer's `AppDestinations` rather than a reuse of it: that
 * enum's constructor holds `R.string` and `R.drawable` ids, so putting it on [UserPreferences] would
 * point domain at presentation and drag Android resources into a layer that has none. The two are
 * joined in one place, `MainScreen.toDestination()`.
 *
 * Persisted by `name`, so the order of these entries is free to change but their spellings are not.
 */
enum class DefaultTab {
    HOME,
    APPS,
    FREEZER,
    SETTINGS
}
