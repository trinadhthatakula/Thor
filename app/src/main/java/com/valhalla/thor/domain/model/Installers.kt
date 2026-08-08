// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * The installer package ids Thor recognises by name.
 *
 * These were string literals repeated across the Home dashboard, the Apps list and the Fix Store
 * action, and the copies had already drifted: two of them knew that AOSP ships
 * [AOSP_PACKAGE_INSTALLER] where Google ships [GOOGLE_PACKAGE_INSTALLER], and one did not — which
 * is how Fix Store came to offer to "fix" apps a de-Googled ROM had sideloaded perfectly normally.
 */
object Installers {

    /** The Play Store. */
    const val PLAY_STORE = "com.android.vending"

    /** F-Droid proper. Forks and alternative clients report their own ids. */
    const val F_DROID = "org.fdroid.fdroid"

    /** Google's system package-installer UI — what a sideload records on a device with GMS. */
    const val GOOGLE_PACKAGE_INSTALLER = "com.google.android.packageinstaller"

    /** AOSP's package-installer UI, used by the ROMs that ship without Google's. */
    const val AOSP_PACKAGE_INSTALLER = "com.android.packageinstaller"

    /**
     * The placeholder Thor stores when Android reports no installer at all.
     *
     * Distinct from a `null` installer only by which layer produced it, so anything grouping or
     * filtering on the installer has to treat the two the same.
     */
    const val UNKNOWN = "Unknown"

    /** Both system package-installer UIs, either of which means "sideloaded". */
    val PACKAGE_INSTALLERS = setOf(GOOGLE_PACKAGE_INSTALLER, AOSP_PACKAGE_INSTALLER)
}

/**
 * The apps Fix Store would rewrite the installer record of: everything in [apps] that Play did not
 * already install, minus Thor itself.
 *
 * An app with **no** recorded installer is a candidate. That is the case the feature exists for —
 * Android records nothing for an `adb install` or a restored backup, and Play then declines to
 * update it.
 *
 * Both package-installer ids are excluded, not just Google's. A sideloaded app already has an
 * honest installer record; rewriting it buys nothing and costs the signature risk. The predicate on
 * the Home card and the one behind the action were separate copies of this rule and only one of them
 * knew about [Installers.AOSP_PACKAGE_INSTALLER], so a de-Googled ROM saw a badge counting apps the
 * picker would then offer to "fix".
 *
 * [thorPackageName] is passed in rather than read from `BuildConfig` so this stays a pure function —
 * and because the debug build's `applicationIdSuffix` makes the running package id something no
 * constant in the source spells.
 */
fun fixStoreCandidates(apps: List<AppInfo>, thorPackageName: String): List<AppInfo> =
    apps.filter { app ->
        val installer = app.installerPackageName
        app.packageName != thorPackageName &&
                installer != Installers.PLAY_STORE &&
                (installer == null || installer !in Installers.PACKAGE_INSTALLERS)
    }
