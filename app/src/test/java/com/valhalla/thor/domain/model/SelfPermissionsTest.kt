// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of Thor's own declared permissions a privileged self-grant may issue `pm grant` for.
 *
 * Every rejection [planSelfGrant] makes is a command that would have *failed*, and each failure mode
 * is a different one, which is why they are pinned separately: an undefined permission answers
 * `Unknown permission`, a non-runtime one throws `not a changeable permission type`, and an
 * already-held one succeeds while changing nothing. All three cost a round trip through the root
 * shell or the Shizuku binder, on a path that runs while the user is waiting for the app list.
 *
 * The device is asked and no name is hardcoded in the production rule, so the interesting cases here
 * are whole devices rather than single permissions: the same Thor manifest yields one grant on a
 * Pixel, two on HyperOS and none on API 28. Pure for the usual reason — `PackageManager` is abstract
 * and `:app` has no mocking library by policy — so "a HyperOS device on Android 13" is nothing more
 * exotic here than a list of [SelfPermission].
 */
class SelfPermissionsTest {

    // Thor's own manifest, by name. Spelled out rather than read from android.Manifest so that a
    // reader can see which of Thor's declarations each case is actually about.
    private val postNotifications = "android.permission.POST_NOTIFICATIONS"
    private val internet = "android.permission.INTERNET"
    private val queryAllPackages = "android.permission.QUERY_ALL_PACKAGES"
    private val packageUsageStats = "android.permission.PACKAGE_USAGE_STATS"
    private val requestInstallPackages = "android.permission.REQUEST_INSTALL_PACKAGES"

    /** Defined by this OS, `dangerous`, and therefore requestable — the only grantable shape. */
    private fun runtime(name: String, granted: Boolean = false) = SelfPermission(
        name = name,
        declaration = SelfPermissionDeclaration.Declared(
            DeclaredPermission(isDangerous = true, group = null)
        ),
        isGranted = granted,
    )

    /** Defined, but at a protection level `pm grant` refuses: normal, signature, appop, privileged. */
    private fun installTime(name: String, granted: Boolean = false) = SelfPermission(
        name = name,
        declaration = SelfPermissionDeclaration.Declared(
            DeclaredPermission(isDangerous = false, group = null)
        ),
        isGranted = granted,
    )

    /** `getPermissionInfo` threw `NameNotFoundException`: this build has never heard of it. */
    private fun undefined(name: String) = SelfPermission(
        name = name,
        declaration = SelfPermissionDeclaration.Undefined,
        isGranted = false,
    )

    /** The package manager would not answer — a failed question, not a verdict about the device. */
    private fun unanswered(name: String) = SelfPermission(
        name = name,
        declaration = SelfPermissionDeclaration.Unknown,
        isGranted = false,
    )

