// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.data.source.local.UadSnapshot
import com.valhalla.thor.domain.model.FreezeCandidate
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.freezeTierOf
import com.valhalla.thor.domain.model.isFrozen
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Single

/**
 * Reads an app's live freeze state. The single place that answers "is this app frozen?",
 * replacing the inline copy that used to live in FreezerShortcutManager.
 *
 * [MATCH_FLAGS] covers both of Thor's freeze mechanics — MATCH_DISABLED_COMPONENTS for a
 * disabled package, MATCH_UNINSTALLED_PACKAGES for a system package uninstalled for the user —
 * and FLAG_SUSPENDED (API 24+) catches the suspend-mode case. Both mechanics are live: a system
 * app is frozen with `pm disable` where the platform allows it and with `pm uninstall --user N`
 * where it does not, and packages frozen the second way by earlier builds are still out there.
 */
@Single
class AppFreezeStateReader(
    private val packageManager: PackageManager,
) {
    /** Live freeze state only, for callers with no freeze decision to make. */
    fun stateOf(packageName: String): FreezeState =
        candidateOf(packageName, UadSnapshot.UNFILTERED).state

    /**
     * Live freeze state *and* the freeze-policy verdict, from one [ApplicationInfo] read.
     *
     * [uad] is passed in rather than resolved here so a bulk run classifies its whole watchlist
     * against one snapshot: `UadHelper` is not injected into this class on purpose, which keeps
     * a per-package `uadMap` touch — and the ~1.6 MB JSON parse hiding behind the first one —
     * off a loop that already costs one binder call per entry.
     */
    fun candidateOf(packageName: String, uad: UadSnapshot): FreezeCandidate = try {
        val info = packageManager.getApplicationInfo(packageName, MATCH_FLAGS)
        // MATCH_UNINSTALLED_PACKAGES is not optional. A *system* app is frozen with `pm disable`
        // where that works and with `pm uninstall --user N` where it does not — the gated
        // destructive fallback (`destructiveFreezeFallbackAllowed`), plus every package the
        // uninstall-only builds froze and that is still frozen today. A package in that second
        // state is not installed for this user, so the lookup throws NameNotFoundException without
        // the flag — the app then reads ABSENT and freezableCandidates drops it, which silently
        // emptied the Unfreeze-all target list. FLAG_INSTALLED then has to be folded into
        // `enabled`, the same way AppInfoMapper and AppRepositoryImpl already do it, or the
        // package comes back looking ACTIVE instead of FROZEN. One conjunction, one mechanic each:
        // `info.enabled` is what catches the `pm disable` half (that package *is* installed),
        // FLAG_INSTALLED is what catches the uninstall half (that package reports enabled == true).
        val enabled = info.enabled && (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
        val suspended = (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        // FLAG_SYSTEM alone, never OR'd with FLAG_UPDATED_SYSTEM_APP — that is what isSystem
        // means everywhere else in Thor (AppInfoMapper, RootSystemGateway.setAppDisabled), and
        // a tier computed from a different definition than the one the freeze itself uses would
        // block and unblock inconsistent sets of apps.
        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val tier = freezeTierOf(
            isSystem = isSystem,
            bloatRecommendation = uad.recommendationFor(packageName),
            isUadLoadFailed = uad.loadFailed,
        )
        FreezeCandidate(
            state = if (isFrozen(enabled, suspended)) FreezeState.FROZEN else FreezeState.ACTIVE,
            blockedFromFreeze = tier == FreezeTier.BLOCKED,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        // Unnamed: "no such package for this user" is the expected answer here, not an error.
        ABSENT
    } catch (e: CancellationException) {
        // CancellationException is an Exception in Kotlin. candidateOf runs inside the runner's
        // coroutines, so the broad catch below would otherwise turn a cancelled sweep into a
        // watchlist of ABSENT packages — silently, and looking exactly like success.
        throw e
    } catch (e: Exception) {
        // Restores the behaviour of the FreezerShortcutManager.isFrozen this class replaced,
        // which caught every Exception. A binder death or a DeadObjectException here reaches
        // the unguarded callers otherwise (FreezerShortcutManager.pinAppShortcut,
        // FreezerViewModel.pinAllToLauncher), and there is no CoroutineExceptionHandler in
        // :app to catch what escapes. Unreadable is treated as ABSENT: a bulk run skips the
        // package rather than acting on a state it could not confirm.
        Logger.e("AppFreezeStateReader", "could not read freeze state for $packageName", e)
        ABSENT
    }

    companion object {
        /**
         * The query flags any lookup of a possibly-frozen package needs, and the one definition of
         * them.
         *
         * MATCH_UNINSTALLED_PACKAGES for a package frozen with `pm uninstall --user N`, whose
         * lookup throws `NameNotFoundException` without it; MATCH_DISABLED_COMPONENTS for one
         * frozen with `pm disable`, which is belt-and-braces rather than load-bearing —
         * `getApplicationInfo` does not filter on the *application's* enabled setting, so a
         * disabled package resolves either way.
         *
         * Both mechanics are live, and neither flag can be dropped. `pm disable` is now what
         * freezes a system app wherever the platform permits it, and `pm uninstall --user N` is
         * what freezes one where it does not — the gated destructive fallback, see
         * `destructiveFreezeFallbackAllowed`. Even if that gate closed everywhere tomorrow,
         * MATCH_UNINSTALLED_PACKAGES would stay load-bearing: devices carry system apps that the
         * uninstall-only builds froze, and they read as ABSENT without it, which is precisely how
         * `Unfreeze all` would lose the packages that most need unfreezing.
         *
         * Public because the *copies* are what went wrong. `ExtensionOpsProvider` reported a frozen
         * system app as not-frozen to extensions until it grew its own pair, and
         * `FreezerShortcutManager` had neither flag — latent only because per-app pinned shortcuts
         * are gated to user apps. One definition, so the next site to need them cannot half-have
         * them.
         *
         * A caller that resolves an `ApplicationInfo` with these must fold FLAG_INSTALLED into
         * `enabled`, as [candidateOf] does: the lookup now *succeeds* for a package uninstalled for
         * this user and reports `enabled == true`, which trades one wrong answer for another.
         */
        const val MATCH_FLAGS =
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS

        // blockedFromFreeze = true, not the data class default: every path that produces ABSENT
        // is one where we could not read the app at all, so we also could not have classified
        // it. ABSENT already excludes the package from both ops, but leaving the verdict at
        // "not blocked" would quietly re-enable freezing if the state test ever moved.
        private val ABSENT = FreezeCandidate(FreezeState.ABSENT, blockedFromFreeze = true)
    }
}
