// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.Installers
import com.valhalla.thor.presentation.userApp
import com.valhalla.thor.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Home chart's bucketing.
 *
 * Testable on the JVM because [installerDistribution] takes its label lookup as a parameter rather
 * than reaching for a `PackageManager`, and because `R.string` constants are plain ints — nothing
 * here resolves a resource, it only checks which one a bar points at.
 */
class InstallerDistributionTest {

    private val noLabels: (String) -> String? = { null }

    private fun apps(vararg installers: String?) =
        installers.mapIndexed { index, installer ->
            userApp(packageName = "app.$index", installerPackageName = installer)
        }

    private fun List<InstallerSlice>.countFor(installer: String?) =
        firstOrNull { it.installerPackageName == installer }?.count

    @Test
    fun `an empty list produces no bars`() {
        assertEquals(emptyList<InstallerSlice>(), installerDistribution(emptyList(), noLabels))
    }

    @Test
    fun `apps are counted against the installer that put them there`() {
        val slices = installerDistribution(
            apps(Installers.PLAY_STORE, Installers.PLAY_STORE, Installers.F_DROID),
            noLabels
        )

        assertEquals(2, slices.countFor(Installers.PLAY_STORE))
        assertEquals(1, slices.countFor(Installers.F_DROID))
    }

    @Test
    fun `the installers Thor knows are named from resources, not from the device`() {
        val slices = installerDistribution(
            apps(Installers.PLAY_STORE, Installers.F_DROID, Installers.GOOGLE_PACKAGE_INSTALLER),
            // A device that would name them something else entirely. The curated three ignore it.
            labelFor = { "Nonsense" }
        )

        assertEquals(
            UiText.StringResource(R.string.play_store),
            slices.first { it.installerPackageName == Installers.PLAY_STORE }.label
        )
        assertEquals(
            UiText.StringResource(R.string.f_droid),
            slices.first { it.installerPackageName == Installers.F_DROID }.label
        )
        assertEquals(
            UiText.StringResource(R.string.sideloaded),
            slices.first { it.installerPackageName == Installers.GOOGLE_PACKAGE_INSTALLER }.label
        )
    }

    @Test
    fun `both package-installer ids read as sideloaded`() {
        val slices = installerDistribution(
            apps(Installers.GOOGLE_PACKAGE_INSTALLER, Installers.AOSP_PACKAGE_INSTALLER),
            noLabels
        )

        assertTrue(
            slices.all { it.label == UiText.StringResource(R.string.sideloaded) }
        )
    }

    @Test
    fun `an installer Thor does not know is named by the device`() {
        val slices = installerDistribution(
            apps("com.aurora.store"),
            labelFor = { if (it == "com.aurora.store") "Aurora Store" else null }
        )

        assertEquals(UiText.DynamicString("Aurora Store"), slices.single().label)
    }

    @Test
    fun `an installer no longer on the device falls back to its package id`() {
        // The store was uninstalled; the apps it placed still name it. Better a package id than a
        // blank bar — the id is what the Apps tab filter shows too, so the two agree.
        val slices = installerDistribution(apps("com.aurora.store"), noLabels)

        assertEquals(UiText.DynamicString("com.aurora.store"), slices.single().label)
    }

    @Test
    fun `installers are told apart by package id, not by the tail of their name`() {
        // The label-keyed version this replaced bucketed on `substringAfterLast(".").uppercase()`,
        // which drew both of these as one bar reading "STORE".
        val slices = installerDistribution(apps("com.aurora.store", "com.other.store"), noLabels)

        assertEquals(2, slices.size)
        assertEquals(1, slices.countFor("com.aurora.store"))
        assertEquals(1, slices.countFor("com.other.store"))
    }

    @Test
    fun `four installers each keep their own bar`() {
        val slices = installerDistribution(
            apps(Installers.PLAY_STORE, Installers.F_DROID, "a.store", "b.store"),
            noLabels
        )

        assertEquals(4, slices.size)
        assertNull(slices.firstOrNull { it.installerPackageName == null })
    }

    @Test
    fun `past four installers the tail collapses into one Others bar`() {
        val slices = installerDistribution(
            // Play Store leads with 3; the other four have one app each.
            apps(
                Installers.PLAY_STORE, Installers.PLAY_STORE, Installers.PLAY_STORE,
                Installers.F_DROID, Installers.F_DROID,
                "a.store", "b.store", "c.store"
            ),
            noLabels
        )

        assertEquals(4, slices.size)
        assertEquals(3, slices.countFor(Installers.PLAY_STORE))
        assertEquals(2, slices.countFor(Installers.F_DROID))
        // a, b and c each had one app, and the third named bar went to whichever of them ranked
        // first — so Others holds the remaining two.
        val others = slices.single { it.installerPackageName == null }
        assertEquals(UiText.StringResource(R.string.others), others.label)
        assertEquals(2, others.count)
    }

    @Test
    fun `every app is accounted for exactly once`() {
        val all = apps(
            Installers.PLAY_STORE, Installers.PLAY_STORE, Installers.F_DROID,
            "a.store", "b.store", "c.store", "d.store", null, "Unknown"
        )

        assertEquals(all.size, installerDistribution(all, noLabels).sumOf { it.count })
    }

    @Test
    fun `a null installer and the Unknown placeholder land in the same bar`() {
        // Two layers reporting the same fact. Bucketing them apart would draw the chart's one
        // unnameable category twice, with no way to tell the halves apart.
        val slices = installerDistribution(apps(null, Installers.UNKNOWN), noLabels)

        val others = slices.single()
        assertNull(others.installerPackageName)
        assertEquals(2, others.count)
    }

    @Test
    fun `apps with no recorded installer merge into Others rather than forming a second bar`() {
        val slices = installerDistribution(
            apps(
                Installers.PLAY_STORE, Installers.PLAY_STORE, Installers.PLAY_STORE,
                Installers.F_DROID, Installers.F_DROID,
                null, null,
                "a.store", "b.store"
            ),
            noLabels
        )

        assertEquals(1, slices.count { it.installerPackageName == null })
        // The unrecorded pair outranks a and b, so it takes the third slot — and then folds into
        // Others anyway, taking both of them with it. One unnameable bar, never two.
        assertEquals(4, slices.single { it.installerPackageName == null }.count)
    }

    @Test
    fun `no Others bar is drawn when nothing is left over`() {
        val slices = installerDistribution(apps(Installers.PLAY_STORE), noLabels)

        assertEquals(1, slices.size)
        assertEquals(Installers.PLAY_STORE, slices.single().installerPackageName)
    }
}
