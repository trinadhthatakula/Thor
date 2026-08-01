// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a failed disable may escalate to `pm uninstall --user` — the one
 * freeze decision that can cost a user data they cannot recover. No Android deps.
 *
 * These tests are deliberately exhaustive over `PrivilegeMode` rather than spot-checking the
 * interesting branch. The failure mode being guarded against is someone adding a mode, or relaxing
 * a branch to make a freeze "work", without noticing that the branch's price is the user's data.
 */
class DestructiveFreezeFallbackTest {

    private fun allowed(
        isSystem: Boolean = true,
        mode: PrivilegeMode = PrivilegeMode.SHIZUKU,
        sdkInt: Int = ANDROID_16_BAKLAVA,
    ) = destructiveFreezeFallbackAllowed(isSystem, mode, sdkInt)

    // --- The one combination that is allowed to escalate -------------------------------------

    @Test
    fun `shizuku on android 16 may escalate for a system app`() {
        assertTrue(allowed(mode = PrivilegeMode.SHIZUKU, sdkInt = ANDROID_16_BAKLAVA))
    }

    @Test
    fun `shizuku above android 16 may escalate for a system app`() {
        assertTrue(allowed(mode = PrivilegeMode.SHIZUKU, sdkInt = ANDROID_16_BAKLAVA + 1))
        assertTrue(allowed(mode = PrivilegeMode.SHIZUKU, sdkInt = 99))
    }

    // --- The boundary itself -------------------------------------------------------------------

    /**
     * Off-by-one here is not a cosmetic bug in either direction: one release too low destroys data
     * on devices that could have disabled the app, one too high leaves Shizuku users on 16 unable
     * to freeze system apps at all.
     */
    @Test
    fun `shizuku one release below the boundary may not escalate`() {
        assertFalse(allowed(mode = PrivilegeMode.SHIZUKU, sdkInt = ANDROID_16_BAKLAVA - 1))
    }

    @Test
    fun `shizuku on older releases may not escalate`() {
        // 28 is Thor's minSdk; 35 is the release immediately before the restriction landed.
        for (sdk in listOf(28, 29, 30, 31, 33, 34, 35)) {
            assertFalse("sdk $sdk must not escalate", allowed(mode = PrivilegeMode.SHIZUKU, sdkInt = sdk))
        }
    }

    // --- Every other privilege mode fails closed, at every version -----------------------------

    @Test
    fun `root never escalates at any version`() {
        for (sdk in listOf(28, 35, ANDROID_16_BAKLAVA, ANDROID_16_BAKLAVA + 1, 99)) {
            assertFalse("root at sdk $sdk", allowed(mode = PrivilegeMode.ROOT, sdkInt = sdk))
        }
    }

    @Test
    fun `dhizuku never escalates at any version`() {
        for (sdk in listOf(28, 35, ANDROID_16_BAKLAVA, ANDROID_16_BAKLAVA + 1, 99)) {
            assertFalse("dhizuku at sdk $sdk", allowed(mode = PrivilegeMode.DHIZUKU, sdkInt = sdk))
        }
    }

    @Test
    fun `no privilege never escalates at any version`() {
        for (sdk in listOf(28, 35, ANDROID_16_BAKLAVA, ANDROID_16_BAKLAVA + 1, 99)) {
            assertFalse("none at sdk $sdk", allowed(mode = PrivilegeMode.NONE, sdkInt = sdk))
        }
    }

    // --- User apps are never in scope, whatever else is true -----------------------------------

    /**
     * A user app disables on every supported release under every privilege mode, so there is no
     * platform gap to work around. If this ever returns true, some caller has lost the `isSystem`
     * distinction and is one failed `pm disable` away from wiping a user-installed app.
     */
    @Test
    fun `a user app never escalates under any mode or version`() {
        for (mode in PrivilegeMode.entries) {
            for (sdk in listOf(28, 35, ANDROID_16_BAKLAVA, 99)) {
                assertFalse(
                    "user app under $mode at sdk $sdk",
                    allowed(isSystem = false, mode = mode, sdkInt = sdk),
                )
            }
        }
    }

    /** Guards the enum itself: a new mode must be considered, not silently inherit `true`. */
    @Test
    fun `only shizuku can ever escalate`() {
        val escalating = PrivilegeMode.entries.filter {
            destructiveFreezeFallbackAllowed(isSystem = true, privilegeMode = it, sdkInt = 99)
        }
        assertTrue(
            "unexpected modes may destroy data: $escalating",
            escalating == listOf(PrivilegeMode.SHIZUKU),
        )
    }
}
