// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.gateway

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.source.local.dhizuku.DhizukuReflector
import com.valhalla.thor.domain.model.SuspensionChange
import com.valhalla.thor.domain.model.SuspensionFailureReason
import com.valhalla.thor.domain.model.SuspensionPolicyException
import com.valhalla.thor.presentation.FakePreferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DhizukuSuspensionGatewayTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val gateway = DhizukuSystemGateway(
        context, DhizukuReflector(context), FakePreferenceRepository(), Dispatchers.Unconfined,
    )

    @Test
    fun `unfreeze preserves a confirmed suspension with unidentified ownership`() = runTest {
        install(suspended = true)
        // No Dhizuku runtime is initialized: this recovery must only read, never invoke DPM.
        val result = gateway.setAppSuspended(PACKAGE, false).getOrThrow()

        assertEquals(SuspensionChange(ownershipUnknown = true), result)
        assertTrue(result.suspensionRemains)
        assertEquals(ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SUSPENDED,
            context.packageManager.getApplicationInfo(PACKAGE, 0).flags)
    }

    @Test
    fun `unfreeze of a confirmed unsuspended app has no remaining pause`() = runTest {
        install(suspended = false)
        assertEquals(SuspensionChange(), gateway.setAppSuspended(PACKAGE, false).getOrThrow())
    }

    @Test
    fun `suspend still refuses to write an unverifiable shared entry`() = runTest {
        install(suspended = true)
        val failure = gateway.setAppSuspended(PACKAGE, true).exceptionOrNull() as SuspensionPolicyException
        assertEquals(SuspensionFailureReason.CANNOT_KEEP_INDEPENDENT, failure.reason)
        assertEquals(ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SUSPENDED,
            context.packageManager.getApplicationInfo(PACKAGE, 0).flags)
    }

    @Test
    fun `unreadable app state does not claim a successful restoration`() = runTest {
        val failure = gateway.setAppSuspended(PACKAGE, false).exceptionOrNull() as SuspensionPolicyException
        assertEquals(SuspensionFailureReason.OWNERSHIP_UNKNOWN, failure.reason)
    }

    private fun install(suspended: Boolean) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = PACKAGE
                flags = ApplicationInfo.FLAG_INSTALLED or if (suspended) ApplicationInfo.FLAG_SUSPENDED else 0
            }
        })
    }

    private companion object {
        const val PACKAGE = "com.example.target"
    }
}
