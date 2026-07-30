// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * The Stormbringer launcher hook runs in the launcher's process, so the ContentProvider cannot
 * cryptographically prove the caller is our extension. We bound the blast radius instead: only
 * packages the user already handed to the Freezer may be restored. GH#239 / Stormbringer.
 *
 * [restorablePackages] is the union of the watchlist and every freeze profile's membership, not
 * the watchlist alone. Profiles are deliberately *not* a subset of the watchlist (see
 * [FreezeProfile]), so an app can be frozen by a profile it is the only member of — and a gate
 * that only knew about the watchlist would refuse to let the launcher wake that app up, leaving
 * tapping its icon a silent no-op with no in-app hint as to why.
 */
fun mayRestore(packageName: String, restorablePackages: Set<String>): Boolean =
    packageName.isNotBlank() && packageName in restorablePackages
