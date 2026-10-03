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
        // Archive- and sweep-owned shells configure FLAG_MOUNT_MASTER independently because reading
        // another package's private data or clearing its cache requires the global namespace.
        //
        // Odin 1.1.0 publishes a shell only after its handshake and initializers complete under
        // the builder deadline. Keep the interactive acquisition budget aligned with owned shells.
        // An expired acquisition is not proof of root revocation: the shared availability model
        // retains TIMED_OUT/FAILED separately from a confirmed NON_ROOT observation. Authorization
        // is managed by the installed root manager; KernelSU forks can require a manual app grant.
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setTimeout(SHELL_INIT_TIMEOUT_SECONDS)
        )
    }

    /** Ten-second startup budget, also used by Thor's owned-shell factory. */
    private const val SHELL_INIT_TIMEOUT_SECONDS = 10L
}
