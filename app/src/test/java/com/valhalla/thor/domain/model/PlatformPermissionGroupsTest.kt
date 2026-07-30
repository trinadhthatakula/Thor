// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The permission -> group table the permission filter is built on.
 *
 * A hardcoded table is only worth having if it is *right*, and none of it is checked by the
 * compiler: every key and value is a string. A typo in `android.permision.CAMERA` costs no build
 * error, no crash and no log line — the Camera chip is simply missing on the one device where
 * somebody notices. These assertions are the type system this file does not get.
 *
 * Pure Kotlin on purpose. The table exists precisely because the device cannot be asked, so a test
 * that asked a device would be testing the wrong thing.
 */
class PlatformPermissionGroupsTest {

    private val declaredGroups = setOf(
        PlatformPermissionGroups.CONTACTS,
        PlatformPermissionGroups.CALENDAR,
        PlatformPermissionGroups.SMS,
        PlatformPermissionGroups.STORAGE,
        PlatformPermissionGroups.READ_MEDIA_VISUAL,
        PlatformPermissionGroups.READ_MEDIA_AURAL,
        PlatformPermissionGroups.LOCATION,
        PlatformPermissionGroups.CALL_LOG,
        PlatformPermissionGroups.PHONE,
        PlatformPermissionGroups.MICROPHONE,
        PlatformPermissionGroups.ACTIVITY_RECOGNITION,
        PlatformPermissionGroups.CAMERA,
        PlatformPermissionGroups.SENSORS,
        PlatformPermissionGroups.NEARBY_DEVICES,
        PlatformPermissionGroups.NOTIFICATIONS
    )

    @Test
    fun everyKeyIsAFullyQualifiedPlatformPermission() {
        val malformed = PlatformPermissionGroups.knownPermissions
            .filterNot { it.startsWith("android.permission.") }
        assertEquals(
            "keys must be the exact strings PackageInfo.requestedPermissions hands back",
            emptyList<String>(),
            malformed
        )
    }

    @Test
    fun everyValueIsOneOfTheDeclaredGroups() {
        val unknown = PlatformPermissionGroups.knownGroups - declaredGroups
        assertEquals(
            "a group produced by the table but not declared as a constant is a typo",
            emptySet<String>(),
            unknown
        )
    }

    @Test
    fun everyDeclaredGroupHasAtLeastOnePermission() {
        // A group nothing maps to can never appear as a chip, so it is dead weight that reads as
        // coverage.
        val empty = declaredGroups - PlatformPermissionGroups.knownGroups
        assertEquals(emptySet<String>(), empty)
    }

    @Test
    fun undefinedIsNeverAGroupTheTableProduces() {
        // The entire reason this file exists: the platform answers UNDEFINED for all of these, and
        // a table that repeated that answer would be an elaborate way to change nothing.
        assertTrue(
            PlatformPermissionGroups.UNDEFINED !in PlatformPermissionGroups.knownGroups
        )
    }

    @Test
    fun theHeadlinePermissionsResolveToTheGroupsUsersLookFor() {
        // These six are the filter, in practice. Each one is a chip a user goes looking for by name.
        assertEquals(
            PlatformPermissionGroups.CAMERA,
            PlatformPermissionGroups.groupOf("android.permission.CAMERA")
        )
        assertEquals(
            PlatformPermissionGroups.MICROPHONE,
            PlatformPermissionGroups.groupOf("android.permission.RECORD_AUDIO")
        )
        assertEquals(
            PlatformPermissionGroups.LOCATION,
            PlatformPermissionGroups.groupOf("android.permission.ACCESS_FINE_LOCATION")
        )
        assertEquals(
            PlatformPermissionGroups.CONTACTS,
            PlatformPermissionGroups.groupOf("android.permission.READ_CONTACTS")
        )
        assertEquals(
            PlatformPermissionGroups.SMS,
            PlatformPermissionGroups.groupOf("android.permission.READ_SMS")
        )
        assertEquals(
            PlatformPermissionGroups.STORAGE,
            PlatformPermissionGroups.groupOf("android.permission.READ_EXTERNAL_STORAGE")
        )
    }

