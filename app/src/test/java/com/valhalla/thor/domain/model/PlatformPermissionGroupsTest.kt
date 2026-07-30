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

    /** Thor's minSdk — the oldest device every one of these answers has to be right on. */
    private val oldest = 28

    /** Android 13, where the platform re-homed `ACCESS_MEDIA_LOCATION`. */
    private val tiramisu = 33

    /** A device new enough to define everything the table carries. */
    private val latest = 36

    /**
     * Most of the table has had one answer for as long as it has existed, so most assertions here
     * are about a *permission* rather than a device and say so by leaving the level defaulted. The
     * ones that are genuinely about a version boundary pass it explicitly.
     */
    private fun groupOf(permission: String, sdkInt: Int = latest) =
        PlatformPermissionGroups.groupOf(permission, sdkInt)

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
        // ADD_VOICEMAIL is the platform's own exception and has to be listed as one: its constant
        // value is "com.android.voicemail.permission.ADD_VOICEMAIL", so the android.permission.*
        // spelling this table used to carry looked right and matched nothing.
        val knownExceptions = setOf("com.android.voicemail.permission.ADD_VOICEMAIL")
        val malformed = PlatformPermissionGroups.knownPermissions
            .filterNot { it.startsWith("android.permission.") || it in knownExceptions }
        assertEquals(
            "keys must be the exact strings PackageInfo.requestedPermissions hands back",
            emptyList<String>(),
            malformed
        )
    }

    @Test
    fun addVoicemailIsKeyedOnItsRealConstantValue() {
        // The bug this pins: android.permission.ADD_VOICEMAIL is not a permission any device
        // declares, so the Phone chip silently skipped every voicemail app.
        assertNull(groupOf("android.permission.ADD_VOICEMAIL"))
        assertEquals(
            PlatformPermissionGroups.PHONE,
            groupOf("com.android.voicemail.permission.ADD_VOICEMAIL")
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
        assertEquals(PlatformPermissionGroups.CAMERA, groupOf("android.permission.CAMERA"))
        assertEquals(
            PlatformPermissionGroups.MICROPHONE,
            groupOf("android.permission.RECORD_AUDIO")
        )
        assertEquals(
            PlatformPermissionGroups.LOCATION,
            groupOf("android.permission.ACCESS_FINE_LOCATION")
        )
        assertEquals(
            PlatformPermissionGroups.CONTACTS,
            groupOf("android.permission.READ_CONTACTS")
        )
        assertEquals(PlatformPermissionGroups.SMS, groupOf("android.permission.READ_SMS"))
        assertEquals(
            PlatformPermissionGroups.STORAGE,
            groupOf("android.permission.READ_EXTERNAL_STORAGE")
        )
    }

    @Test
    fun theHeadlinePermissionsAnswerTheSameOnTheOldestSupportedDevice() {
        // Everything above, on Thor's minSdk. Only the entries in `regrouped` may vary by API level;
        // if a plain one ever starts to, the version parameter has leaked somewhere it does not
        // belong and the chip a user sees depends on their OS for no reason.
        listOf(
            "android.permission.CAMERA" to PlatformPermissionGroups.CAMERA,
            "android.permission.RECORD_AUDIO" to PlatformPermissionGroups.MICROPHONE,
            "android.permission.ACCESS_FINE_LOCATION" to PlatformPermissionGroups.LOCATION,
            "android.permission.READ_CONTACTS" to PlatformPermissionGroups.CONTACTS,
            "android.permission.READ_SMS" to PlatformPermissionGroups.SMS,
            "android.permission.READ_EXTERNAL_STORAGE" to PlatformPermissionGroups.STORAGE
        ).forEach { (permission, group) ->
            assertEquals(permission, group, groupOf(permission, oldest))
        }
    }

    @Test
    fun theTieredLocationAndPhonePermissionsShareTheirGroup() {
        // Coarse, fine and background location are three permissions and one question. If they
        // landed in different groups the Location chip would answer only part of it.
        val location = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        ).map { groupOf(it) }
        assertEquals(setOf(PlatformPermissionGroups.LOCATION), location.toSet())

        assertEquals(
            PlatformPermissionGroups.PHONE,
            groupOf("android.permission.READ_PHONE_NUMBERS")
        )
        assertEquals(
            PlatformPermissionGroups.CALL_LOG,
            groupOf("android.permission.READ_CALL_LOG")
        )
    }

    @Test
    fun everyNearbyDevicePermissionSharesOneChip() {
        // Same argument as location: Bluetooth, UWB, Wi-Fi and the generic ranging permission are
        // several permissions and one question the user asks ("what can find things around me?").
        // RANGING is the Android 16 addition — PermissionMapping.kt puts it here, and without a row
        // it fell through to PermissionInfo.group, which reads UNDEFINED for platform permissions,
        // so an app holding only RANGING left the Nearby devices chip altogether.
        val nearby = listOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.UWB_RANGING",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.RANGING"
        ).map { groupOf(it) }
        assertEquals(setOf(PlatformPermissionGroups.NEARBY_DEVICES), nearby.toSet())
    }

    @Test
    fun granularMediaPermissionsAreSplitTheWayTheSystemPromptsForThem() {
        // Tiramisu split STORAGE in two, and the platform prompts for photos/video separately from
        // audio. The chips follow the prompts, not the old single bucket.
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_VISUAL,
            groupOf("android.permission.READ_MEDIA_IMAGES")
        )
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_VISUAL,
            groupOf("android.permission.READ_MEDIA_VIDEO")
        )
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_AURAL,
            groupOf("android.permission.READ_MEDIA_AUDIO")
        )
    }

    @Test
    fun accessMediaLocationMovesToTheMediaChipWhereThePlatformMovesIt() {
        // The one permission whose group is a property of the device. Tiramisu split STORAGE and
        // PermissionController re-homed this with it, so on 33+ a photo app requesting
        // READ_MEDIA_IMAGES + ACCESS_MEDIA_LOCATION belongs entirely to Photos and videos — pinning
        // it to STORAGE put it in a chip the system itself would never show it under. Below 33
        // there is no media group to move it to and STORAGE is the honest answer.
        val permission = "android.permission.ACCESS_MEDIA_LOCATION"

        assertEquals(PlatformPermissionGroups.STORAGE, groupOf(permission, oldest))
        assertEquals(PlatformPermissionGroups.STORAGE, groupOf(permission, tiramisu - 1))
        assertEquals(PlatformPermissionGroups.READ_MEDIA_VISUAL, groupOf(permission, tiramisu))
        assertEquals(PlatformPermissionGroups.READ_MEDIA_VISUAL, groupOf(permission, latest))
    }

    @Test
    fun everyRegroupedPermissionAlsoHasABaseRow() {
        // A regrouped entry is an override, not a definition. One without a row in the main table
        // would resolve on new devices and disappear on old ones — the silent-drop failure this
        // file exists to catch, wearing a new hat.
        val orphans =
            PlatformPermissionGroups.regroupedPermissions - PlatformPermissionGroups.knownPermissions
        assertEquals(emptySet<String>(), orphans)
    }

    @Test
    fun aNonRuntimePlatformPermissionIsNotInTheTable() {
        // INTERNET matching 400 apps is not a filter. The table only carries permissions the system
        // itself puts behind a prompt; everything else must fall through to PermissionInfo, which
        // will correctly refuse it for not being dangerous.
        assertNull(groupOf("android.permission.INTERNET"))
        assertNull(groupOf("android.permission.WAKE_LOCK"))
        assertNull(groupOf("android.permission.VIBRATE"))
    }

    @Test
    fun aCustomPermissionFallsThroughRatherThanBeingGuessed() {
        // Null means "ask the platform", not "no group" — an app's own permission is the one case
        // where PermissionInfo.group is still honest, and the repository relies on getting null
        // here to go and read it.
        assertNull(groupOf("com.example.app.permission.C2D_MESSAGE"))
        assertNull(groupOf("moe.shizuku.manager.permission.API_V23"))
        assertNull(groupOf(""))
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
        assertEquals(43, PlatformPermissionGroups.knownPermissions.size)
        assertEquals(15, PlatformPermissionGroups.knownGroups.size)
    }

    @Test
    fun knownPermissionsAndGroupOfAgree() {
        // knownPermissions is exposed for tests; if it ever stops being the same map groupOf reads,
        // every assertion above becomes vacuous. Both ends of the supported range, because an
        // entry that only resolves on one of them is an entry that drops apps on the other.
        listOf(oldest, latest).forEach { sdkInt ->
            PlatformPermissionGroups.knownPermissions.forEach { permission ->
                assertNotNull(
                    "$permission is in knownPermissions but groupOf() does not resolve it on $sdkInt",
                    groupOf(permission, sdkInt)
                )
            }
        }
    }
}
