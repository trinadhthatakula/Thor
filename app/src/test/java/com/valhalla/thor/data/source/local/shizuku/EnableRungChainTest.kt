// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The rung chain behind `Shizuku.setAppDisabled`.
 *
 * The rungs themselves cannot be tested here — every one of them needs a live Shizuku binder, a
 * `PackageManager`, or both, and no gateway in Thor has a unit test for exactly that reason. What
 * *is* reachable is the part that changed and the part that is easy to get wrong later: which rung
 * runs first, the rule that a rung is judged by re-reading the state rather than by what it
 * reported, and whether the platform refusing is carried back out of the chain. [orderRungs] and
 * [firstRungThatSticks] are pure for this purpose.
 */
class EnableRungChainTest {

    private fun rung(
        label: String,
        reports: RungResult = RungResult.RAN,
        onRun: (String) -> Unit = {},
    ) = EnableRung(label) { onRun(label); reports }

    private val shell = rung(RUNG_SHELL)
    private val reflection = rung(RUNG_REFLECTION)
    private val unprivileged = rung(RUNG_UNPRIVILEGED)

    /**
     * The default order, and the one every non-system caller keeps. `pm disable --user N` works for
     * user apps and costs less than a binder reflection round trip; reordering that path was
     * explicitly out of scope for the system-app fix.
     */
    @Test
    fun `shell-first tries the shell rung before reflection`() {
        assertEquals(
            listOf(RUNG_SHELL, RUNG_REFLECTION, RUNG_UNPRIVILEGED),
            orderRungs(EnableRungOrder.SHELL_FIRST, shell, reflection, unprivileged).map { it.label }
        )
    }

    /** The system-app freeze order: the direct `IPackageManager` call gets the first attempt. */
    @Test
    fun `reflection-first tries the reflection rung before the shell`() {
        assertEquals(
            listOf(RUNG_REFLECTION, RUNG_SHELL, RUNG_UNPRIVILEGED),
            orderRungs(EnableRungOrder.REFLECTION_FIRST, shell, reflection, unprivileged)
                .map { it.label }
        )
    }

    /**
     * The unprivileged rung can only throw for a package Thor does not own, so it must never be
     * spent before a privileged one whatever the order — this is the invariant that would break
     * silently if a future order were added to the `when` without thinking about it.
     */
    @Test
    fun `the unprivileged rung is last in every order`() {
        EnableRungOrder.entries.forEach { order ->
            assertEquals(
                "unprivileged rung must stay last for $order",
                RUNG_UNPRIVILEGED,
                orderRungs(order, shell, reflection, unprivileged).last().label
            )
        }
    }

    /** Nothing after the winning rung runs; the chain is a chain, not a broadcast. */
    @Test
    fun `stops at the first rung after which the state actually changed`() {
        val ran = mutableListOf<String>()
        var stateChanged = false
        val rungs = listOf(
            rung("first", onRun = { ran += it }),
            rung("second", onRun = { ran += it; stateChanged = true }),
            rung("third", onRun = { ran += it }),
        )

        assertEquals("second", firstRungThatSticks(rungs) { stateChanged }.winner)
        assertEquals(listOf("first", "second"), ran)
    }

    /**
     * The rule the whole fix rests on: `pm` exits 0 for a disable `PackageManagerService` refused,
     * and a `Bypass.invoke` that threw nothing has still proven nothing. A rung reporting success
     * while the state did not move must not end the chain — that is what used to let a freeze
     * report success without freezing anything.
     */
    @Test
    fun `a rung that reports success without changing the state does not end the chain`() {
        val ran = mutableListOf<String>()
        var stateChanged = false
        val rungs = listOf(
            rung("liar", reports = RungResult.RAN, onRun = { ran += it }),
            rung("worker", reports = RungResult.RAN, onRun = { ran += it; stateChanged = true }),
        )

        assertEquals("worker", firstRungThatSticks(rungs) { stateChanged }.winner)
        assertEquals(listOf("liar", "worker"), ran)
    }

    /**
     * And the mirror image: `Shizuku.execute` returns -1 when it cannot read an exit code at all
     * (null binder, timeout), which is not the same statement as "the state did not change". The
     * post-read outranks the report in both directions.
     */
    @Test
    fun `a rung that reports failure still wins if the state changed`() {
        var stateChanged = false
        val rungs = listOf(
            rung("silent-worker", reports = RungResult.FAILED, onRun = { stateChanged = true }),
            rung("never-reached", reports = RungResult.RAN),
        )

        assertEquals("silent-worker", firstRungThatSticks(rungs) { stateChanged }.winner)
    }

