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
        existingNames.any { equalsNoCase(it, name) } -> ProfileNameError.DUPLICATE
        else -> ProfileNameError.OK
    }
}

/**
 * Equality under SQLite's `NOCASE` collation — which folds **ASCII only**.
 *
 * Deliberately not `String.equals(ignoreCase = true)`: Kotlin's fold is Unicode-aware, so it
 * calls "Ä" and "ä" the same name while `COLLATE NOCASE` does not. That mismatch is a
 * false rejection, and the worse direction of the two — the user is told a name is taken, the
 * inline error cannot be dismissed, and the profile the database would have accepted can never
 * be saved. Erring the other way costs nothing: [profileNameError] is a courtesy check and
 * `FreezerViewModel.runProfileWrite` still catches the constraint violation.
 */
private fun equalsNoCase(a: String, b: String): Boolean {
    // Length is safe to compare first because the fold is per-character; nothing here maps one
    // char onto two the way a Unicode fold can (German ß → ss).
    if (a.length != b.length) return false
    return a.indices.all { foldAscii(a[it]) == foldAscii(b[it]) }
}

private fun foldAscii(c: Char): Char = if (c in 'A'..'Z') c + 32 else c
