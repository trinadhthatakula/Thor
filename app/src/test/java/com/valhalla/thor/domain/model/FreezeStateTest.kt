// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure candidate filtering for bulk freeze/unfreeze. No Android deps. */
class FreezeStateTest {

    private val states = mapOf(
        "com.active.one" to FreezeState.ACTIVE,
        "com.active.two" to FreezeState.ACTIVE,
        "com.frozen.one" to FreezeState.FROZEN,
        "com.gone" to FreezeState.ABSENT,
    )
    private val watchlist = states.keys.toList()
    private val candidateOf: (String) -> FreezeCandidate = {
        FreezeCandidate(states[it] ?: FreezeState.ABSENT)
    }

    @Test
    fun `freeze targets only active apps`() {
        assertEquals(
            listOf("com.active.one", "com.active.two"),
            freezableCandidates(watchlist, BulkOp.FREEZE, candidateOf)
        )
    }

    @Test
    fun `unfreeze targets only frozen apps`() {
        assertEquals(
            listOf("com.frozen.one"),
            freezableCandidates(watchlist, BulkOp.UNFREEZE, candidateOf)
        )
    }

    @Test
    fun `uninstalled packages are never candidates`() {
        val all = freezableCandidates(watchlist, BulkOp.FREEZE, candidateOf) +
                freezableCandidates(watchlist, BulkOp.UNFREEZE, candidateOf)
        assertEquals(emptyList<String>(), all.filter { it == "com.gone" })
    }

    @Test
    fun `a fully frozen watchlist yields no freeze candidates`() {
        // This is the reported bug: the tile must go INACTIVE here, and it can only do that
        // if the candidate list is empty rather than the watchlist size.
        val allFrozen = listOf("a", "b", "c")
        assertEquals(
            emptyList<String>(),
            freezableCandidates(allFrozen, BulkOp.FREEZE) { FreezeCandidate(FreezeState.FROZEN) }
        )
    }

    @Test
    fun `an empty watchlist yields no candidates`() {
        assertEquals(
            emptyList<String>(),
            freezableCandidates(emptyList(), BulkOp.FREEZE, candidateOf)
        )
    }

    @Test
    fun `candidate order follows the watchlist`() {
        val reversed = listOf("com.active.two", "com.active.one")
        assertEquals(
            listOf("com.active.two", "com.active.one"),
            freezableCandidates(reversed, BulkOp.FREEZE, candidateOf)
        )
    }

    // --- blocked tier ---------------------------------------------------------------------
    //
    // The QS tile and the launcher Freeze-all shortcut act on the watchlist with no dialog in
    // front of them, so this filter is the only thing standing between a stored watchlist entry
    // and a freeze — disable, or removal for this user where disabling is not available — on a
    // package the in-app dialog refuses to freeze at all.

    private fun blocked(state: FreezeState) = FreezeCandidate(state, blockedFromFreeze = true)

    @Test
    fun `a blocked active app is not a freeze candidate`() {
        assertEquals(
            emptyList<String>(),
            freezableCandidates(listOf("com.unsafe"), BulkOp.FREEZE) { blocked(FreezeState.ACTIVE) }
        )
    }

    @Test
    fun `a blocked frozen app is still an unfreeze candidate`() {
        // The asymmetry that makes the block safe. An app can be in the watchlist frozen from
        // before it was ever classified (or from a Thor version without this filter); gating
        // unfreeze on the same predicate would trap it frozen with no in-app way out.
        assertEquals(
            listOf("com.unsafe"),
            freezableCandidates(listOf("com.unsafe"), BulkOp.UNFREEZE) {
                blocked(FreezeState.FROZEN)
            }
        )
    }

    @Test
    fun `blocked apps are dropped but their neighbours survive`() {
        val mixed = listOf("com.ok.one", "com.unsafe", "com.ok.two")
        assertEquals(
            listOf("com.ok.one", "com.ok.two"),
            freezableCandidates(mixed, BulkOp.FREEZE) {
                if (it == "com.unsafe") blocked(FreezeState.ACTIVE)
                else FreezeCandidate(FreezeState.ACTIVE)
            }
        )
    }

