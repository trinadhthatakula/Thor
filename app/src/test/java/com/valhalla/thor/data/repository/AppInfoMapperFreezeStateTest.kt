// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.source.local.isEffectivelyEnabled
import com.valhalla.thor.data.source.local.room.AppEntity
import com.valhalla.thor.domain.model.RestorePlan
import com.valhalla.thor.domain.model.isFrozen
import com.valhalla.thor.domain.model.restorePlanFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class AppInfoMapperFreezeStateTest {

    @Test
    fun `mapping preserves all freeze mechanisms and their restore plans`() {
        val cases = listOf(
            StateCase("active", restore = RestorePlan(unsuspend = false, enable = false)),
            StateCase("hidden", hidden = true, restore = RestorePlan(unsuspend = false, enable = true)),
            StateCase("disabled", enabled = false, restore = RestorePlan(unsuspend = false, enable = true)),
            StateCase("user_removed", installed = false, restore = RestorePlan(unsuspend = false, enable = true)),
            StateCase("suspended", suspended = true, restore = RestorePlan(unsuspend = true, enable = false)),
            StateCase("hidden_suspended", hidden = true, suspended = true, restore = RestorePlan(unsuspend = true, enable = true)),
            StateCase("hidden_disabled", hidden = true, enabled = false, restore = RestorePlan(unsuspend = false, enable = true)),
            StateCase("hidden_removed", hidden = true, installed = false, restore = RestorePlan(unsuspend = false, enable = true)),
        )
        val packageManager = ApplicationProvider.getApplicationContext<Application>().packageManager

        for (state in cases) {
            val applicationInfo = ApplicationInfo().apply {
                packageName = "com.example.${state.name}"
                enabled = state.enabled
                flags = (if (state.installed) ApplicationInfo.FLAG_INSTALLED else 0) or
                    (if (state.suspended) ApplicationInfo.FLAG_SUSPENDED else 0)
                ReflectionHelpers.setField(this, "privateFlags", if (state.hidden) 1 else 0)
            }
            val packageInfo = PackageInfo().apply {
                packageName = applicationInfo.packageName
                this.applicationInfo = applicationInfo
            }
            val mapped = mapToAppInfo(packageInfo, applicationInfo, packageManager, isLightweight = true)

            assertEquals(state.name, state.installed, mapped.isInstalled)
            assertEquals(state.name, state.restore, restorePlanFor(mapped.enabled, mapped.isSuspended))
            assertEquals(state.name, state.restore.enable || state.restore.unsuspend, mapped.isFrozen)
            // The cache validation path must see the same state that gets persisted by the mapper.
            assertEquals(state.name, mapped.enabled, applicationInfo.isEffectivelyEnabled)
            val cached = AppEntity.fromDomain(mapped).toDomain()
            assertEquals(state.name, mapped.isFrozen, cached.isFrozen)
            assertEquals(state.name, state.restore, restorePlanFor(cached.enabled, cached.isSuspended))
        }
    }

    @Test
    fun `privileged private flag does not make an installed application hidden`() {
        val info = ApplicationInfo().apply {
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED
            ReflectionHelpers.setField(this, "privateFlags", 8)
        }
        assertTrue(info.isEffectivelyEnabled)
        ReflectionHelpers.setField(info, "privateFlags", 8 or 1)
        assertFalse(info.isEffectivelyEnabled)
        ReflectionHelpers.setField(info, "privateFlags", 8)
        assertTrue(info.isEffectivelyEnabled)
    }

    private data class StateCase(
        val name: String,
        val enabled: Boolean = true,
        val installed: Boolean = true,
        val hidden: Boolean = false,
        val suspended: Boolean = false,
        val restore: RestorePlan,
    )
}
