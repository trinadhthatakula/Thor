// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallFreezeFallbackTest {

    private fun allowed(
        isSystem: Boolean = true,
        mode: PrivilegeMode = PrivilegeMode.SHIZUKU,
        refused: Boolean = true,
    ) = uninstallFreezeFallbackAllowed(isSystem, mode, refused)

    // --- A refusal is a failure to report, not a licence to remove the package -----------------

        @Test
    fun `explicit consent permits only refused Shizuku system app disables`() {
        for (mode in PrivilegeMode.entries) {
            for (system in listOf(false, true)) {
                for (refused in listOf(false, true)) {
                    val expected = mode == PrivilegeMode.SHIZUKU && system && refused
                    assertEquals(expected,
                        uninstallFreezeFallbackAllowed(system, mode, refused, removalFallbackEnabled = true))
                    assertFalse(uninstallFreezeFallbackAllowed(system, mode, refused, removalFallbackEnabled = false))
                }
            }
        }
    }

    @Test
    fun `shizuku refused by the platform fails the freeze instead of escalating`() {
        assertFalse(allowed(mode = PrivilegeMode.SHIZUKU, refused = true))
    }

        @Test
    fun `dhizuku refused by the platform fails the freeze instead of escalating`() {
        assertFalse(allowed(mode = PrivilegeMode.DHIZUKU, refused = true))
    }

    // --- The discriminator: refusal, not failure ----------------------------------------------

        @Test
    fun `shizuku that merely failed may not escalate`() {
        assertFalse(allowed(mode = PrivilegeMode.SHIZUKU, refused = false))
    }

        @Test
    fun `the answer depends only on the refusal, never on anything version-like`() {
        for (mode in PrivilegeMode.entries) {
            val refusedAnswer = uninstallFreezeFallbackAllowed(true, mode, true)
            val failedAnswer = uninstallFreezeFallbackAllowed(true, mode, false)
            assertFalse("$mode escalated without a refusal", failedAnswer)
            // Called twice with identical arguments the answer must be identical — the function is
            // pure, so there is no ambient SDK_INT, Build field or clock it could be reading.
            assertTrue(
                "$mode is not deterministic",
                refusedAnswer == uninstallFreezeFallbackAllowed(true, mode, true),
            )
        }
    }

    // --- Every privilege mode fails closed, refused or not -------------------------------------

        @Test
    fun `root never escalates, refused or not`() {
        assertFalse("root refused", allowed(mode = PrivilegeMode.ROOT, refused = true))
        assertFalse("root failed", allowed(mode = PrivilegeMode.ROOT, refused = false))
    }

        @Test
    fun `dhizuku that merely failed may not escalate`() {
        assertFalse("dhizuku failed", allowed(mode = PrivilegeMode.DHIZUKU, refused = false))
    }

    @Test
    fun `no privilege never escalates, refused or not`() {
        assertFalse("none refused", allowed(mode = PrivilegeMode.NONE, refused = true))
        assertFalse("none failed", allowed(mode = PrivilegeMode.NONE, refused = false))
    }

    // --- User apps are never in scope, whatever else is true -----------------------------------

        @Test
    fun `a user app never escalates under any mode, even when refused`() {
        for (mode in PrivilegeMode.entries) {
            for (refused in listOf(true, false)) {
                assertFalse(
                    "user app under $mode, refused=$refused",
                    allowed(isSystem = false, mode = mode, refused = refused),
                )
            }
        }
    }

    // --- The whole gate, end to end ------------------------------------------------------------

        @Test
    fun `without consent nothing opens the uninstall rung`() {
        val escalating = buildList {
            for (isSystem in listOf(true, false)) {
                for (mode in PrivilegeMode.entries) {
                    for (refused in listOf(true, false)) {
                        if (uninstallFreezeFallbackAllowed(isSystem, mode, refused)) {
                            add("isSystem=$isSystem, mode=$mode, refused=$refused")
                        }
                    }
                }
            }
        }
        assertEquals(
            "a freeze may still remove packages for the user: $escalating",
            emptyList<String>(),
            escalating,
        )
    }
}