    @Test
    fun `an all-blocked watchlist yields no freeze candidates`() {
        // What the tile has to paint INACTIVE. A count taken any other way would advertise
        // "Freeze 3" over a batch that then froze nothing.
        assertEquals(
            emptyList<String>(),
            freezableCandidates(listOf("a", "b", "c"), BulkOp.FREEZE) { blocked(FreezeState.ACTIVE) }
        )
    }

    @Test
    fun `blockedFromFreeze defaults to false`() {
        // Guards the default: flipping it would silently empty every freeze batch.
        assertEquals(false, FreezeCandidate(FreezeState.ACTIVE).blockedFromFreeze)
    }
}

/** The freeze-risk tier shared by the tile, the bulk paths and the in-app dialogs. */
class FreezePolicyTest {

    @Test
    fun `user apps are never blocked whatever the recommendation says`() {
        // bloatRecommendation is meaningless for a user app, and freezing one is reversible
        // with pm enable — so a stale UAD row must not gate it.
        assertEquals(
            FreezeTier.NORMAL,
            freezeTierOf(isSystem = false, bloatRecommendation = "Unsafe", isUadLoadFailed = false)
        )
    }

    @Test
    fun `user apps are not blocked by a failed UAD load either`() {
        assertEquals(
            FreezeTier.NORMAL,
            freezeTierOf(isSystem = false, bloatRecommendation = null, isUadLoadFailed = true)
        )
    }

    @Test
    fun `an unsafe system app is blocked`() {
        // Capitalised exactly as uad_lists.json stores it. A comparison that forgets
        // .lowercase() matches nothing here and the whole gate becomes a silent no-op that
        // still passes every other test in this class.
        assertEquals(
            FreezeTier.BLOCKED,
            freezeTierOf(isSystem = true, bloatRecommendation = "Unsafe", isUadLoadFailed = false)
        )
    }

    @Test
    fun `an expert system app warns but is not blocked`() {
        assertEquals(
            FreezeTier.EXPERT,
            freezeTierOf(isSystem = true, bloatRecommendation = "Expert", isUadLoadFailed = false)
        )
    }

    @Test
    fun `a recommended system app is normal`() {
        assertEquals(
            FreezeTier.NORMAL,
            freezeTierOf(
                isSystem = true,
                bloatRecommendation = "Recommended",
                isUadLoadFailed = false
            )
        )
    }

    @Test
    fun `an unclassified system app is normal`() {
        // Present in the list but with no removal advice, or absent from it entirely: Thor has
        // always allowed these, and blocking them would take most of the system list away.
        assertEquals(
            FreezeTier.NORMAL,
            freezeTierOf(isSystem = true, bloatRecommendation = null, isUadLoadFailed = false)
        )
    }

    @Test
    fun `a failed UAD load blocks every system app`() {
        // Fail closed: with no list we cannot tell a safe system app from a bootloop.
        assertEquals(
            FreezeTier.BLOCKED,
            freezeTierOf(isSystem = true, bloatRecommendation = null, isUadLoadFailed = true)
        )
    }

    @Test
    fun `a failed UAD load outranks a benign recommendation`() {
        // The recommendation string cannot be trusted when the load that produced it failed —
        // it is whatever a partially-populated or stale map happened to hold.
        assertEquals(
            FreezeTier.BLOCKED,
            freezeTierOf(
                isSystem = true,
                bloatRecommendation = "Recommended",
                isUadLoadFailed = true
            )
        )
    }

    // ── freezeNeedsConfirmation: which freezes the user may switch the dialog off for ──
    //
    // Every freeze surface calls this before raising AppRiskDialog, so it is the whole gate. The
    // dialog is also the only refusal a BLOCKED app gets on these paths, which is why the suppression
    // lives out here rather than inside it.

    private fun systemApp(recommendation: String?, uadFailed: Boolean = false) = AppInfo(
        packageName = "com.system.app",
        isSystem = true,
        bloatRecommendation = recommendation,
        isUadLoadFailed = uadFailed
    )

    @Test
    fun `a user app is never confirmed, whether or not the setting is on`() {
        val user = AppInfo(packageName = "com.user.app", isSystem = false)
        assertEquals(false, freezeNeedsConfirmation(user, skipRoutineConfirmation = false))
        assertEquals(false, freezeNeedsConfirmation(user, skipRoutineConfirmation = true))
    }

