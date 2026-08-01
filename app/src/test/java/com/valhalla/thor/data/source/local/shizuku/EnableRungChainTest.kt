// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rung chain behind `Shizuku.setAppDisabled`.
 *
 * The rungs themselves cannot be tested here — every one of them needs a live Shizuku binder, a
 * `PackageManager`, or both, and no gateway in Thor has a unit test for exactly that reason. What
 * *is* reachable is the part that changed and the part that is easy to get wrong later: which rung
 * runs first, and the rule that a rung is judged by re-reading the state rather than by what it
 * reported. [orderRungs] and [firstRungThatSticks] are pure for this purpose.
 */
class EnableRungChainTest {

    private fun rung(label: String, reports: Boolean = true, onRun: (String) -> Unit = {}) =
        EnableRung(label) { onRun(label); reports }

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

        assertEquals("second", firstRungThatSticks(rungs) { stateChanged })
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
            rung("liar", reports = true, onRun = { ran += it }),
            rung("worker", reports = true, onRun = { ran += it; stateChanged = true }),
        )

        assertEquals("worker", firstRungThatSticks(rungs) { stateChanged })
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
            rung("silent-worker", reports = false, onRun = { stateChanged = true }),
            rung("never-reached", reports = true),
        )

        assertEquals("silent-worker", firstRungThatSticks(rungs) { stateChanged })
    }

    /**
     * Null is what escalates the system-app freeze to its destructive rung, so "nothing stuck" has
     * to mean every rung genuinely ran and was verified — not that the loop gave up early.
     */
    @Test
    fun `returns null only after every rung has run`() {
        val ran = mutableListOf<String>()
        val rungs = listOf(
            rung("a", reports = true, onRun = { ran += it }),
            rung("b", reports = false, onRun = { ran += it }),
            rung("c", reports = true, onRun = { ran += it }),
        )

        assertNull(firstRungThatSticks(rungs) { false })
        assertEquals(listOf("a", "b", "c"), ran)
    }
}
