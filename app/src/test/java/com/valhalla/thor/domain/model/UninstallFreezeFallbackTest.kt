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
class UninstallFreezeFallbackTest {

    private fun allowed(
        isSystem: Boolean = true,
        mode: PrivilegeMode = PrivilegeMode.SHIZUKU,
        refused: Boolean = true,
    ) = uninstallFreezeFallbackAllowed(isSystem, mode, refused)

    // --- The combinations that are allowed to escalate -----------------------------------------

    @Test
    fun `shizuku refused by the platform may escalate for a system app`() {
        assertTrue(allowed(mode = PrivilegeMode.SHIZUKU, refused = true))
    }

    /**
     * Dhizuku answers like Shizuku, and for the same reason: it has a disable rung that the
     * platform can *refuse*. Its commands run as the device-owner app rather than as shell, and
     * that app holds no CHANGE_COMPONENT_ENABLED_STATE, so a `SecurityException` out of
     * `PackageManagerService` is the expected refusal — after which uninstall-for-user is the only
     * remaining way to freeze a preinstalled app.
     *
     * This branch was `false` while `DhizukuSystemGateway` uninstalled system apps unconditionally,
     * where a closed gate would have changed nothing. Now that the gateway routes through the
     * disable chain first, a closed gate makes the rung unreachable and takes system-app freezing
     * away from Dhizuku entirely.
     */
    @Test
    fun `dhizuku refused by the platform may escalate for a system app`() {
        assertTrue(allowed(mode = PrivilegeMode.DHIZUKU, refused = true))
    }

    // --- The discriminator: refusal, not failure ----------------------------------------------

    /**
     * This is the branch the whole gate exists for. A busy PackageManager, a binder timeout or a
     * package caught mid-update all fail to disable without the platform having refused anything;
     * escalating on those is how a transient error turns into permanent data loss that nobody ever
     * reports, because from the outside it looked like the freeze worked.
     */
    @Test
    fun `shizuku that merely failed may not escalate`() {
        assertFalse(allowed(mode = PrivilegeMode.SHIZUKU, refused = false))
    }

    /**
     * Guards against the version gate coming back. It was `sdkInt >= 36` on the report that
     * shell-uid disabling broke on Android 16; a stock AOSP API 36 emulator disables system apps
     * from uid 2000 without complaint, and the restriction that does exist is Xiaomi's, first seen
     * on Android 14. Nothing about the answer may depend on the release — only on the refusal.
     */
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

    // --- Every other privilege mode fails closed, refused or not -------------------------------

    /**
     * Root is uid 0. Every refusal observed in the wild — AOSP's own shell guard and Xiaomi's
     * vendor `canBeDisabled` alike — keys on the shell uid (2000), so a refusal reaching root is an
     * anomaly worth surfacing, not a licence to delete the user's data.
     */
    @Test
    fun `root never escalates, refused or not`() {
        assertFalse("root refused", allowed(mode = PrivilegeMode.ROOT, refused = true))
        assertFalse("root failed", allowed(mode = PrivilegeMode.ROOT, refused = false))
    }

    /**
     * The half of Dhizuku's answer that did not change. A binder timeout, a package mid-update or
     * a `pm` that simply did nothing all fail without the platform having refused, and escalating
     * on those is how a transient error becomes a package silently removed for the user.
     */
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

    /**
     * A user app disables on every supported release under every privilege mode, so there is no
     * platform gap to work around. If this ever returns true, some caller has lost the `isSystem`
     * distinction and is one failed `pm disable` away from wiping a user-installed app.
     */
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

    /**
     * Guards the enum itself: a new mode must be considered, not silently inherit `true`.
     *
     * The expected set is spelled out rather than counted. Both members earned their place by
     * having a disable rung the platform can refuse — root cannot be refused by the guards this
     * fallback exists for (they key on the shell uid), and NONE has no rung at all.
     */
    @Test
    fun `only the modes with a refusable disable rung can escalate`() {
        val escalating = PrivilegeMode.entries.filter {
            uninstallFreezeFallbackAllowed(
                isSystem = true,
                privilegeMode = it,
                disableRefusedByPolicy = true,
            )
        }
        assertTrue(
            "unexpected modes may remove packages for the user: $escalating",
            escalating.toSet() == setOf(PrivilegeMode.SHIZUKU, PrivilegeMode.DHIZUKU),
        )
    }
}
