// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `pm uninstall -k --user N` said about a preinstalled app, and which sentence the user is
 * shown because of it.
 *
 * [SystemAppRemovalOutcome] and [isRootOnlySystemAppRemoval] are the whole pure surface of the
 * system-app freeze fix; everything around them needs a device. The rung that produces the outcome
 * needs a live Shizuku or Dhizuku binder, and the two `systemFreezeFailureMessage` helpers that
 * consume it are private and resolve their strings through a `Context`. The classification in
 * between is reachable from a plain JVM test, and it is the part worth pinning: it is what decides
 * whether Thor tells somebody to switch to Root mode.
 *
 * [EnableRungChainTest] already pins the canonical string, the plain negatives, and the fact that
 * this classifier and [isPolicyRefusal] never answer for each other's input. This file covers the
 * shapes the message actually arrives in — wrapped in other output, blank, alongside an exit code
 * that disagrees with it — and the removal failures that look like this one but are not.
 */
class SystemAppRemovalOutcomeTest {

    /**
     * Verbatim from emulator-5556 (stock API 37, `CE2A.260420.019`) at uid 2000, running
     * `pm uninstall -k --user 0 com.android.wallpaperbackup` and restoring the package afterwards.
     *
     * Case and bracket form are part of the fixture on purpose. The match is a substring test, so a
     * needle rewritten to drop `Failure [` would still pass here while one that reorders the words
     * inside would not — this constant is the only record of which of those two the real string is.
     */
    private val api37Refusal = "Failure [only root can delete system app for a particular user]"

    /**
     * The refusal is classified out of the *whole* output, not out of one line of it.
     *
     * Neither gateway hands this function a tidy single line. `Shizuku.execute` folds stdout and
     * stderr together and turns a thrown failure into `stackTraceToString()`; `Dhizuku.execute`
     * returns `stdout.ifBlank { stderr }` with whatever trailing newline `pm` left on it. Reading
     * only the first line is a real temptation — `ShizukuSystemGateway` deliberately does exactly
     * that, but *after* classifying, and only so a stack trace cannot reach a snackbar. If the
     * trimming ever moved ahead of the classification, an Android 17 user would drop into the
     * generic branch and be told nothing they can act on.
     */
    @Test
    fun `the refusal is recognised wherever in the output it lands`() {
        assertTrue("trailing newline", isRootOnlySystemAppRemoval("$api37Refusal\n"))
        assertTrue("surrounding whitespace", isRootOnlySystemAppRemoval("\n  $api37Refusal  \n"))
        assertTrue(
            "a line of shell noise ahead of it",
            isRootOnlySystemAppRemoval("WARNING: linker: unused DT entry\n$api37Refusal")
        )
    }

    /**
     * The message survives on the outcome whatever the exit code claimed.
     *
     * `pm uninstall` is not a reliable narrator in either direction — it can exit 0 having changed
     * nothing and non-zero having done the work — which is why both gateways verify by re-reading
     * the package instead of by `succeeded`. API 37 was measured exiting 1 here, but a build that
     * printed the same `Failure [...]` and exited 0 must not lose the one sentence that names the
     * cause. That only holds while [SystemAppRemovalOutcome.platformMessage] is populated
     * independently of [SystemAppRemovalOutcome.succeeded]; conditioning it on failure would look
     * like tidying and would silently gut this branch.
     */
    @Test
    fun `the outcome carries pm's words whether or not it called itself a success`() {
        val exitedNonZero =
            SystemAppRemovalOutcome(succeeded = false, exitCode = 1, platformMessage = api37Refusal)
        val exitedZero =
            SystemAppRemovalOutcome(succeeded = true, exitCode = 0, platformMessage = api37Refusal)

        assertTrue(isRootOnlySystemAppRemoval(exitedNonZero.platformMessage))
        assertTrue(isRootOnlySystemAppRemoval(exitedZero.platformMessage))
    }

    /**
     * Output that is only whitespace says as little as no output at all.
     *
     * It is reachable rather than theoretical: `Shizuku.freezeSystemAppForUser` maps a blank read to
     * null, but `DhizukuReflector`'s catch passes `e.message` straight through, and an exception
     * message can be blank. Matching on it would be worse than useless — the generic branch at least
     * reports the exit code, which is more than the "reflection is blocked or shell lacks
     * permissions" sentence this whole change exists to retire ever carried.
     */
    @Test
    fun `whitespace-only output is as silent as none at all`() {
        assertFalse(isRootOnlySystemAppRemoval("   "))
        assertFalse(isRootOnlySystemAppRemoval("\n"))
        assertFalse(isRootOnlySystemAppRemoval(" \t \n  \n"))
    }

