// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a failed disable may escalate to `pm uninstall -k --user N` — the
 * one freeze decision that changes what a package looks like to the rest of the system. No Android
 * deps.
 *
 * **It is now shut for every privilege mode**, and most of what is below exists to keep it shut: a
 * system-app freeze the platform refuses to disable ends in a visible failure with the package left
 * installed, rather than in a quiet substitution of one mechanic for another. `false` here is not
 * "nothing happens" — it is the gateway returning `Result.failure`, which the freeze surfaces as a
 * message naming the refusal.
 *
 * These tests stay exhaustive over `PrivilegeMode` rather than spot-checking the interesting
 * branch. The failure mode being guarded against is someone adding a mode, or re-opening a branch
 * to make a freeze "work" on a device that refuses to disable, without noticing that the branch's
 * price is a package removed for the user who only asked for it to be frozen.
 */
class UninstallFreezeFallbackTest {

    private fun allowed(
        isSystem: Boolean = true,
        mode: PrivilegeMode = PrivilegeMode.SHIZUKU,
        refused: Boolean = true,
    ) = uninstallFreezeFallbackAllowed(isSystem, mode, refused)

    // --- A refusal is a failure to report, not a licence to remove the package -----------------

    /**
     * Shizuku is the mode the fallback was built for: an OEM (Xiaomi HyperOS, first reported on
     * Android 14) refuses to let the shell uid disable its system packages, and removing the
     * package for the current user was the only mechanic left.
     *
     * That is no longer read as permission to use it. The user asked for a freeze — a reversible
     * thing that leaves the app where it is — and `pm uninstall -k --user N` clears
     * `FLAG_INSTALLED`, so the package stops resolving for anything that does not pass
     * `MATCH_UNINSTALLED_PACKAGES`. `-k` keeps the data; nothing keeps the installed bit. The cost
     * of this answer is real and deliberate: on those devices a Shizuku user cannot freeze system
     * apps at all, and is told why instead of being shown a success toast for a package that was
     * removed for them.
     */
    @Test
    fun `shizuku refused by the platform fails the freeze instead of escalating`() {
        assertFalse(allowed(mode = PrivilegeMode.SHIZUKU, refused = true))
    }

    /**
     * Dhizuku answers like Shizuku, and always has, because it faces the same question: it has a
     * disable rung the platform can *refuse*. Its commands run as the device-owner app rather than
     * as shell, and that app holds no CHANGE_COMPONENT_ENABLED_STATE, so a `SecurityException` out
     * of `PackageManagerService` is the expected refusal.
     *
     * Its escalation was never observed running on hardware — no device with Dhizuku was available
     * while it was open — so this branch shuts on the argument rather than on a measurement. The
     * argument is Shizuku's: a package removed for the user is not what "freeze" was asked for.
     */
    @Test
    fun `dhizuku refused by the platform fails the freeze instead of escalating`() {
        assertFalse(allowed(mode = PrivilegeMode.DHIZUKU, refused = true))
    }

    // --- The discriminator: refusal, not failure ----------------------------------------------

    /**
     * This branch predates the gate being shut everywhere and still earns its own test. A busy
     * PackageManager, a binder timeout or a package caught mid-update all fail to disable without
     * the platform having refused anything; the two cases have to stay distinguishable because the
     * gateways spend the flag on *which sentence the user reads*, and because the explicit removal
     * path that is deferred to its own change may only ever be offered on a real refusal.
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

    // --- Every privilege mode fails closed, refused or not -------------------------------------

    /**
     * Root is uid 0. Every refusal observed in the wild — AOSP's own shell guard and Xiaomi's
     * vendor `canBeDisabled` alike — keys on the shell uid (2000), so a refusal reaching root is an
     * anomaly worth surfacing, not a licence to remove the package. Root reached that reading
     * first; the other two modes have now adopted it.
     */
    @Test
    fun `root never escalates, refused or not`() {
        assertFalse("root refused", allowed(mode = PrivilegeMode.ROOT, refused = true))
        assertFalse("root failed", allowed(mode = PrivilegeMode.ROOT, refused = false))
    }

    /**
     * The other half of Dhizuku's answer, and the older one. A binder timeout, a package mid-update
     * or a `pm` that simply did nothing all fail without the platform having refused; a transient
     * error must never be able to buy a removal even if the refusal branch is ever re-opened for an
     * explicit, asked-for one.
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
     * distinction and is one failed `pm disable` away from removing a user-installed app.
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

    // --- The whole gate, end to end ------------------------------------------------------------

    /**
     * The owner's ruling, pinned as one assertion: **no argument this function can be handed opens
     * the uninstall rung.** Not a refused system app under Shizuku, not one under Dhizuku, not any
     * of the other twelve combinations.
     *
     * Enumerated rather than spot-checked so the enum is guarded too — a new [PrivilegeMode] must
     * be considered here, not silently inherit an answer — and so that re-opening *any* branch,
     * including the two that used to be open, turns this red with the combination named. When the
     * explicit "remove it for this user anyway" path lands it will re-open exactly one branch, and
     * this test is where that decision has to be written down.
     */
    @Test
    fun `nothing opens the uninstall rung, so no freeze can remove a package for the user`() {
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
