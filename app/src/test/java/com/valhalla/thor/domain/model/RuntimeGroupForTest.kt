// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that decides which chip a declared permission counts towards.
 *
 * The interesting cases are all about *the device disagreeing with the manifest*.
 * `PackageInfo.requestedPermissions` is a list of declarations, not capabilities: an APK built
 * against API 33 and running on API 28 still declares `POST_NOTIFICATIONS`, and the OS underneath it
 * has never heard of that permission. Thor's minSdk is 28, so this is an ordinary device, not a
 * corner case — and a filter that trusted the manifest would offer a Notifications chip listing apps
 * that hold no notification permission because there is no such permission to hold.
 *
 * [runtimeGroupFor] is a pure function for exactly this reason. `PackageManager` is abstract, `:app`
 * has no mocking library, and `PermissionRepositoryImpl` cannot be built on a JVM — so the binder
 * call stays in the repository and the decision it feeds lives here, where an "API 28 device" is
 * just `declared = null`.
 */
class RuntimeGroupForTest {

    private val camera = "android.permission.CAMERA"
    private val postNotifications = "android.permission.POST_NOTIFICATIONS"
    private val readMediaImages = "android.permission.READ_MEDIA_IMAGES"
    private val accessMediaLocation = "android.permission.ACCESS_MEDIA_LOCATION"
    private val ranging = "android.permission.RANGING"

    /** Thor's minSdk. */
    private val oldest = 28

    /** Android 13, where `ACCESS_MEDIA_LOCATION` changes group. */
    private val tiramisu = 33

    /** A device new enough to define everything the table carries. */
    private val latest = 36

    /**
     * The rule under test, on a modern device unless the case is about a version boundary. Most of
     * these are about the *device disagreeing with the manifest*, which `declared` already says;
     * only [accessMediaLocation] cares about the level, and it passes one explicitly.
     */
    private fun groupFor(
        permission: String,
        declared: DeclaredPermission?,
        sdkInt: Int = latest
    ) = runtimeGroupFor(permission, declared, sdkInt)

    @Test
    fun aPermissionThisDeviceDoesNotDefineIsNotGrouped() {
        // The headline case: an app declaring POST_NOTIFICATIONS on API 28. getPermissionInfo throws
        // NameNotFoundException there, which arrives here as null, and the table must not be
        // consulted — knowing which group it *would* belong to on API 33 is not knowledge that the
        // permission exists.
        assertNull(groupFor(postNotifications, declared = null))
        assertNull(groupFor(readMediaImages, declared = null))
        assertNull(groupFor(camera, declared = null))
    }

