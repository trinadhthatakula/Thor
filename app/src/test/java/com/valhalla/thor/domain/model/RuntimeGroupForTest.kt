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

    @Test
    fun aPermissionThisDeviceDoesNotDefineIsNotGrouped() {
        // The headline case: an app declaring POST_NOTIFICATIONS on API 28. getPermissionInfo throws
        // NameNotFoundException there, which arrives here as null, and the table must not be
        // consulted — knowing which group it *would* belong to on API 33 is not knowledge that the
        // permission exists.
        assertNull(runtimeGroupFor(postNotifications, declared = null))
        assertNull(runtimeGroupFor(readMediaImages, declared = null))
        assertNull(runtimeGroupFor(camera, declared = null))
    }

    @Test
    fun aFuturePermissionIsGroupedOnceTheDeviceActuallyDefinesIt() {
        // The same two permissions on an OS that does define them. Nothing about the table changed;
        // the device's answer did. This is the pair that makes the test above about *ordering*
        // rather than about POST_NOTIFICATIONS being special.
        assertEquals(
            PlatformPermissionGroups.NOTIFICATIONS,
            runtimeGroupFor(postNotifications, dangerous())
        )
        assertEquals(
            PlatformPermissionGroups.READ_MEDIA_VISUAL,
            runtimeGroupFor(readMediaImages, dangerous())
        )
    }

    @Test
    fun aPermissionThatIsNotDangerousIsNotGrouped() {
        // INTERNET is defined on every device and matches nearly every app. Refusing it is what
        // keeps the chips a filter rather than a list of everything installed.
        assertNull(
            runtimeGroupFor(
                "android.permission.INTERNET",
                DeclaredPermission(isDangerous = false, group = null)
            )
        )
        // Even a non-dangerous permission carrying a real group name stays out: the user is never
        // prompted for it, so grouping it would put apps under a chip that implies a granted
        // capability nobody granted.
        assertNull(
            runtimeGroupFor(
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
            runtimeGroupFor(camera, dangerous(group = PlatformPermissionGroups.UNDEFINED))
        )
        // And it wins even if some OEM build reports something else — one permission, one chip,
        // decided in one place rather than varying by ROM.
        assertEquals(
            PlatformPermissionGroups.CAMERA,
            runtimeGroupFor(camera, dangerous(group = "com.oem.permission-group.HARDWARE"))
        )
    }

    @Test
    fun aCustomDangerousPermissionKeepsItsOwnGroup() {
        // The one case where PermissionInfo.group is still honest: an app declaring its own
        // dangerous permission also declares the group, and the platform hands it back verbatim.
        assertEquals(
            "com.example.permission-group.HEALTH",
            runtimeGroupFor(
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
        assertNull(runtimeGroupFor("com.example.app.permission.ODD", dangerous(group = null)))
        assertNull(runtimeGroupFor("com.example.app.permission.ODD", dangerous(group = "")))
        assertNull(runtimeGroupFor("com.example.app.permission.ODD", dangerous(group = "   ")))
        assertNull(
            runtimeGroupFor(
                "com.example.app.permission.ODD",
                dangerous(group = PlatformPermissionGroups.UNDEFINED)
            )
        )
    }

    @Test
    fun everyPermissionInTheTableResolvesWhenTheDeviceDefinesIt() {
        // The table's entries are only reachable through this function, so a key that the ordering
        // rule can never reach is dead. Pairs with knownPermissionsAndGroupOfAgree, which pins the
        // table itself.
        PlatformPermissionGroups.knownPermissions.forEach { permission ->
            assertEquals(
                "$permission is in the table but runtimeGroupFor() drops it",
                PlatformPermissionGroups.groupOf(permission),
                runtimeGroupFor(permission, dangerous())
            )
        }
    }

    private fun dangerous(group: String? = PlatformPermissionGroups.UNDEFINED) =
        DeclaredPermission(isDangerous = true, group = group)
}
