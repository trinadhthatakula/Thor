// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.UserPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerSettingsCapabilityTest {
    @Test
    fun `Dhizuku preference disables unsupported options even with Root available`() {
        val state = SettingsViewModel.SettingsUiState(
            prefs = UserPreferences(
                preferredPrivilegeMode = PrivilegeMode.DHIZUKU,
                autoReinstallEnabled = true,
                grantAllPermissionsOnInstall = true,
            ),
            isRootAvailable = true,
            isShizukuAvailable = true,
            isDhizukuAvailable = true,
        )
        assertFalse(state.canFixStore)
        assertFalse(state.canGrantPermissionsOnInstall)
        assertTrue(state.prefs.autoReinstallEnabled)
        assertTrue(state.prefs.grantAllPermissionsOnInstall)
    }

    @Test
    fun `unavailable preference uses actual fallback capability`() {
        val rootFallback = SettingsViewModel.SettingsUiState(
            prefs = UserPreferences(preferredPrivilegeMode = PrivilegeMode.DHIZUKU),
            isRootAvailable = true,
        )
        assertTrue(rootFallback.canFixStore)
        assertTrue(rootFallback.canGrantPermissionsOnInstall)
        val dhizukuFallback = rootFallback.copy(isRootAvailable = false, isDhizukuAvailable = true)
        assertFalse(dhizukuFallback.canFixStore)
        assertFalse(dhizukuFallback.canGrantPermissionsOnInstall)
        assertFalse(SettingsViewModel.SettingsUiState().canFixStore)
    }

}
