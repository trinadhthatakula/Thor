// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * A named group of apps the user can freeze or unfreeze in one tap.
 *
 * Deliberately *not* a subset of the freezer watchlist. Watchlist membership is a standing
 * instruction that the QS tile and both launcher Freeze-all shortcuts act on with no UI at all,
 * so folding profiles into it would mean creating a "Games" profile silently enlists every game
 * in the tile's Freeze-all. The two lists stay independent; what they share is the runner, and
 * therefore the tier gate.
 */
data class FreezeProfile(
    val id: Long,
    val name: String,
    val packageNames: List<String>,
) {
    val size: Int get() = packageNames.size
}

/** The longest profile name the editor accepts. Long enough to be descriptive, short enough
 * to render on one line in a sheet row next to two run buttons and an overflow. */
const val MAX_PROFILE_NAME_LENGTH = 40

/**
 * Why a profile name was rejected, or [OK].
 *
 * A value rather than a thrown exception because the editor needs to render the reason *while
 * the user is still typing*, before anything is saved.
 */
enum class ProfileNameError { OK, BLANK, TOO_LONG, DUPLICATE }

/** Names are stored trimmed: " Games " and "Games" are the same profile to a user. */
fun normalizeProfileName(raw: String): String = raw.trim()

/**
 * Validate a profile name against the names already in use.
 *
 * [existingNames] is every *other* profile's name — a rename that keeps the same name must not
 * report itself as a duplicate, so callers exclude the profile being edited.
 *
 * Case-insensitive to match the `COLLATE NOCASE` unique index the database enforces. Checking
 * only in the UI would push the collision down to an SQLite constraint violation, which surfaces
 * as a crash in a coroutine with no catch rather than as a message under the text field.
 */
fun profileNameError(raw: String, existingNames: Collection<String>): ProfileNameError {
    val name = normalizeProfileName(raw)
    return when {
        name.isEmpty() -> ProfileNameError.BLANK
        name.length > MAX_PROFILE_NAME_LENGTH -> ProfileNameError.TOO_LONG
        existingNames.any { it.equals(name, ignoreCase = true) } -> ProfileNameError.DUPLICATE
        else -> ProfileNameError.OK
    }
}