    @Test
    fun aFuturePermissionIsGroupedOnceTheDeviceActuallyDefinesIt() {
        // The same two permissions on an OS that does define them. Nothing about the table changed;
        // the device's answer did. This is the pair that makes the test above about *ordering*
        // rather than about POST_NOTIFICATIONS being special.
        assertEquals(
            PlatformPermissionGroups.NOTIFICATIONS,
            groupFor(postNotifications, dangerous())
        )
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_VISUAL,
            groupFor(readMediaImages, dangerous())
        )
    }

    @Test
    fun aPermissionThatIsNotDangerousIsNotGrouped() {
        // INTERNET is defined on every device and matches nearly every app. Refusing it is what
        // keeps the chips a filter rather than a list of everything installed.
        assertNull(
            groupFor(
                "android.permission.INTERNET",
                DeclaredPermission(isDangerous = false, group = null)
            )
        )
        // Even a non-dangerous permission carrying a real group name stays out: the user is never
        // prompted for it, so grouping it would put apps under a chip that implies a granted
        // capability nobody granted.
        assertNull(
            groupFor(
                "android.permission.BLUETOOTH",
                DeclaredPermission(isDangerous = false, group = PlatformPermissionGroups.NEARBY_DEVICES)
            )
        )
    }

    @Test
    fun theTableOverridesWhateverTheDeviceSaysTheGroupIs() {
        // Since Android 10 the platform answers UNDEFINED for every dangerous platform permission,
        // so on a real device this is the *only* branch that ever produces a Camera chip.
        assertEquals(
            PlatformPermissionGroups.CAMERA,
            groupFor(camera, dangerous(group = PlatformPermissionGroups.UNDEFINED))
        )
        // And it wins even if some OEM build reports something else — one permission, one chip,
        // decided in one place rather than varying by ROM.
        assertEquals(
            PlatformPermissionGroups.CAMERA,
            groupFor(camera, dangerous(group = "com.oem.permission-group.HARDWARE"))
        )
    }

    @Test
    fun aCustomDangerousPermissionKeepsItsOwnGroup() {
        // The one case where PermissionInfo.group is still honest: an app declaring its own
        // dangerous permission also declares the group, and the platform hands it back verbatim.
        assertEquals(
            "com.example.permission-group.HEALTH",
            groupFor(
                "com.example.app.permission.READ_HEALTH",
                dangerous(group = "com.example.permission-group.HEALTH")
            )
        )
    }

    @Test
    fun aDangerousPermissionWithNoUsableGroupIsLeftOut() {
        // UNDEFINED, blank and null are the three ways the platform says "no group". A chip built
        // from any of them would be either the UNDEFINED bucket — every dangerous permission on the
        // device, i.e. no filter at all — or an unnamed one.
        assertNull(groupFor("com.example.app.permission.ODD", dangerous(group = null)))
        assertNull(groupFor("com.example.app.permission.ODD", dangerous(group = "")))
        assertNull(groupFor("com.example.app.permission.ODD", dangerous(group = "   ")))
        assertNull(
            groupFor(
                "com.example.app.permission.ODD",
                dangerous(group = PlatformPermissionGroups.UNDEFINED)
            )
        )
    }

    @Test
    fun everyPermissionInTheTableResolvesWhenTheDeviceDefinesIt() {
        // The table's entries are only reachable through this function, so a key that the ordering
        // rule can never reach is dead. Pairs with knownPermissionsAndGroupOfAgree, which pins the
        // table itself. Checked at both ends of the supported range so the version parameter cannot
        // quietly strand an entry on one of them.
        listOf(oldest, latest).forEach { sdkInt ->
            PlatformPermissionGroups.knownPermissions.forEach { permission ->
                assertEquals(
                    "$permission is in the table but runtimeGroupFor() drops it on $sdkInt",
                    PlatformPermissionGroups.groupOf(permission, sdkInt),
                    groupFor(permission, dangerous(), sdkInt)
                )
            }
        }
    }

    @Test
    fun accessMediaLocationIsGroupedWhereThisDeviceWouldPromptForIt() {
        // The device defines it either way — this is not about existence, it is about which of two
        // real groups it belongs to, and that genuinely changed at Tiramisu. Same declaration, same
        // table, different answer, because the user saw a different prompt.
        assertEquals(
            PlatformPermissionGroups.STORAGE,
            groupFor(accessMediaLocation, dangerous(), oldest)
        )
        assertEquals(
            PlatformPermissionGroups.STORAGE,
            groupFor(accessMediaLocation, dangerous(), tiramisu - 1)
        )
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_VISUAL,
            groupFor(accessMediaLocation, dangerous(), tiramisu)
        )
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_VISUAL,
            groupFor(accessMediaLocation, dangerous(), latest)
        )
    }

    @Test
    fun rangingCountsTowardsNearbyDevicesOnceTheDeviceDeclaresItDangerous() {
        // Android 16's RANGING is guarded twice over, and neither guard is an API-level check here.
        // On a device that has never heard of it, getPermissionInfo throws and `declared` is null.
        // On one that defines it with `Flags.rangingStackEnabled()` off it is not dangerous. Only
        // when the device says both does it reach the table — which is why adding the row needs no
        // version condition of its own.
        assertNull(groupFor(ranging, declared = null, sdkInt = oldest))
        assertNull(groupFor(ranging, declared = null, sdkInt = latest))
        assertNull(
            groupFor(ranging, DeclaredPermission(isDangerous = false, group = null), latest)
        )
        assertEquals(
            PlatformPermissionGroups.NEARBY_DEVICES,
            groupFor(ranging, dangerous(), latest)
        )
    }

    private fun dangerous(group: String? = PlatformPermissionGroups.UNDEFINED) =
        DeclaredPermission(isDangerous = true, group = group)
}
