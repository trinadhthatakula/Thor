// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * What happened to an archive's game data.
 *
 * Lives in `domain.model` rather than beside `ObbInstaller`, which is where it was declared until
 * `AppArchiveInstaller` began returning it: a domain port cannot reference a data-layer type. The
 * members are unchanged — the shipped install path and the restore path both read the same three.
 */
sealed interface ObbPlacement {

    /** The archive declared no expansions, so there was nothing to do. */
    data object NotNeeded : ObbPlacement

    /** [count] expansion files are now in `Android/obb/<pkg>/`. */
    data class Placed(val count: Int) : ObbPlacement

    /**
     * The app installed but its game data did not land.
     *
     * Reported to the user rather than swallowed: an installed game that crashes on first launch
     * with no explanation is the failure mode GH#164 describes, and silence here would reproduce
     * it from the other direction.
     */
    data class Failed(val reason: String) : ObbPlacement
}
