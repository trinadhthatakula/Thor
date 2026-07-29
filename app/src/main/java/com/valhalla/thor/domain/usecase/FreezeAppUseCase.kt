// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FreezeTier
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.isBlockedFromFreeze
import com.valhalla.thor.domain.repository.AppRepository
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException
import org.koin.core.annotation.Factory

/**
 * Freeze one app — the single-app counterpart of the bulk paths' `freezableCandidates` filter,
 * and what any surface that wants to freeze a package should call.
 *
 * The [FreezeTier] block lives here because until this it lived in a composable: `AppRiskDialog`
 * renders no confirm button for `BLOCKED`, so on the three single-app paths there was simply
 * nothing tappable wired to the freeze. That covered every surface that existed and nothing that
 * did not — a shortcut, an extension trigger, an automation intent or a widget would reach for the
 * obvious entry point and hand a `packageName` straight to `pm uninstall --user`. The dialog is
 * still the front door; this is the backstop under it.
 *
 * Freeze-only on purpose. Unfreezing keeps calling [ManageAppUseCase] directly, because it is the
 * way *out* of a bad state — a block that caught it too would trap the very app it protects.
 *
 * Cost: one [AppRepository.getAppDetails] (a `getPackageInfo` plus the UAD lookup) per call.
 * Affordable because this runs on a single-app tap. The batch paths deliberately do **not** route
 * through here: they classify their whole target list against one shared snapshot and then call
 * [ManageAppUseCase.setAppDisabled], so routing them here would mean a redundant per-package
 * re-read *and* a second refusal for apps the user was already told about.
 */
@Factory
class FreezeAppUseCase(
    private val appRepository: AppRepository,
    private val manageAppUseCase: ManageAppUseCase,
) {
    /**
     * @param mode [FreezerMode.SUSPEND] freezes by suspending instead of disabling. Gated
     * identically: the watchlist's `blockedFromFreeze` already covers both mechanics, so flipping
     * the Freezer's mode must not become a way around the block.
     *
     * Failure carries a [UiTextException] so callers only have to render it —
     * `R.string.error_unsafe_skipped`, the same message `FreezerViewModel.toggleManaged` emits.
     */
    suspend operator fun invoke(
        packageName: String,
        mode: FreezerMode = FreezerMode.FREEZE,
    ): Result<Unit> {
        // Resolved here rather than taken from the caller's AppInfo: a view model's snapshot is
        // whatever the last rescan left behind, and the point of this class is that a caller with
        // nothing but a package name is still covered.
        //
        // runCatching, not a bare call: "the lookup threw" has to reach isBlockedFromFreeze as a
        // null so it refuses, exactly like "the lookup returned nothing". Let the throw escape and
        // it leaves invoke() past the gate entirely — every caller is a bare `viewModelScope.launch`
        // with no catch, so instead of the skipped toast the user gets a crash. The shipped
        // AppRepository already swallows to null, so this is the contract holding rather than a live
        // bug; the point is that the next implementation cannot quietly reopen the hole.
        val app = runCatching { appRepository.getAppDetails(packageName) }.getOrNull()
        if (isBlockedFromFreeze(app)) {
            return Result.failure(
                UiTextException(UiText.StringResource(R.string.error_unsafe_skipped))
            )
        }
        return when (mode) {
            FreezerMode.SUSPEND -> manageAppUseCase.setAppSuspended(packageName, true)
            FreezerMode.FREEZE -> manageAppUseCase.setAppDisabled(packageName, true)
        }
    }
}
