// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * `viewModelScope.launch` that reports a throw instead of taking the process with it.
 *
 * A bare `viewModelScope.launch` has no exception handler, so anything thrown inside it reaches the
 * thread's default handler and kills the app. Every coroutine in the backup and restore view models
 * calls into a privileged shell, a DataStore, a document provider or WorkManager, and each of those
 * can fail for reasons the user is entitled to be told about — one of them, the passphrase vault, has
 * a string in `strings_backup.xml` saying in as many words that its failure is survivable.
 *
 * [onFailure] is the reporting hook and is the reason this is not a `CoroutineExceptionHandler` on
 * the scope: a handler would turn all of these into one silent no-op, and what this feature is short
 * of is telling the user *more*, not less. Each call site passes the state change that leaves the
 * screen usable — a refusal panel, a cleared spinner, a failure banner — and only passes nothing
 * where there is genuinely nothing to say.
 *
 * There is deliberately no log line here. `android.util.Log` is not mocked in this module's JVM
 * tests (no `testOptions.unitTests.isReturnDefaultValues`), so a log call would throw inside the very
 * guard that exists to stop throws, in every test that drives one of these view models.
 *
 * [CancellationException] is rethrown rather than reported: it is structured concurrency's own
 * signal, raised when the view model is cleared or when [ViewModel] scope children are cancelled, and
 * swallowing it would break cancellation rather than report a failure.
 */
internal fun ViewModel.launchGuarded(
    onFailure: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job = viewModelScope.launch {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        onFailure(failure)
    }
}
