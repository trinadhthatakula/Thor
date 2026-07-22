// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreezerShortcutContractTest {

    @Test
    fun parseAction_returns_known_actions() {
        assertEquals(FreezerShortcutContract.ACTION_LAUNCH, FreezerShortcutContract.parseAction("launch"))
        assertEquals(FreezerShortcutContract.ACTION_FREEZE_ALL, FreezerShortcutContract.parseAction("freeze_all"))
        assertEquals(FreezerShortcutContract.ACTION_UNFREEZE_ALL, FreezerShortcutContract.parseAction("unfreeze_all"))
    }

    @Test
    fun parseAction_returns_null_for_unknown_or_missing() {
        assertNull(FreezerShortcutContract.parseAction(null))
        assertNull(FreezerShortcutContract.parseAction(""))
        assertNull(FreezerShortcutContract.parseAction("delete_all"))
    }

    @Test
    fun appShortcutId_is_stable_and_package_scoped() {
        assertEquals("freezer_app_com.amazon.mShop.android.shopping",
            FreezerShortcutContract.appShortcutId("com.amazon.mShop.android.shopping"))
        assertEquals(
            FreezerShortcutContract.appShortcutId("a"),
            FreezerShortcutContract.appShortcutId("a")
        )
    }
}