    /**
     * The removal failures root cannot fix must not borrow root's sentence.
     *
     * `DELETE_FAILED_DEVICE_POLICY_MANAGER` and `DELETE_FAILED_OWNER_BLOCKED` are the ones the
     * Dhizuku path will actually meet, because its commands run as the device-owner app — and root
     * is not the answer to either. It is not even reachable: `uninstallFreezeFallbackAllowed`
     * answers false for [com.valhalla.thor.domain.model.PrivilegeMode.ROOT], so Root mode never
     * runs this rung at all. Pointing somebody at it costs them a privilege-mode switch and still
     * leaves them without the real reason.
     */
    @Test
    fun `the removal failures root cannot fix are not this refusal`() {
        listOf(
            "Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]",
            "Failure [DELETE_FAILED_OWNER_BLOCKED]",
            "Failure [DELETE_FAILED_APP_PINNED]",
            "Failure [DELETE_FAILED_USER_RESTRICTED]",
            "java.lang.SecurityException: Neither user 10214 nor current process has " +
                "android.permission.DELETE_PACKAGES.",
        ).forEach {
            assertFalse("misread as the root-only refusal: $it", isRootOnlySystemAppRemoval(it))
        }
    }

    /**
     * Case is not what makes the match.
     *
     * `ignoreCase = true` is deliberate and easy to mistake for noise: today's needle is AOSP's own,
     * printed in exactly one casing, so the flag looks droppable. It is there for the vendor build
     * that re-cases the string, which is precisely the kind of device this branch exists for —
     * every measured refusal in this area so far has been an OEM's, not AOSP's.
     */
    @Test
    fun `case is not what makes the match`() {
        assertTrue(
            isRootOnlySystemAppRemoval(
                "FAILURE [ONLY ROOT CAN DELETE SYSTEM APP FOR A PARTICULAR USER]"
            )
        )
    }

    /**
     * A *reworded* refusal falls to the generic branch instead of being guessed at, and that is the
     * intended trade.
     *
     * The classifier matches one fixed phrase; anything else — including a vendor sentence that
     * means the same thing — is unrecognised. What an unrecognised refusal loses is only the "switch
     * to Root mode" instruction, because `pm`'s own words ride out with it either way. Widening the
     * needle to "requires root" or "permission denied" would buy that instruction back at the price
     * of attaching it to failures root cannot fix, which is the more expensive mistake: it sends the
     * user somewhere useless and hides the real cause on arrival.
     */
    @Test
    fun `a reworded refusal falls to the generic branch rather than being guessed at`() {
        assertFalse(isRootOnlySystemAppRemoval("Failure [only root can delete a system app]"))
        assertFalse(isRootOnlySystemAppRemoval("Failure [system app removal requires root]"))
        assertFalse(isRootOnlySystemAppRemoval("Failure [root required]"))
    }

    /**
     * `pm`'s one useful line survives whatever the helper wrapped around it.
     *
     * Both `execute` implementations fold a thrown failure into `stackTraceToString()`, and both
     * gateways paste [displayLine] into a string a `MainViewModel` renders verbatim in a snackbar —
     * so "the whole message" and "the line worth reading" are not the same string, and only the
     * second may reach a user. The stack-trace case is the one that actually bites: a Dhizuku binder
     * killed by an OEM's background management is the ordinary way the freeze reaches this branch.
     */
    @Test
    fun `display trims to the first line that says something`() {
        assertEquals(
            api37Refusal,
            SystemAppRemovalOutcome(false, 1, "$api37Refusal\n").displayLine()
        )
        assertEquals(
            api37Refusal,
            SystemAppRemovalOutcome(false, 1, "\n\n  $api37Refusal  \nmore output\n").displayLine()
        )
        assertEquals(
            "java.lang.SecurityException: Cannot disable system packages.",
            SystemAppRemovalOutcome(
                succeeded = false,
                exitCode = -1,
                platformMessage = "java.lang.SecurityException: Cannot disable system packages.\n" +
                    "\tat android.os.Parcel.createExceptionOrNull(Parcel.java:3057)\n" +
                    "\tat android.os.Parcel.createException(Parcel.java:3041)\n",
            ).displayLine()
        )
    }

    /**
     * With nothing to quote, the exit code is what is left — and it is still more than the sentence
     * this change retired ("reflection is blocked or shell lacks permissions") ever carried.
     *
     * Whitespace-only counts as nothing: `DhizukuReflector`'s catch passes `e.message` straight
     * through and an exception message can be blank, which is the same reachability that makes the
     * blank case worth pinning for the classifier above.
     */
    @Test
    fun `an outcome with nothing to quote falls back to the exit code`() {
        assertEquals("exit code 1", SystemAppRemovalOutcome(false, 1, null).displayLine())
        assertEquals("exit code -1", SystemAppRemovalOutcome(false, -1, "   \n\t\n").displayLine())
    }
}
