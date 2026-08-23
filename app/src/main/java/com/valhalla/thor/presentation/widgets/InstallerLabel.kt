// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.Installers
import com.valhalla.thor.util.UiText

/**
 * The name Thor gives an installer it knows, or null to fall back to the device's own label.
 *
 * The curated tier is not redundancy. `PackageManager` calls
 * [Installers.GOOGLE_PACKAGE_INSTALLER] "Package installer", which is accurate and tells a user
 * nothing; "Sideloaded" is the editorial answer, and Thor already ships it. Keep this list at
 * exactly the ids that are already here — everything else is `PackageManager`'s job, which is what
 * makes Aurora, Obtainium and the next store work with no code change.
 */
fun curatedInstallerLabel(installerPackageName: String): UiText? = when (installerPackageName) {
    Installers.PLAY_STORE -> UiText.StringResource(R.string.play_store)
    Installers.F_DROID -> UiText.StringResource(R.string.f_droid)
    // A device carries one of the two package-installer UIs, not both, so in practice only one of
    // these can ever be on screen at a time.
    in Installers.PACKAGE_INSTALLERS -> UiText.StringResource(R.string.sideloaded)
    else -> null
}

/**
 * Names the app that installed something: curated → the device's own label → the raw package id.
 *
 * [labelFor] is [com.valhalla.thor.domain.repository.InstallerLabelResolver.labelFor]; its null
 * answer means nothing by that id is installed any more, and the id itself is a better answer than
 * a blank — it is also what the Apps tab's source filter shows, so the two agree.
 *
 * Takes a non-null id on purpose. "Android recorded no installer" is not a name, and each screen
 * says something different about it — the chart folds it into Others, the picker says so in words.
 */
fun installerLabel(installerPackageName: String, labelFor: (String) -> String?): UiText =
    curatedInstallerLabel(installerPackageName)
        ?: UiText.DynamicString(labelFor(installerPackageName) ?: installerPackageName)
