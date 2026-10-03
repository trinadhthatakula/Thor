// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.InstallSessionUnresolved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The registered ticket owns retained leases; this function owns only the caller's wait. */
internal suspend fun trackInstallSessionSubmission(
    abandon: () -> Unit,
    onSubmissionFailure: suspend (Throwable) -> Unit,
    onCleanupFailure: (Throwable) -> Unit,
    releaseUnsubmitted: suspend () -> Unit,
    detach: () -> Unit,
    awaitCompletion: suspend () -> Unit,
    submit: suspend (commit: (action: () -> Unit) -> Unit) -> Unit,
): Boolean {
    var attempted = false
    var submitted = false
    try {
        try {
            submit { action ->
                // There is no safe abandonment/fallback proof after crossing the commit boundary,
                // including a Binder exception whose delivery to PackageInstaller is unknown.
                attempted = true
                action()
                submitted = true
            }
        } catch (failure: Throwable) {
            if (!attempted) runCatching(abandon)
            if (failure is CancellationException) throw failure
            when {
                submitted -> onCleanupFailure(failure)
                attempted -> throw InstallSessionUnresolved(
                    "Thor could not confirm install submission; conflicting operations remain blocked",
                    failure,
                )
                else -> onSubmissionFailure(failure)
            }
        }
        if (submitted) awaitCompletion()
        currentCoroutineContext().ensureActive()
        return submitted
    } finally {
        detach()
        // Only an unattempted session can retire without its matching terminal callback.
        if (!attempted) withContext(NonCancellable) { releaseUnsubmitted() }
    }
}
