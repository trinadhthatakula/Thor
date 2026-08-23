// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * How tightly the app grids pack their tiles — the Apps tab, the Freezer tab, and the two app
 * pickers in the Freezer's bottom sheets.
 *
 * Deliberately three named steps rather than a column count or an icon size in dp. A tile is not
 * one number: its cell width, icon, padding, corner radius and badge all have to move together, or
 * the icon is silently coerced smaller by its own `Modifier.size` while the corner radius stays put
 * and the tile renders as a pill. The dp table that keeps them in step lives beside the grids that
 * consume it, in `presentation/widgets/AppList.kt` — the same split `AnimationIntensity` and
 * `settleDelayFor` use, and for the same reason: `Dp` is a Compose type and this layer has no
 * Android dependencies.
 *
 * Persisted by `name`, so the order of these entries is free to change but their spellings are not.
 */
enum class AppGridDensity {
    COMPACT,
    DEFAULT,
    LARGE
}
