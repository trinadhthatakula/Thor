// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TarOutcomeTest {

    @Test
    fun `a clean exit is a plain success`() {
        assertEquals(TarOutcome.Succeeded, classifyTarExit(exitCode = 0, stagedBytes = 4096L))
    }

    @Test
    fun `exit 1 with bytes on disk is a warning, not a failure`() {
        // §7.3. GNU-family tar returns 1 for "a file changed while being read", which happens on
        // nearly every live app directory even after a force-stop. Failing here would fail most real
        // backups; the archive is complete enough to restore, and the header says so.
        val outcome = classifyTarExit(exitCode = 1, stagedBytes = 4096L)

        assertTrue(outcome.toString(), outcome is TarOutcome.SucceededWithWarning)
    }

    @Test
    fun `exit 1 with an empty archive is a failure`() {
        // Nothing was written, so there is nothing to warn about — this is tar giving up.
        assertTrue(classifyTarExit(exitCode = 1, stagedBytes = 0L) is TarOutcome.Failed)
    }

    @Test
    fun `exit 2 is a failure even with bytes on disk`() {
        // 2 is tar's fatal class. A partially written tar is worse than none: it would restore a
        // truncated directory tree over the app's real data.
        assertTrue(classifyTarExit(exitCode = 2, stagedBytes = 999L) is TarOutcome.Failed)
    }

    @Test
    fun `a folded exception is a failure, never a warning`() {
        // `RootSystemGateway.execute()` folds a *throw* into `-1 to stackTraceToString()`. A negative
        // code is Thor's own stack trace, not a tar verdict — and `-1` is not `> 1`, so a rule
        // written as "exitCode > 1 fails" would classify it as a success with a warning.
        assertTrue(classifyTarExit(exitCode = -1, stagedBytes = 4096L) is TarOutcome.Failed)
    }

    @Test
    fun `a warning carries text a header can hold`() {
        val outcome = classifyTarExit(exitCode = 1, stagedBytes = 4096L)

        val warning = (outcome as TarOutcome.SucceededWithWarning).warning
        assertTrue(warning, warning.isNotBlank())
    }
}
