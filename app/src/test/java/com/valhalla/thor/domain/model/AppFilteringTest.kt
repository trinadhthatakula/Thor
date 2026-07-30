// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-list filter rules.
 *
 * These lived inside a private method on a ViewModel with eleven constructor dependencies until the
 * permission filter arrived, so none of them had ever been asserted — including the ones that were
 * already shipping. `filterApps` exists to make them reachable; this covers all three branches, not
 * just the new one.
 */
class AppFilteringTest {

    private val camera = "android.permission-group.CAMERA"
    private val mic = "android.permission-group.MICROPHONE"

    private fun app(
        pkg: String,
        installer: String? = null,
        enabled: Boolean = true,
        suspended: Boolean = false
    ) = AppInfo(
        packageName = pkg,
        installerPackageName = installer,
        enabled = enabled,
        isSuspended = suspended
    )

    private fun pkgs(apps: List<AppInfo>) = apps.map { it.packageName }

    // --- "All" ---

    @Test
    fun all_passesEverythingThroughForEveryFilterType() {
        val apps = listOf(app("a", installer = "com.android.vending"), app("b", enabled = false))

        for (type in filterTypes) {
            assertEquals(
                "filter type $type dropped apps on the All chip",
                apps,
                filterApps(apps, type, ALL_FILTER)
            )
        }
    }

    @Test
    fun all_shortCircuitsBeforeTheIndexIsConsulted() {
        val apps = listOf(app("a"), app("b"))

        // The index is empty, which for any real group would match nothing. "All" must not care —
        // otherwise selecting the Permission filter shows an empty list until the sweep finishes.
        assertEquals(apps, filterApps(apps, FilterType.Permission, ALL_FILTER, PermissionIndex()))
    }

    // --- Source ---

    @Test
    fun source_matchesTheInstallerExactly() {
        val apps = listOf(
            app("play", installer = "com.android.vending"),
            app("fdroid", installer = "org.fdroid.fdroid"),
            app("unknown", installer = null)
        )

        val filtered = filterApps(apps, FilterType.Source, "com.android.vending")

        assertEquals(listOf("play"), pkgs(filtered))
    }

    @Test
    fun source_neverMatchesAnAppWithNoInstaller() {
        // installerPackageName is null for a great many system apps. A filter that compared loosely
        // would sweep all of them into whichever installer the user tapped.
        val apps = listOf(app("nullInstaller", installer = null))

        assertTrue(filterApps(apps, FilterType.Source, "com.android.vending").isEmpty())
    }

    // --- State ---

    @Test
    fun state_frozenMeansDisabled_notSuspended() {
        val apps = listOf(
            app("active"),
            app("frozen", enabled = false),
            app("suspended", suspended = true)
        )

        // A suspended app is still `enabled`, so it belongs under Suspended and nowhere else.
        // These two states are independent and an app can be in both.
        assertEquals(listOf("active", "suspended"), pkgs(filterApps(apps, FilterType.State, "Active")))
        assertEquals(listOf("frozen"), pkgs(filterApps(apps, FilterType.State, "Frozen")))
        assertEquals(listOf("suspended"), pkgs(filterApps(apps, FilterType.State, "Suspended")))
    }

    @Test
    fun state_anUnknownChipShowsEverythingRatherThanNothing() {
        val apps = listOf(app("a"), app("b"))

        // selectedFilter is persisted in DataStore, so a chip that disappears in a later release
        // comes back from disk on first launch. Failing to an empty list would read as "all your
        // apps are gone".
        assertEquals(apps, filterApps(apps, FilterType.State, "Retired"))
    }

    // --- Permission ---

    @Test
    fun permission_keepsOnlyThePackagesDeclaringThatGroup() {
        val apps = listOf(app("cam"), app("mic"), app("neither"))
        val index = PermissionIndex(
            packagesByGroup = mapOf(camera to setOf("cam"), mic to setOf("mic")),
            groupLabels = mapOf(camera to "Camera", mic to "Microphone")
        )

        assertEquals(listOf("cam"), pkgs(filterApps(apps, FilterType.Permission, camera, index)))
        assertEquals(listOf("mic"), pkgs(filterApps(apps, FilterType.Permission, mic, index)))
    }

    @Test
    fun permission_anIndexEntryForAnUninstalledPackageDoesNotInventAnApp() {
        // The index is built from PackageManager and the list from Thor's own cache, so they can
        // disagree for a moment after an uninstall. The list is the authority for what exists.
        val apps = listOf(app("cam"))
        val index = PermissionIndex(packagesByGroup = mapOf(camera to setOf("cam", "gone")))

        assertEquals(listOf("cam"), pkgs(filterApps(apps, FilterType.Permission, camera, index)))
    }

    @Test
    fun permission_anEmptyIndexMatchesNothing() {
        val apps = listOf(app("cam"), app("mic"))

        // While the sweep is still running. Matching everything here would show the full list under
        // a Camera chip and then shrink under the user, which reads as a glitch.
        assertTrue(filterApps(apps, FilterType.Permission, camera, PermissionIndex()).isEmpty())
    }

    @Test
    fun permission_aGroupNotInTheIndexMatchesNothing() {
        val apps = listOf(app("cam"))
        val index = PermissionIndex(packagesByGroup = mapOf(camera to setOf("cam")))

        assertTrue(filterApps(apps, FilterType.Permission, mic, index).isEmpty())
    }

    // --- PermissionIndex itself ---

    @Test
    fun index_ordersGroupsByLocalisedLabel_notByPermissionName() {
        // The chips must read alphabetically to the user, and the user reads the label. Ordering by
        // the group *name* would order by "android.permission-group.…", i.e. by the English
        // constant, which is not alphabetical in any language including English.
        val index = PermissionIndex(
            packagesByGroup = mapOf(camera to setOf("a"), mic to setOf("b"), "g.zzz" to setOf("c")),
            groupLabels = mapOf(camera to "Kamera", mic to "Mikrofon", "g.zzz" to "Adressbuch")
        )

        assertEquals(listOf("g.zzz", camera, mic), index.orderedGroups)
    }

    @Test
    fun index_ordersCaseInsensitively() {
        val index = PermissionIndex(
            packagesByGroup = mapOf("g.a" to setOf("a"), "g.b" to setOf("b")),
            groupLabels = mapOf("g.a" to "camera", "g.b" to "Body sensors")
        )

        assertEquals(listOf("g.b", "g.a"), index.orderedGroups)
    }

    @Test
    fun index_fallsBackToTheGroupNameWhenTheLabelIsMissing() {
        val index = PermissionIndex(
            packagesByGroup = mapOf("g.aaa" to setOf("a"), "g.zzz" to setOf("b")),
            groupLabels = mapOf("g.zzz" to "Zebra")
        )

        // Unlabelled groups still have to sort somewhere deterministic rather than landing in
        // whatever order the HashMap happened to produce.
        assertEquals(listOf("g.aaa", "g.zzz"), index.orderedGroups)
    }

    @Test
    fun index_packagesForIsEmptyForAnUnknownGroup() {
        val index = PermissionIndex(packagesByGroup = mapOf(camera to setOf("a")))

        assertTrue(index.packagesFor("android.permission-group.SMS").isEmpty())
        assertTrue(PermissionIndex().isEmpty)
    }
}