    @Test
    fun onAPixelOnlyPostNotificationsIsGrantable() {
        // The ordinary device, and the case the owner actually asked for. GET_INSTALLED_APPS is a
        // Chinese-market permission no AOSP build defines, and the four AOSP declarations below are
        // all install-time, so exactly one command is worth issuing.
        val plan = planSelfGrant(
            listOf(
                installTime(internet),
                installTime(queryAllPackages),
                undefined(GET_INSTALLED_APPS_PERMISSION),
                installTime(packageUsageStats),
                runtime(postNotifications),
            )
        )
        assertEquals(listOf(postNotifications), plan.toGrant)
        // Nothing was left unclassified, so this run may latch and never sweep again.
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun onAChineseRomBothRuntimePermissionsAreGrantable() {
        // MIUI/HyperOS, ColorOS, OriginOS, MagicOS: GET_INSTALLED_APPS is defined *and* dangerous
        // there, which is the whole reason the rule asks the device instead of carrying a list.
        val plan = planSelfGrant(
            listOf(
                installTime(internet),
                runtime(GET_INSTALLED_APPS_PERMISSION),
                runtime(postNotifications),
            )
        )
        assertEquals(listOf(GET_INSTALLED_APPS_PERMISSION, postNotifications), plan.toGrant)
    }

    @Test
    fun onApi28NothingIsGrantable() {
        // Thor's minSdk. POST_NOTIFICATIONS does not exist until API 33 and GET_INSTALLED_APPS is
        // absent from AOSP entirely, so the manifest declares both and the device defines neither.
        // An empty plan still counts as complete: there is nothing here a retry would find.
        val plan = planSelfGrant(
            listOf(
                installTime(internet),
                undefined(GET_INSTALLED_APPS_PERMISSION),
                undefined(postNotifications),
            )
        )
        assertTrue(plan.toGrant.isEmpty())
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun anUngrantedInstallTimePermissionIsStillSkipped() {
        // Ungranted is not the same as grantable. `grantRuntimePermission` throws
        // SecurityException("... is not a changeable permission type") for anything that is not a
        // runtime permission, and both of these are Thor's real cases: PACKAGE_USAGE_STATS is
        // signature|privileged|appop, whose app-op half UsageAccessManager sets through `appops`,
        // and REQUEST_INSTALL_PACKAGES is an app-op the user toggles under "Install unknown apps".
        val plan = planSelfGrant(
            listOf(
                installTime(packageUsageStats),
                installTime(requestInstallPackages),
            )
        )
        assertTrue(plan.toGrant.isEmpty())
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun anAlreadyHeldRuntimePermissionIsSkipped() {
        // Re-granting succeeds and changes nothing, so it is a privileged round trip bought for no
        // effect — on every privilege state change, since the latch only closes on a complete run.
        val plan = planSelfGrant(listOf(runtime(postNotifications, granted = true)))
        assertTrue(plan.toGrant.isEmpty())
        assertFalse(plan.hasUnanswered)
    }

    @Test
    fun aPermissionTheDeviceWouldNotClassifyKeepsTheRunRepeatable() {
        // The distinction the three-state declaration exists for. Both an undefined permission and an
        // unanswered one are left out of `toGrant`, so the *plans* are indistinguishable — but only
        // the undefined one is an answer. Reporting no unanswered probe here would let the caller
        // latch its once-per-process guard on a package manager that simply hiccupped, disabling the
        // whole feature for the life of the process and calling it success.
        val plan = planSelfGrant(listOf(unanswered(postNotifications)))
        assertTrue(plan.toGrant.isEmpty())
        assertTrue(plan.hasUnanswered)
    }

    @Test
    fun oneUnansweredProbeDoesNotHoldBackTheGrantsThatAreKnown() {
        // Partial knowledge is still worth acting on: the user gets the permission that could be
        // classified now, and the flag brings the sweep back for the one that could not. Failing
        // closed on the whole plan would mean one flaky lookup costs a grant that was ready.
        val plan = planSelfGrant(
            listOf(
                unanswered(GET_INSTALLED_APPS_PERMISSION),
                runtime(postNotifications),
            )
        )
        assertEquals(listOf(postNotifications), plan.toGrant)
        assertTrue(plan.hasUnanswered)
    }

    @Test
    fun theManifestsDeclarationOrderIsPreserved() {
        // The grants are issued in this order, one privileged command at a time, and the notification
        // permission is the one the user is most likely to be waiting on — a plan that reordered
        // could put it behind a permission whose gateway call hangs.
        val plan = planSelfGrant(
            listOf(
                runtime(postNotifications),
                runtime(GET_INSTALLED_APPS_PERMISSION),
            )
        )
        assertEquals(listOf(postNotifications, GET_INSTALLED_APPS_PERMISSION), plan.toGrant)
    }

    @Test
    fun aManifestWithNoPermissionsPlansNothingAndIsComplete() {
        // `requestedPermissions` is nullable on the platform side and arrives here as an empty list.
        // Complete rather than repeatable: there is no question left open.
        val plan = planSelfGrant(emptyList())
        assertTrue(plan.toGrant.isEmpty())
        assertFalse(plan.hasUnanswered)
    }
}
