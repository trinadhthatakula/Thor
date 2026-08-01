// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * The apps the Freezer's "you have disabled apps that aren't in the Freezer — import them?" prompt
 * should offer, in scan order.
 *
 * Pure list logic, extracted out of `FreezerScreen` for one reason: the rule was wrong for a month
 * and nothing noticed, because a filter written inline inside a Composable has nowhere to be tested
 * from. `freezableCandidates` is the same shape and the same lesson.
 *
 * Four conditions, none of them redundant:
 *
 * **`!enabled`** — the app is frozen. Note that [AppInfo.enabled] is not the platform's
 * `ApplicationInfo.enabled`: `AppInfoMapper` and `AppRepositoryImpl` both fold `FLAG_INSTALLED`
 * into it, so this is true for a `pm disable`d package *and* for one removed with
 * `pm uninstall --user N`. That is why the next condition exists.
 *
 * **`isInstalled`** — narrows the offer to [FreezeMechanic.DISABLE], the mechanic Thor now prefers
 * everywhere the platform allows it. A package in the [FreezeMechanic.UNINSTALL] state is not
 * offered, and deliberately so: nothing in a `PackageManager` read distinguishes "Thor removed this
 * for the user" from "the vendor shipped it removed" or "another debloater removed it", and
 * importing it means the next `Unfreeze all` runs `pm install-existing` on a package Thor never
 * touched. The same reasoning covers a *user* app uninstalled with its data retained: Thor cannot
 * bring it back at all, so tracking it in the watchlist only produces a row that fails every
 * unfreeze. Apps frozen at freeze time are offered the "Add to Freezer" snackbar
 * (`FreezerEvent.ShowFreezerPrompt`, `AppListEvent.ShowFreezerPrompt`) regardless of mechanic —
 * this list is the recovery path for freezes that happened elsewhere, not the primary one.
 *
 * **not already tracked** — the prompt is about apps the watchlist does not know.
 *
 * **[isBlockedFromFreeze]** — this replaces a blanket `!isSystem` clause. That clause was added
 * before Thor had a risk model at all (2026-06-21, five weeks before [freezeTierOf] existed) as a
 * coarse "system apps are dangerous, skip them all" proxy, and it has always excluded real,
 * Thor-frozen system apps rather than being the no-op it looked like. It is also the only place in
 * the Freezer that refuses system apps outright — the watchlist sheet, the freezer list and the
 * profile editor all have a SYSTEM tab. Expressing the proxy as the tier check is what it was
 * reaching for, and the check is load-bearing in its own right: [freezableCandidates] drops a
 * BLOCKED app from FREEZE runs but never from UNFREEZE ones, so importing one would be a one-way
 * door — `Unfreeze all` enables it and every later freeze refuses to put it back.
 *
 * The tier check also keeps the old behaviour as its failure mode. [freezeTierOf] answers BLOCKED
 * for *every* system app when the UAD list could not be read, so a device with no usable list
 * degrades this to "user apps only" — exactly what the blanket clause did — instead of offering a
 * set of system apps it could not classify.
 */
fun importableDisabledApps(
    allInstalledApps: List<AppInfo>,
    freezerPackageNames: Set<String>,
): List<AppInfo> = allInstalledApps.filter { app ->
    !app.enabled &&
            app.isInstalled &&
            app.packageName !in freezerPackageNames &&
            !isBlockedFromFreeze(app)
}