    @Test
    fun `a routine system app is confirmed by default and skipped when asked`() {
        // The tap the setting exists for: debloating a fresh device answers this dialog dozens of
        // times, and the last answer carries no more information than the first.
        val routine = systemApp("Recommended")
        assertEquals(true, freezeNeedsConfirmation(routine, skipRoutineConfirmation = false))
        assertEquals(false, freezeNeedsConfirmation(routine, skipRoutineConfirmation = true))
    }

    @Test
    fun `an unclassified system app counts as routine`() {
        // Most of the system list: present in uad_lists.json with no advice, or absent from it. It
        // is FreezeTier.NORMAL, so leaving it out of the setting's reach would make the setting
        // apply to almost nothing.
        assertEquals(false, freezeNeedsConfirmation(systemApp(null), skipRoutineConfirmation = true))
    }

    @Test
    fun `an expert system app is confirmed however the setting is set`() {
        // The load-bearing one. EXPERT is a per-app verdict about *this* package, and nothing under
        // the dialog re-checks it — FreezeAppUseCase refuses BLOCKED and nothing else. A "don't ask
        // me" about tedium must not quietly become one about risk.
        val expert = systemApp("Expert")
        assertEquals(true, freezeNeedsConfirmation(expert, skipRoutineConfirmation = false))
        assertEquals(true, freezeNeedsConfirmation(expert, skipRoutineConfirmation = true))
    }

    @Test
    fun `a blocked system app still raises the dialog, because that is how it is refused`() {
        // AppRiskDialog renders no confirm button for BLOCKED. Suppressing it here would not skip a
        // confirmation, it would skip the refusal and freeze the app.
        val unsafe = systemApp("Unsafe")
        assertEquals(true, freezeNeedsConfirmation(unsafe, skipRoutineConfirmation = true))
        // Same for the fail-closed branch: no usable UAD list at all.
        val unreadable = systemApp("Recommended", uadFailed = true)
        assertEquals(true, freezeNeedsConfirmation(unreadable, skipRoutineConfirmation = true))
    }

    // ── killableMembers: the same question for the one profile verb outside the runner ──

    private fun app(
        packageName: String,
        enabled: Boolean = true,
        isSuspended: Boolean = false,
        isInstalled: Boolean = true,
    ) = AppInfo(
        packageName = packageName,
        enabled = enabled,
        isSuspended = isSuspended,
        isInstalled = isInstalled
    )

    @Test
    fun `a force-stop targets only the members that could be running`() {
        val installed = listOf(
            app("com.active.one"),
            app("com.disabled", enabled = false),
            app("com.suspended", isSuspended = true),
            app("com.active.two"),
        )
        // "com.never.installed" is in the profile and in no snapshot row at all — the shape a
        // profile takes on after its app is uninstalled, or after the profile came off a backup.
        val members = listOf(
            "com.active.one", "com.disabled", "com.suspended", "com.never.installed",
            "com.active.two"
        )

        assertEquals(
            listOf("com.active.one", "com.active.two"),
            killableMembers(members, installed).map { it.packageName }
        )
    }

    @Test
    fun `the profile's own order survives, because the count sits under a confirmation`() {
        val installed = listOf(app("c"), app("b"), app("a"))
        assertEquals(
            listOf("a", "b", "c"),
            killableMembers(listOf("a", "b", "c"), installed).map { it.packageName }
        )
    }

    @Test
    fun `a member uninstalled for this user is dropped even while it reads as enabled`() {
        // Belt and braces, and the test says so: AppInfoMapper folds FLAG_INSTALLED into `enabled`,
        // so this row cannot be produced today. It pins the second clause against a mapper that
        // stops folding — the alternative is force-stopping a package that is not there, silently.
        assertEquals(
            emptyList<String>(),
            killableMembers(listOf("a"), listOf(app("a", isInstalled = false)))
                .map { it.packageName }
        )
    }

    @Test
    fun `a profile whose members are all frozen offers nothing to stop`() {
        // What the disabled menu item is derived from. "Force stop 0 apps" behind a confirmation
        // dialog is the outcome this makes visible before the tap instead of after it.
        val installed = listOf(app("a", enabled = false), app("b", isSuspended = true))
        assertEquals(emptyList<AppInfo>(), killableMembers(listOf("a", "b"), installed))
    }

    @Test
    fun `an empty profile is answered without touching the snapshot`() {
        assertEquals(emptyList<AppInfo>(), killableMembers(emptyList(), listOf(app("a"))))
    }
}
