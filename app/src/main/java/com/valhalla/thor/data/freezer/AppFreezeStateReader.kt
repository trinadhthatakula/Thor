// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.valhalla.thor.domain.model.FreezeState
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
 * and FLAG_SUSPENDED (API 24+) catches the suspend-mode case.
 */
@Single
class AppFreezeStateReader(
    private val packageManager: PackageManager,
) {
    fun stateOf(packageName: String): FreezeState = try {
        val info = packageManager.getApplicationInfo(packageName, MATCH_FLAGS)
        // MATCH_UNINSTALLED_PACKAGES is not optional. Thor freezes *system* apps with
        // `pm uninstall --user N`, not `pm disable`, so a frozen system app is not installed
        // for this user and the lookup throws NameNotFoundException without it — the app then
        // reads ABSENT and freezableCandidates drops it, which silently emptied the
        // Unfreeze-all target list. FLAG_INSTALLED then has to be folded into `enabled`, the
        // same way AppInfoMapper and AppRepositoryImpl already do it, or the package comes
        // back looking ACTIVE instead of FROZEN.
        val enabled = info.enabled && (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
        val suspended = (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        if (isFrozen(enabled, suspended)) FreezeState.FROZEN else FreezeState.ACTIVE
    } catch (_: PackageManager.NameNotFoundException) {
        // Unnamed: "no such package for this user" is the expected answer here, not an error.
        FreezeState.ABSENT
    } catch (e: CancellationException) {
        // CancellationException is an Exception in Kotlin. stateOf runs inside the runner's
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
        FreezeState.ABSENT
    }

    private companion object {
        const val MATCH_FLAGS =
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
    }
}
