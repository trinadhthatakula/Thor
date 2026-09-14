// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.dhizuku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.thor.data.freezer.AppFreezeStateReader
import com.valhalla.thor.data.gateway.DhizukuSystemGateway
import com.valhalla.thor.data.repository.PreferenceRepositoryImpl
import com.valhalla.thor.data.source.local.isEffectivelyEnabled
import com.valhalla.thor.data.source.local.isHiddenForUser
import com.valhalla.thor.domain.model.FreezeState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.rosan.dhizuku.api.Dhizuku as DhizukuAPI

/**
 * Opt-in live Dhizuku regression. Pass `dhizukuTestPackage` naming a disposable, active app;
 * Thor must already be authorized in Dhizuku. Both helper and gateway use the real device-owner
 * binder. Each round trip restores the target in finally, including when an assertion fails.
 */
@RunWith(AndroidJUnit4::class)
class DhizukuFreezeIntegrationTest {
    private lateinit var context: Context
    private lateinit var targetPackage: String

    @Before
    fun requireExplicitTargetAndDhizukuGrant() {
        val requestedPackage = InstrumentationRegistry.getArguments()
            .getString("dhizukuTestPackage")?.trim()
        assumeTrue(
            "Opt in with instrumentation argument dhizukuTestPackage=<disposable active app>",
            !requestedPackage.isNullOrEmpty(),
        )
        targetPackage = requireNotNull(requestedPackage)
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertTrue("Dhizuku must be running with Thor authorized", DhizukuAPI.init(context))
        assertTrue("Thor must already be authorized in Dhizuku", DhizukuAPI.isPermissionGranted())
        DhizukuHelper.markClientInitialised(true)
        assertFalse("The target must not be Thor", targetPackage == context.packageName)
        assertFalse("The target must not be Dhizuku", targetPackage == DhizukuAPI.getOwnerPackageName())
        assertFalse(
            "The target must not be the instrumentation app",
            targetPackage == InstrumentationRegistry.getInstrumentation().context.packageName,
        )

        val initial = readTarget()
        assertTrue("Target must initially be installed", initial.isInstalled)
        assertTrue("Target must initially be enabled", initial.enabled)
        assertFalse("Target must initially be visible", initial.isHiddenForUser)
        assertFalse(
            "Target must initially be unsuspended",
            initial.flags and ApplicationInfo.FLAG_SUSPENDED != 0,
        )
    }

    @Test
    fun helperHidesAndUnhidesWithoutUninstalling() {
        try {
            assertTrue("Dhizuku hide failed", DhizukuHelper.setAppHidden(context, targetPackage, true))
            assertHiddenState(true)
            assertTrue("Repeated hide failed", DhizukuHelper.setAppHidden(context, targetPackage, true))
            assertHiddenState(true)

            assertTrue("Dhizuku unhide failed", DhizukuHelper.setAppHidden(context, targetPackage, false))
            assertHiddenState(false)
            assertTrue("Repeated unhide failed", DhizukuHelper.setAppHidden(context, targetPackage, false))
            assertHiddenState(false)
        } finally {
            restoreTarget()
        }
    }

    @Test
    fun gatewayFreezeAndUnfreezeAreIdempotent() = runBlocking {
        val gateway = DhizukuSystemGateway(
            context,
            DhizukuReflector(context),
            PreferenceRepositoryImpl(context),
            Dispatchers.IO,
        )
        try {
            gateway.setAppDisabled(targetPackage, true).getOrThrow()
            assertHiddenState(true)
            gateway.setAppDisabled(targetPackage, true).getOrThrow()
            assertHiddenState(true)

            gateway.setAppDisabled(targetPackage, false).getOrThrow()
            assertHiddenState(false)
            gateway.setAppDisabled(targetPackage, false).getOrThrow()
            assertHiddenState(false)
        } finally {
            restoreTarget()
        }
    }

    @Test
    fun missingPackageCannotReportSuccessfulHideOrUnhide() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val missingPackage = "com.valhalla.thor.dhizuku.missing.p$suffix"
        assertFalse(DhizukuHelper.setAppHidden(context, missingPackage, true))
        assertFalse(DhizukuHelper.setAppHidden(context, missingPackage, false))
    }

    private fun assertHiddenState(hidden: Boolean) {
        val info = readTarget()
        assertTrue("Hiding must keep the package installed", info.isInstalled)
        assertTrue("Hiding must preserve the enabled setting", info.enabled)
        assertEquals("ApplicationInfo hidden flag", hidden, info.isHiddenForUser)
        assertEquals("Effective enabled state", !hidden, info.isEffectivelyEnabled)
        assertEquals(
            "The live freezer reader must recognize the device-policy hidden state",
            if (hidden) FreezeState.FROZEN else FreezeState.ACTIVE,
            AppFreezeStateReader(context.packageManager).stateOf(targetPackage),
        )
    }

    private fun restoreTarget() {
        assertTrue(
            "Cleanup could not unhide $targetPackage; restore it in Dhizuku before continuing",
            DhizukuHelper.setAppHidden(context, targetPackage, false),
        )
        assertHiddenState(false)
    }

    private fun readTarget(): ApplicationInfo = context.packageManager.getApplicationInfo(
        targetPackage,
        PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS,
    )

    private val ApplicationInfo.isInstalled: Boolean
        get() = flags and ApplicationInfo.FLAG_INSTALLED != 0
}
