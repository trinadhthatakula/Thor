// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

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
 * [block] is run through [coroutineScope] rather than called directly, and that is what makes the
 * guard cover a coroutine started with `launch` *inside* it. Called directly, a child's failure is
 * reported to `viewModelScope` instead: the parent would see only the [CancellationException] that
 * the child's failure raises in it, rethrow it below, and the real exception would go on to
 * `viewModelScope`'s `SupervisorJob`, which declines it, and from there to the thread's default
 * handler — past [onFailure], and past the two watchers that start their progress collector with an
 * inner `launch` and document `onFailure` as covering "either collector". Neither of those two
 * children can throw today, so this is a latent hole rather than a live one; it is closed here
 * because the comments at those call sites promise it is closed, and because the next `launch` added
 * inside a `block` should not have to know this.
 *
 * There is deliberately no log line here. `android.util.Log` is not mocked in this module's JVM
 * tests (no `testOptions.unitTests.isReturnDefaultValues`), so a log call would throw inside the very
 * guard that exists to stop throws, in every test that drives one of these view models.
 *
 * That ban is on *this file* touching `android.util.Log`, not on a call site logging from its
 * [onFailure]. The freezer handlers all open with `Logger.e`, which is safe for a reason this file
 * cannot rely on for itself: `Logger` gates every level on `Logger.isDebug`, a `var` defaulting to
 * `false` that only `ThorApplication` and `ThorRootService` ever set, so under JVM tests it stays
 * false and `Log` is never reached. A bare `Log.e` at a call site would be the same hazard as one
 * here.
 *
 * [CancellationException] is rethrown rather than reported: it is structured concurrency's own
 * signal, raised when the view model is cleared or when [ViewModel] scope children are cancelled, and
 * swallowing it would break cancellation rather than report a failure.
 *
 * [context] exists because the freezer watchlist call sites this guard was extended to cover in
 * `fix/freezer-bookkeeping-crashes` are `viewModelScope.launch(ioDispatcher)`, not bare launches.
 * Without it, adopting the guard would silently move their Room writes and privileged shell calls
 * onto `Dispatchers.Main.immediate` — a correctness regression bought with a crash fix, and one
 * nothing would have failed on, because Room's own suspend DAO functions dispatch internally and
 * would keep working. It is first in the list to match [launch]'s own signature, and defaults to
 * [EmptyCoroutineContext] so the backup, export and passphrase call sites are unchanged.
 */
internal fun ViewModel.launchGuarded(
    context: CoroutineContext = EmptyCoroutineContext,
    onFailure: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job = viewModelScope.launch(context) {
    try {
        coroutineScope(block)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        onFailure(failure)
    }
}