    @Test
    fun theTieredLocationAndPhonePermissionsShareTheirGroup() {
        // Coarse, fine and background location are three permissions and one question. If they
        // landed in different groups the Location chip would answer only part of it.
        val location = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        ).map { PlatformPermissionGroups.groupOf(it) }
        assertEquals(setOf(PlatformPermissionGroups.LOCATION), location.toSet())

        assertEquals(
            PlatformPermissionGroups.PHONE,
            PlatformPermissionGroups.groupOf("android.permission.READ_PHONE_NUMBERS")
        )
        assertEquals(
            PlatformPermissionGroups.CALL_LOG,
            PlatformPermissionGroups.groupOf("android.permission.READ_CALL_LOG")
        )
    }

    @Test
    fun granularMediaPermissionsAreSplitTheWayTheSystemPromptsForThem() {
        // Tiramisu split STORAGE in two, and the platform prompts for photos/video separately from
        // audio. The chips follow the prompts, not the old single bucket.
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_VISUAL,
            PlatformPermissionGroups.groupOf("android.permission.READ_MEDIA_IMAGES")
        )
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_VISUAL,
            PlatformPermissionGroups.groupOf("android.permission.READ_MEDIA_VIDEO")
        )
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_AURAL,
            PlatformPermissionGroups.groupOf("android.permission.READ_MEDIA_AUDIO")
        )
        // Deliberately STORAGE, not READ_MEDIA_VISUAL: an app asking for it has always also asked
        // for a storage or media read, so this is the placement that double-counts nothing.
        assertEquals(
            PlatformPermissionGroups.STORAGE,
            PlatformPermissionGroups.groupOf("android.permission.ACCESS_MEDIA_LOCATION")
        )
    }

    @Test
    fun aNonRuntimePlatformPermissionIsNotInTheTable() {
        // INTERNET matching 400 apps is not a filter. The table only carries permissions the system
        // itself puts behind a prompt; everything else must fall through to PermissionInfo, which
        // will correctly refuse it for not being dangerous.
        assertNull(PlatformPermissionGroups.groupOf("android.permission.INTERNET"))
        assertNull(PlatformPermissionGroups.groupOf("android.permission.WAKE_LOCK"))
        assertNull(PlatformPermissionGroups.groupOf("android.permission.VIBRATE"))
    }

    @Test
    fun aCustomPermissionFallsThroughRatherThanBeingGuessed() {
        // Null means "ask the platform", not "no group" — an app's own permission is the one case
        // where PermissionInfo.group is still honest, and the repository relies on getting null
        // here to go and read it.
        assertNull(PlatformPermissionGroups.groupOf("com.example.app.permission.C2D_MESSAGE"))
        assertNull(PlatformPermissionGroups.groupOf("moe.shizuku.manager.permission.API_V23"))
        assertNull(PlatformPermissionGroups.groupOf(""))
    }

    @Test
    fun groupNamesCarryThePrefixThatMakesThemResolvable() {
        // The labels come from PackageManager.getPermissionGroupInfo, so a group name that is not
        // the platform's own string silently loses its localised label and shows a derived one.
        val unprefixed = PlatformPermissionGroups.knownGroups
            .filterNot { it.startsWith(PlatformPermissionGroups.PREFIX) }
        assertEquals(emptyList<String>(), unprefixed)
    }

    @Test
    fun theTableStillCoversTheSetItWasWrittenAgainst() {
        // A pin, not a limit. Adding a permission is expected and this number should move with it —
        // but a silent *drop* (a rebase that eats a line, a duplicate key overwriting an earlier
        // one) is exactly the change that produces a chip quietly missing some apps.
        assertEquals(42, PlatformPermissionGroups.knownPermissions.size)
        assertEquals(15, PlatformPermissionGroups.knownGroups.size)
    }

    @Test
    fun knownPermissionsAndGroupOfAgree() {
        // knownPermissions is exposed for tests; if it ever stops being the same map groupOf reads,
        // every assertion above becomes vacuous.
        PlatformPermissionGroups.knownPermissions.forEach { permission ->
            assertNotNull(
                "$permission is in knownPermissions but groupOf() does not resolve it",
                PlatformPermissionGroups.groupOf(permission)
            )
        }
    }
}
