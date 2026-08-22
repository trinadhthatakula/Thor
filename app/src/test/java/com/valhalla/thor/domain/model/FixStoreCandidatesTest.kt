// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import com.valhalla.thor.presentation.userApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Fix Store target set.
 *
 * This predicate existed twice — once behind the action, once behind the badge that counts what the
 * action would do — and the copies disagreed. Testing it as one pure function is the point of
 * having extracted it: the badge and the picker cannot drift again without one of these failing.
 */
class FixStoreCandidatesTest {

    private val thor = "com.valhalla.thor"

    private fun candidateNames(vararg apps: AppInfo) =
        fixStoreCandidates(apps.toList(), thor).map { it.packageName }

    @Test
    fun `an app Play installed is left alone`() {
        assertTrue(
            candidateNames(
                userApp("com.play.app", installerPackageName = Installers.PLAY_STORE)
            ).isEmpty()
        )
    }

    @Test
    fun `an app with no recorded installer is a candidate`() {
        // The case the feature exists for: Android records nothing for an `adb install` or a
        // restored backup, and Play then declines to update it.
        assertEquals(
            listOf("com.sideloaded"),
            candidateNames(userApp("com.sideloaded", installerPackageName = null))
        )
    }

    @Test
    fun `an app another store installed is a candidate`() {
        assertEquals(
            listOf("com.fdroid.app"),
            candidateNames(userApp("com.fdroid.app", installerPackageName = Installers.F_DROID))
        )
    }

    @Test
    fun `both package-installer ids are excluded, not just Google's`() {
        // The bug this pins: the old predicate named Google's installer only, so on a de-Googled
        // ROM — where every sideload records AOSP's — Fix Store offered to "fix" the whole device.
        assertTrue(
            candidateNames(
                userApp("com.a", installerPackageName = Installers.GOOGLE_PACKAGE_INSTALLER),
                userApp("com.b", installerPackageName = Installers.AOSP_PACKAGE_INSTALLER)
            ).isEmpty()
        )
    }

    @Test
    fun `Thor is never a candidate`() {
        // Every gateway refuses to reinstall Thor by name, so including it would spend a run on a
        // failure that was certain before it started.
        assertTrue(candidateNames(userApp(thor, installerPackageName = null)).isEmpty())
    }

    @Test
    fun `the running package id is what excludes Thor, not the id in the source`() {
        // The debug build applies an `applicationIdSuffix`, so the package that is actually
        // installed is not the one any constant spells. Passing the id in is what keeps this right.
        val debug = "com.valhalla.thor.debug"

        assertTrue(
            fixStoreCandidates(listOf(userApp(debug, installerPackageName = null)), debug).isEmpty()
        )
    }

    @Test
    fun `order is preserved so the picker can sort it itself`() {
        assertEquals(
            listOf("com.c", "com.a", "com.b"),
            candidateNames(
                userApp("com.c", installerPackageName = null),
                userApp("com.play", installerPackageName = Installers.PLAY_STORE),
                userApp("com.a", installerPackageName = "com.aurora.store"),
                userApp("com.b", installerPackageName = Installers.F_DROID)
            )
        )
    }
}
