// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.installer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyInstallIdentityTest {
    private val before = LegacyInstallIdentity(42L, setOf("certificate"), true, false, "other.store")
    private val after = before.copy(installer = "com.android.vending")

    @Test
    fun `success requires unchanged installed app and verified Play attribution`() {
        assertTrue(after.verifies(before))
        assertFalse(before.verifies(before))
        assertFalse(after.copy(installer = "com.google.android.packageinstaller").verifies(before))
        assertFalse(after.copy(versionCode = 43L).verifies(before))
        assertFalse(after.copy(signers = setOf("other")).verifies(before))
        assertFalse(after.copy(signers = emptySet()).verifies(before.copy(signers = emptySet())))
        assertFalse(after.copy(installed = false).verifies(before))
        assertFalse(after.copy(hasSplits = true).verifies(before))
    }
}
