// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.core

import com.valhalla.superuser.Shell

/**
 * Centralized configuration for the Root Shell.
 * Call [init] in your Application.onCreate().
 */
object ThorShellConfig {

    fun init() {
        // Odin's verbose mode logs raw commands and collected output process-wide. Keep it disabled
        // for every build because interactive and owned background shells can execute concurrently.
        Shell.enableVerboseLogging = false

        // Keep only the process-wide interactive MainShell plain. Odin's BuilderImpl falls back from
        // `su --mount-master` to plain `su`, but a Root manager that leaves the first attempt hanging
        // consumes the full shell-check timeout before that fallback starts. Interactive actions do
        // not need the global mount namespace, so they should not pay that acquisition delay.
        // Archive-owned shells configure FLAG_MOUNT_MASTER independently because reading another
        // package's private data does require the global namespace; sweep-owned shells stay plain.
        //
        // setTimeout aligns the builder's shell-check budget with Odin's own root probe, which gives
        // up at RealShellRepository.SHELL_INIT_TIMEOUT_MS = 10s; the 20s BuilderImpl default would
        // leave a `su` process and a blocked executor thread alive for ten seconds after the probe
        // has already reported "no root". The budget covers the time the *user* spends answering the
        // superuser dialog, so the trade is real: a grant given between 10s and 20s now has its
        // shell destroyed instead of cached, and costs one more prompt on the next probe.
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(SHELL_INIT_TIMEOUT_SECONDS)
        )
    }

    /** Matches Odin's `RealShellRepository.SHELL_INIT_TIMEOUT_MS`; see [init]. */
    private const val SHELL_INIT_TIMEOUT_SECONDS = 10L
}