    /**
     * A null winner is what escalates the system-app freeze to its destructive rung, so "nothing
     * stuck" has to mean every rung genuinely ran and was verified — not that the loop gave up
     * early.
     */
    @Test
    fun `returns a null winner only after every rung has run`() {
        val ran = mutableListOf<String>()
        val rungs = listOf(
            rung("a", reports = RungResult.RAN, onRun = { ran += it }),
            rung("b", reports = RungResult.FAILED, onRun = { ran += it }),
            rung("c", reports = RungResult.RAN, onRun = { ran += it }),
        )

        assertNull(firstRungThatSticks(rungs) { false }.winner)
        assertEquals(listOf("a", "b", "c"), ran)
    }

    // --- The refusal flag: the bit that costs a user their data ---------------------------------

    /**
     * The default. Everything failing without the platform having said no must leave the flag
     * clear, because the caller spends a set flag on `pm uninstall --user N`.
     */
    @Test
    fun `plain failures never set the refusal flag`() {
        val rungs = listOf(
            rung("a", reports = RungResult.FAILED),
            rung("b", reports = RungResult.FAILED),
            rung("c", reports = RungResult.RAN),
        )

        assertFalse(firstRungThatSticks(rungs) { false }.refusedByPolicy)
    }

    /**
     * Sticky across rungs. A refusal from the reflection rung is a fact about the *device*, so a
     * later rung merely failing must not erase it — otherwise the flag would report whatever the
     * last rung happened to say, and the OEM devices this fallback exists for would never reach it.
     */
    @Test
    fun `a refusal on any rung survives later rungs that merely fail`() {
        val rungs = listOf(
            rung("refused", reports = RungResult.REFUSED_BY_POLICY),
            rung("failed", reports = RungResult.FAILED),
            rung("failed too", reports = RungResult.FAILED),
        )

        assertTrue(firstRungThatSticks(rungs) { false }.refusedByPolicy)
    }

    /**
     * A refused rung whose state nonetheless moved reports both: the winner is real (the caller
     * returns success and never consults the flag), but the refusal is not fabricated away.
     */
    @Test
    fun `a chain that wins after a refusal reports the winner and the refusal`() {
        var stateChanged = false
        val rungs = listOf(
            rung("refused", reports = RungResult.REFUSED_BY_POLICY),
            rung("worker", reports = RungResult.RAN, onRun = { stateChanged = true }),
        )

        val outcome = firstRungThatSticks(rungs) { stateChanged }
        assertEquals("worker", outcome.winner)
        assertTrue(outcome.refusedByPolicy)
    }

    // --- Recognising a refusal ------------------------------------------------------------------

    /**
     * The shell rung only ever sees a refusal as text on stderr. Both refusals Thor can actually
     * meet are covered: AOSP's, verified verbatim on a stock API 36 emulator, and Xiaomi's vendor
     * one out of `PackageManagerServiceImpl.canBeDisabled`.
     */
    @Test
    fun `recognises both real refusal messages`() {
        assertTrue(
            isPolicyRefusal(
                "java.lang.SecurityException: Shell cannot change component state for null to 2"
            )
        )
        assertTrue(
            isPolicyRefusal("java.lang.SecurityException: Cannot disable system packages.")
        )
    }

    @Test
    fun `ordinary shell failures are not refusals`() {
        assertFalse(isPolicyRefusal(null as String?))
        assertFalse(isPolicyRefusal(""))
        assertFalse(isPolicyRefusal("Error: java.lang.IllegalArgumentException: Unknown package: x"))
        assertFalse(isPolicyRefusal("Shell command failed with code 1: pm disable-user --user 0 x"))
    }

    /** The reflection rung throws rather than printing, so the throwable overload must agree. */
    @Test
    fun `recognises a thrown SecurityException through its cause chain`() {
        assertTrue(isPolicyRefusal(SecurityException("nope")))
        assertTrue(
            isPolicyRefusal(
                RuntimeException("reflection failed", IOException("io", SecurityException("nope")))
            )
        )
        assertFalse(isPolicyRefusal(null as Throwable?))
        assertFalse(isPolicyRefusal(IllegalStateException("binder is dead")))
    }

    /**
     * A self-referential cause chain is what a badly-wrapped reflection failure can produce, and an
     * unbounded walk would hang the freeze rather than fail it.
     */
    @Test
    fun `a cyclic cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertFalse(isPolicyRefusal(a))
    }
}
