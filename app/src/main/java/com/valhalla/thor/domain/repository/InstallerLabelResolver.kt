// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.repository

/**
 * Answers "what does this package call itself" for an installer id.
 *
 * Exists because the two places that name installers could only name the ones Thor hardcodes. Both
 * fell back to guessing from the id — the Apps list looked the installer up in the list of apps it
 * happened to be showing, so the same store was named on the User tab and a bare package id on the
 * System tab; the Home chart took the id's last segment, turning `com.aurora.store` into "STORE".
 * A package's label is a package-manager question, and this is the seam that lets a ViewModel ask
 * it without holding a `Context`.
 */
interface InstallerLabelResolver {

    /**
     * The label [packageName] declares, or null when nothing by that id is installed.
     *
     * Null is an ordinary answer, not a failure: an app outlives the store that installed it, so a
     * store the user has since removed is still recorded as the installer of everything it placed.
     */
    fun labelFor(packageName: String): String?
}
