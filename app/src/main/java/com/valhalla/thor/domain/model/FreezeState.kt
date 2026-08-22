// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** An app's freeze state as far as a bulk run cares. [ABSENT] = not installed. */
enum class FreezeState { FROZEN, ACTIVE, ABSENT }

/**
 * Everything a bulk run needs to know about one package: where it is now, and whether policy
 * lets us freeze it at all.
 *
 * Both halves come from a single read so they cannot disagree. Asking for [state] and
 * [blockedFromFreeze] separately would mean a second PackageManager binder call per watchlist
 * entry on every QS shade open, and a window in which the two answers describe different
 * instants.
 */
data class FreezeCandidate(
    val state: FreezeState,
    /**
     * True when [FreezeTier.BLOCKED] applies — an unsafe system app, or any system app while the
     * UAD list is unreadable. Freeze-only: it says nothing about unfreezing, which is always
     * allowed and is in fact the way *out* of a bad state.
     */
    val blockedFromFreeze: Boolean = false,
)

/**
 * The packages a bulk [op] would actually act on, in watchlist order.
 *
 * This is the fix for the tile counting watchlist rows: the freezer watchlist is invariant
 * under freeze/unfreeze, so only the live per-app state can tell us whether there is
 * anything left to do. [FreezeState.ABSENT] packages are skipped but deliberately left in
 * the watchlist — pruning them is out of scope.
 *
 * Written as two explicit branches rather than one `wanted` state plus a shared filter so the
 * asymmetry is structural: [BulkOp.FREEZE] honours [FreezeCandidate.blockedFromFreeze],
 * [BulkOp.UNFREEZE] must not. A blocked app that somehow got frozen has to stay unfreezable,
 * or the block would trap it.
 */
fun freezableCandidates(
    watchlist: List<String>,
    op: BulkOp,
    candidateOf: (String) -> FreezeCandidate,
): List<String> = when (op) {
    BulkOp.FREEZE -> watchlist.filter {
        val candidate = candidateOf(it)
        candidate.state == FreezeState.ACTIVE && !candidate.blockedFromFreeze
    }

    BulkOp.UNFREEZE -> watchlist.filter { candidateOf(it).state == FreezeState.FROZEN }
}

/**
 * The members of a profile a force-stop would actually act on.
 *
 * The same question [freezableCandidates] answers, for the one profile verb that does not go
 * through the bulk runner. Kill stays out of [BulkOp] on purpose — it is orthogonal to freezing
 * rather than a third direction, and putting it in the runner would force an answer to "does a kill
 * cancel an in-flight freeze?", where the honest answer is that the two do not interact.
 *
 * Two filters, one reason each. **Not installed** is a member whose package is gone — the profile
 * keeps the name, but there is no process to stop. **Frozen** is a member that cannot be running by
 * construction: a disabled package has no components to start and a suspended one has already been
 * stopped. Neither belongs in a count the user is about to be shown and asked to confirm.
 *
 * Against today's mapper those two overlap: `AppInfoMapper` folds `FLAG_INSTALLED` into `enabled`,
 * so an app uninstalled for this user already reads as frozen and the first clause catches nothing
 * the second would miss. It is written anyway because the two are independent fields on [AppInfo] —
 * a mapper that stopped folding would otherwise start sending force-stops at absent packages, and
 * the failure would be silent.
 *
 * Reads the app-list snapshot the screen is already rendering rather than the live
 * `AppFreezeStateReader`, which is one binder call per member and lives a layer the view models
 * deliberately cannot reach. The cost of the staleness is bounded and one-directional: an app
 * frozen since the last scan is asked to stop and does nothing.
 */
fun killableMembers(members: List<String>, installed: List<AppInfo>): List<AppInfo> {
    if (members.isEmpty()) return emptyList()
    val byPackage = installed.associateBy { it.packageName }
    // Iterating the members preserves the profile's own order and silently drops the absent ones,
    // which is what "not installed" has to mean here — the snapshot has no row for them at all.
    return members.mapNotNull(byPackage::get).filter { it.isInstalled && !it.isFrozen }
}
