// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** What one `tar` invocation amounted to. Three outcomes, because §7.3 needs the middle one. */
sealed interface TarOutcome {
    data object Succeeded : TarOutcome
    data class SucceededWithWarning(val warning: String) : TarOutcome
    data class Failed(val reason: String) : TarOutcome
}

/**
 * Decide what a `tar` exit code means, given how much it actually wrote.
 *
 * The exit code alone is not enough in either direction:
 *
 * - **1 is usually not a failure.** GNU-family tar uses it for "a file changed while being read",
 *   which live app data does constantly — even after a force-stop, because the system keeps touching
 *   an app's directories. §7.3 records it as a warning in the header and carries on.
 * - **A negative code is not a tar code at all.** `RootSystemGateway.execute()` folds a *throw* into
 *   `-1 to stackTraceToString()`, so `-1` means Thor's own exception. Note that any rule phrased as
 *   "`exitCode > 1` fails" silently classifies `-1` as a *success with a warning*; the check is
 *   therefore written as an explicit `0` / `1` / everything-else.
 *
 * @param stagedBytes the length of the file `tar` was told to write, read *after* it exited. Zero
 *   means nothing landed, which turns the exit-1 warning back into a failure.
 */
fun classifyTarExit(exitCode: Int, stagedBytes: Long): TarOutcome = when (exitCode) {
    0 -> TarOutcome.Succeeded
    1 -> if (stagedBytes > 0L) {
        TarOutcome.SucceededWithWarning(
            "tar reported files that changed while being read; the archive was written anyway"
        )
    } else {
        TarOutcome.Failed("tar exited 1 and wrote nothing")
    }

    else -> TarOutcome.Failed("tar exited $exitCode")
}
