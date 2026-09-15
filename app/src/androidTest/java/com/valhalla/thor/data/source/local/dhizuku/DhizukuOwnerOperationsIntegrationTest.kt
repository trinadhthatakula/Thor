// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.bypass.Bypass
import com.valhalla.thor.R
import com.valhalla.thor.data.gateway.DhizukuSystemGateway
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.asUiText
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import com.rosan.dhizuku.api.Dhizuku as DhizukuAPI

/**
 * Destructive, opt-in checks on a disposable debuggable fixture requesting CAMERA. Run with
 * dhizukuDestructiveTestPackage=com.valhalla.thor.audit.target and a real Thor Dhizuku grant.
 * All operations under test use the real owner gateway. Shell is used only to prepare/read a
 * marker in this exact fixture's private data. The last test uninstalls the fixture.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DhizukuOwnerOperationsIntegrationTest {
    private lateinit var context: Context
    private lateinit var gateway: DhizukuSystemGateway

    @Before
    fun requireDisposableFixtureAndRealGrant() {
        val requested = InstrumentationRegistry.getArguments()
            .getString("dhizukuDestructiveTestPackage")
        assumeTrue("Explicit destructive-test opt-in required", requested == TARGET)
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertTrue(DhizukuAPI.init(context))
        assertTrue(DhizukuAPI.isPermissionGranted())
        DhizukuHelper.markClientInitialised(true)
        assertFalse(TARGET == context.packageName)
        assertFalse(TARGET == DhizukuAPI.getOwnerPackageName())
        val info = context.packageManager.getApplicationInfo(TARGET, 0)
        assertTrue(info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertFalse(info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertTrue(info.flags and ApplicationInfo.FLAG_INSTALLED != 0)
        assertEquals(android.os.Process.myUid() / 100_000, info.uid / 100_000)
        gateway = DhizukuSystemGateway(
            context, DhizukuReflector(context), PreferenceRepositoryImpl(context), Dispatchers.IO,
        )
    }

    @Test
    fun a_reflectionDenialDoesNotRetryOrCrash() {
        val target = ThrowingTarget()
        val failure = assertThrows(InvocationTargetException::class.java) {
            Bypass.invoke<Unit>(ThrowingTarget::class.java, target, "perform")
        }
        assertSame(target.denial, failure.targetException)
        assertEquals(1, target.calls)
    }

    @Test
    fun b_permissionPolicyRoundTrip() = runBlocking {
        val requested = context.packageManager.getPackageInfo(TARGET, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.CAMERA in requested)

        gateway.grantPermission(TARGET, Manifest.permission.CAMERA).getOrThrow()
        assertCameraGranted(true)
        gateway.grantPermission(TARGET, Manifest.permission.CAMERA).getOrThrow()
        gateway.revokePermission(TARGET, Manifest.permission.CAMERA).getOrThrow()
        assertCameraGranted(false)
        gateway.revokePermission(TARGET, Manifest.permission.CAMERA).getOrThrow()

        val missing = gateway.grantPermission(TARGET, "android.permission.THOR_AUDIT_MISSING")
        assertTrue("An undeclared permission must not be reported granted", missing.isFailure)
    }

    @Test
    fun c_suspensionRoundTrip() = runBlocking {
        try {
            gateway.setAppSuspended(TARGET, true).getOrThrow()
            assertSuspended(true)
            gateway.setAppSuspended(TARGET, true).getOrThrow()
            gateway.setAppSuspended(TARGET, false).getOrThrow()
            assertSuspended(false)
            gateway.setAppSuspended(TARGET, false).getOrThrow()
        } finally {
            gateway.setAppSuspended(TARGET, false).getOrThrow()
        }
    }

    @Test
    fun c2_backgroundRestrictionVerifiesUidOverridesWithoutClearingThem() = runBlocking {
        val userId = android.os.Process.myUid() / 100_000
        try {
            gateway.setAppRestricted(TARGET, true).getOrThrow()
            gateway.setAppRestricted(TARGET, false).getOrThrow()
            shell("appops set --user $userId --uid $TARGET RUN_ANY_IN_BACKGROUND ignore")
            assertTrue(shell(
                "appops get --user $userId --uid $TARGET RUN_ANY_IN_BACKGROUND",
            ).contains("ignore"))
            assertTrue(gateway.setAppRestricted(TARGET, false).isFailure)
            assertTrue(shell(
                "appops get --user $userId --uid $TARGET RUN_ANY_IN_BACKGROUND",
            ).contains("ignore"))
        } finally {
            // This op's platform default is MODE_ALLOWED. The CLI's literal "default" writes
            // MODE_DEFAULT (3), which can itself remain an overriding UID entry.
            shell("appops set --user $userId --uid $TARGET RUN_ANY_IN_BACKGROUND allow")
            gateway.setAppRestricted(TARGET, false).getOrThrow()
        }
    }

    @Test
    fun d_forceStopReturnsLocalizedUnsupportedResult() = runBlocking {
        val result = gateway.forceStopApp(TARGET)
        assertTrue(result.isFailure)
        assertEquals(
            UiText.StringResource(R.string.force_stop_unsupported_dhizuku),
            requireNotNull(result.exceptionOrNull()).asUiText(),
        )
    }

    @Test
    fun e_clearDataCompletesAndErasesMarkerWithoutCrashing() = runBlocking {
        val dataDirectory = shell("run-as $TARGET pwd")
        assertTrue("run-as must reach the fixture", dataDirectory.endsWith(TARGET))
        shell("run-as $TARGET mkdir -p files")
        shell("run-as $TARGET touch files/thor-owner-test-marker")
        assertEquals(
            "./files/thor-owner-test-marker",
            shell("run-as $TARGET find . -name thor-owner-test-marker"),
        )

        gateway.clearAppData(TARGET).getOrThrow()

        assertEquals("The fixture must remain readable", dataDirectory, shell("run-as $TARGET pwd"))
        assertEquals("", shell("run-as $TARGET find . -name thor-owner-test-marker"))
        assertTrue(context.packageManager.getApplicationInfo(TARGET, 0)
            .flags and ApplicationInfo.FLAG_INSTALLED != 0)
        assertTrue(gateway.clearAppData("com.valhalla.thor.audit.missing").isFailure)
    }

    @Test
    fun z_uninstallCompletesAndRemovesOnlyFixture() = runBlocking {
        gateway.uninstallApp(TARGET).getOrThrow()
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            context.packageManager.getApplicationInfo(TARGET, 0)
        }
        assertTrue(context.packageManager.getApplicationInfo(context.packageName, 0)
            .flags and ApplicationInfo.FLAG_INSTALLED != 0)
        assertTrue(context.packageManager.getApplicationInfo(DhizukuAPI.getOwnerPackageName(), 0)
            .flags and ApplicationInfo.FLAG_INSTALLED != 0)
    }

    private fun assertCameraGranted(granted: Boolean) {
        assertEquals(
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.CAMERA, TARGET),
        )
    }

    private fun assertSuspended(suspended: Boolean) {
        val info = context.packageManager.getApplicationInfo(TARGET, 0)
        assertEquals(suspended, info.flags and ApplicationInfo.FLAG_SUSPENDED != 0)
    }

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {
            it.readText().trim()
        }
    }

    class ThrowingTarget {
        val denial = SecurityException("expected permission denial")
        var calls = 0

        fun perform() {
            calls++
            throw denial
        }
    }

    companion object {
        private const val TARGET = "com.valhalla.thor.audit.target"
    }
}
