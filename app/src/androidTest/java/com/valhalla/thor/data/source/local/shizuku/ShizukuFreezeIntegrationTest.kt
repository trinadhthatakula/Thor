// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.gateway.ShizukuSystemGateway
import com.valhalla.thor.data.source.local.thorUserId
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.PrivilegeSweepLaunchResult
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepSpec
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import rikka.shizuku.Shizuku as ShizukuApi

/**
 * Opt in on a disposable emulator with shizukuFreezeTest=true. Thor must already have Shizuku
 * permission. Exercises the real shell transport, gateway and foreground queue against the
 * emulator's Easter Egg system app; restores its original enabled setting in finally.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuFreezeIntegrationTest {
    @Test
    fun shellCommandsInheritShizukuServerIdentity() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuShellTest") == "true")
        withTimeout(10_000) { while (!ShizukuApi.pingBinder()) delay(100) }
        assertEquals(PackageManager.PERMISSION_GRANTED, ShizukuApi.checkSelfPermission())
        val (code, output) = Shizuku.execute("id -u")
        assertEquals("Shizuku command failed: $output", 0, code)
        assertEquals(ShizukuApi.getUid().toString(), output?.trim())
    }

    @Test
    fun systemAppRoundTripThroughShellGatewayAndService() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuFreezeTest") == "true")
        assertTrue("Use a disposable emulator", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        withTimeout(10_000) {
            while (!ShizukuApi.pingBinder()) delay(100)
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, ShizukuApi.checkSelfPermission())
        assertEquals("Root-backed Shizuku would mask shell restrictions", 2000, ShizukuApi.getUid())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packages = Packages(context)
        val initial = requireNotNull(packages.getApplicationInfoOrNull(TARGET))
        assertTrue(initial.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertTrue(initial.flags and ApplicationInfo.FLAG_INSTALLED != 0)
        assertTrue(initial.enabled)
        val originalSetting = context.packageManager.getApplicationEnabledSetting(TARGET)
        val preferences = requireNotNull(GlobalContext.get().getOrNull<PreferenceRepository>())
        val originalMode = preferences.userPreferences.first().preferredPrivilegeMode
        val gateway = requireNotNull(GlobalContext.get().getOrNull<ShizukuSystemGateway>())
        val controller = requireNotNull(GlobalContext.get().getOrNull<PrivilegeSweepController>())
        try {
            preferences.setPrivilegeMode(PrivilegeMode.SHIZUKU)
            assertEquals(0, Shizuku.execute("pm disable-user --user $thorUserId $TARGET").first)
            assertDisabled(packages, true)
            assertEquals(0, Shizuku.execute("pm enable --user $thorUserId $TARGET").first)
            assertDisabled(packages, false)

            gateway.setAppDisabled(TARGET, true).getOrThrow()
            assertDisabled(packages, true)
            gateway.setAppDisabled(TARGET, false).getOrThrow()
            assertDisabled(packages, false)

            for (operation in listOf(PrivilegeSweepOperation.FREEZE, PrivilegeSweepOperation.UNFREEZE)) {
                val launched = controller.launch(PrivilegeSweepSpec(
                    operation = operation,
                    packageNames = listOf(TARGET),
                    freezerMode = if (operation == PrivilegeSweepOperation.FREEZE) FreezerMode.FREEZE else null,
                    userId = thorUserId,
                    source = PrivilegeSweepSource.APP_LIST,
                ))
                assertTrue("Queue launch: $launched", launched is PrivilegeSweepLaunchResult.Accepted)
                val request = launched as PrivilegeSweepLaunchResult.Accepted
                val terminal = withTimeout(60_000) {
                    controller.observe(request.requestId).first {
                        it != null && it.phase !in setOf(PrivilegeSweepPhase.QUEUED, PrivilegeSweepPhase.RUNNING)
                    }
                }!!
                assertEquals("Queue result: $terminal", PrivilegeSweepPhase.SUCCEEDED, terminal.phase)
                assertFalse("Shizuku task must not report root fallback", terminal.rootLaneDegraded)
                assertDisabled(packages, operation == PrivilegeSweepOperation.FREEZE)
            }
        } finally {
            val verb = if (originalSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) "default-state" else "enable"
            assertEquals("Restore fixture", 0, Shizuku.execute("pm $verb --user $thorUserId $TARGET").first)
            preferences.setPrivilegeMode(originalMode)
        }
    }

    /** Explicit opt-in for the user's initially removed Carousel fixture; restores that state. */
    @Test
    fun removedCarouselRestoresAndOptedInFreezeWorksThroughQueue() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pocoCarouselTest") == "true")
        val target = "com.mfashiongallery.emag"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packages = Packages(context)
        withTimeout(10_000) { while (!ShizukuApi.pingBinder()) delay(100) }
        assertEquals(PackageManager.PERMISSION_GRANTED, ShizukuApi.checkSelfPermission())
        if (InstrumentationRegistry.getArguments().getString("expectShizukuShell") == "true") {
            assertEquals(2000, ShizukuApi.getUid())
        }
        val initial = requireNotNull(packages.getApplicationInfoOrNull(target))
        assertTrue(initial.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertFalse("Fixture must start removed for this user", initial.flags and ApplicationInfo.FLAG_INSTALLED != 0)
        val preferences = requireNotNull(GlobalContext.get().getOrNull<PreferenceRepository>())
        val original = preferences.userPreferences.first()
        val gateway = requireNotNull(GlobalContext.get().getOrNull<ShizukuSystemGateway>())
        val controller = requireNotNull(GlobalContext.get().getOrNull<PrivilegeSweepController>())
        try {
            preferences.setPrivilegeMode(PrivilegeMode.SHIZUKU)
            preferences.setAllowSystemAppRemovalFallback(false)
            // Legacy removed apps must be recoverable even when the new consent is off.
            gateway.setAppDisabled(target, false).getOrThrow()
            assertTrue(requireNotNull(packages.getApplicationInfoOrNull(target)).flags and ApplicationInfo.FLAG_INSTALLED != 0)
            if (ShizukuApi.getUid() == 2000) {
                val refused = gateway.setAppDisabled(target, true).exceptionOrNull()
                assertTrue("Expected Xiaomi's disable refusal: $refused",
                    refused is com.valhalla.thor.domain.model.SystemAppFreezeFailure)
                assertEquals(com.valhalla.thor.domain.model.SystemAppFreezeFailureReason.SYSTEM_APP_DISABLE_REFUSED,
                    (refused as com.valhalla.thor.domain.model.SystemAppFreezeFailure).reason)
                assertTrue("No consent must leave the package installed",
                    requireNotNull(packages.getApplicationInfoOrNull(target)).flags and ApplicationInfo.FLAG_INSTALLED != 0)
            }
            preferences.setAllowSystemAppRemovalFallback(true)
            for (operation in listOf(PrivilegeSweepOperation.FREEZE, PrivilegeSweepOperation.UNFREEZE)) {
                val launched = controller.launch(PrivilegeSweepSpec(
                    operation = operation, packageNames = listOf(target),
                    freezerMode = if (operation == PrivilegeSweepOperation.FREEZE) FreezerMode.FREEZE else null,
                    userId = thorUserId, source = PrivilegeSweepSource.APP_LIST,
                )) as PrivilegeSweepLaunchResult.Accepted
                val terminal = withTimeout(60_000) {
                    controller.observe(launched.requestId).first {
                        it != null && it.phase !in setOf(PrivilegeSweepPhase.QUEUED, PrivilegeSweepPhase.RUNNING)
                    }
                }!!
                assertEquals("Queue result: $terminal", PrivilegeSweepPhase.SUCCEEDED, terminal.phase)
                assertFalse("Shizuku is not Thor's root lane", terminal.rootLaneDegraded)
                val after = requireNotNull(packages.getApplicationInfoOrNull(target))
                if (operation == PrivilegeSweepOperation.UNFREEZE) {
                    assertTrue(after.enabled)
                    assertTrue(after.flags and ApplicationInfo.FLAG_INSTALLED != 0)
                } else if (ShizukuApi.getUid() == 2000) {
                    assertFalse("Refused disable should use the opted-in removal", after.flags and ApplicationInfo.FLAG_INSTALLED != 0)
                } else {
                    assertFalse("Root-backed Shizuku should disable in place", after.enabled)
                    assertTrue(after.flags and ApplicationInfo.FLAG_INSTALLED != 0)
                }
            }
        } finally {
            // Restore enabled=DEFAULT and removed-for-user, as the caller supplied the fixture.
            Shizuku.execute("pm default-state --user $thorUserId $target")
            val result = Shizuku.execute("pm uninstall -k --user $thorUserId $target")
            preferences.setAllowSystemAppRemovalFallback(original.allowSystemAppRemovalFallback)
            preferences.setPrivilegeMode(original.preferredPrivilegeMode)
            assertEquals("Restore Carousel fixture: ${result.second}", 0, result.first)
            assertFalse(requireNotNull(packages.getApplicationInfoOrNull(target)).flags and ApplicationInfo.FLAG_INSTALLED != 0)
        }
    }

    private fun assertDisabled(packages: Packages, expected: Boolean) {
        val info = requireNotNull(packages.getApplicationInfoOrNull(TARGET))
        assertTrue("Freezing must retain the installed package", info.flags and ApplicationInfo.FLAG_INSTALLED != 0)
        assertEquals(!expected, info.enabled)
    }

    private companion object {
        const val TARGET = "com.android.egg"
    }
}
