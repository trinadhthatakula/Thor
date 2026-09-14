// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.domain.model.FreezeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class AppFreezeStateReaderTest {

    @Test
    fun `hidden packages stay frozen and restorable while retaining enabled and installed flags`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val packageManager = context.packageManager
        install(packageManager, "com.example.hidden", ApplicationInfo.FLAG_INSTALLED, hidden = true)
        install(packageManager, "com.example.disabled", ApplicationInfo.FLAG_INSTALLED, enabled = false)
        install(packageManager, "com.example.active", ApplicationInfo.FLAG_INSTALLED)
        val reader = AppFreezeStateReader(packageManager)

        assertEquals(FreezeState.FROZEN, reader.stateOf("com.example.hidden"))
        assertEquals(FreezeState.FROZEN, reader.stateOf("com.example.disabled"))
        assertEquals(FreezeState.ACTIVE, reader.stateOf("com.example.active"))
        assertEquals(FreezeState.ABSENT, reader.stateOf("com.example.absent"))
        // A hidden app must not require an unsuspend before the gateway can unhide it.
        assertEquals(false, reader.isSuspended("com.example.hidden"))

        val hiddenInfo = requireNotNull(
            shadowOf(packageManager).getInternalMutablePackageInfo("com.example.hidden").applicationInfo,
        )
        ReflectionHelpers.setField(hiddenInfo, "privateFlags", 0)
        assertEquals(FreezeState.ACTIVE, reader.stateOf("com.example.hidden"))
    }

    @Test
    fun `isSuspended distinguishes active disabled and suspended installed packages`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val packageManager = context.packageManager
        install(packageManager, "com.example.active", ApplicationInfo.FLAG_INSTALLED)
        install(packageManager, "com.example.disabled", ApplicationInfo.FLAG_INSTALLED, enabled = false)
        install(
            packageManager,
            "com.example.suspended",
            ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SUSPENDED,
        )
        val reader = AppFreezeStateReader(packageManager)

        assertEquals(false, reader.isSuspended("com.example.active"))
        assertEquals(false, reader.isSuspended("com.example.disabled"))
        assertEquals(true, reader.isSuspended("com.example.suspended"))
    }

    @Test
    fun `isSuspended treats an uninstalled or absent package as unknown`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val packageManager = context.packageManager
        install(packageManager, "com.example.uninstalled", ApplicationInfo.FLAG_INSTALLED)
        val uninstalledInfo = requireNotNull(
            shadowOf(packageManager)
                .getInternalMutablePackageInfo("com.example.uninstalled")
                .applicationInfo,
        )
        uninstalledInfo.flags = 0
        val reader = AppFreezeStateReader(packageManager)

        assertFalse(
            packageManager.getApplicationInfo(
                "com.example.uninstalled",
                AppFreezeStateReader.MATCH_FLAGS,
            ).flags and ApplicationInfo.FLAG_INSTALLED != 0,
        )
        assertNull(reader.isSuspended("com.example.uninstalled"))
        assertNull(reader.isSuspended("com.example.absent"))
        assertEquals(FreezeState.FROZEN, reader.stateOf("com.example.uninstalled"))
    }

    private fun install(
        packageManager: android.content.pm.PackageManager,
        packageName: String,
        flags: Int,
        enabled: Boolean = true,
        hidden: Boolean = false,
    ) {
        shadowOf(packageManager).installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.flags = flags
                this.enabled = enabled
                ReflectionHelpers.setField(this, "privateFlags", if (hidden) 1 else 0)
            }
        })
    }
}
