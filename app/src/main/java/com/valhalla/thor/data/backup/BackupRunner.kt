// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup

import android.content.Context
import android.os.storage.StorageManager
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.usecase.BackupAppsUseCase
import com.valhalla.thor.domain.usecase.BackupProgress
import com.valhalla.thor.domain.usecase.BackupRunResult
import com.valhalla.thor.domain.usecase.StagingGate
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File
import java.io.IOException

/**
 * Owner of a multi-app export run.
 *
 * The point of the @Single is the scope: exporting 200 apps outlives the sheet that started it,
 * the ViewModel behind that sheet and often the Activity behind *that*. A process-lifetime
 * [SupervisorJob] scope survives all three **without a foreground service**, which is the same shape
 * `the legacy bulk executor` already uses for exactly this problem.
 *
 * The reason given here used to be that Thor declares no `FOREGROUND_SERVICE` permission. **That is
 * false, and was false when it was written.** The merged manifest carries the base permission from
 * `work-runtime`'s own manifest, Thor declares `FOREGROUND_SERVICE_DATA_SYNC` explicitly, and it
 * overlays `androidx.work.impl.foreground.SystemForegroundService` with `android:foregroundServiceType`
 * `dataSync` — because the archive job seam next door runs as a foreground service and would throw
 * `MissingForegroundServiceTypeException` without it.
 *
 * What survives is the rest of the argument, which never needed that claim: a `dataSync` FSU is
 * mandatory-typed on API 34+, time-capped on 35+, and cannot be started from the background on 31+
 * without risking `ForegroundServiceStartNotAllowedException`. A process-lifetime scope has none of
 * those failure modes. It also does not have the FSU's wakelock or process priority — the trade this
 * class makes, and the reason an archive capture makes the opposite one.
 */
@Single
class BackupRunner(
    private val context: Context,
    private val backupAppsUseCase: BackupAppsUseCase,
    @Named("io") private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)

    // Instance-scoped, NOT per run: when a second start() replaces an in-flight run, workers the
    // cancelled run abandoned may still be streaming a multi-gigabyte copy through the content
    // resolver, and a fresh Semaphore would let the replacement stage that many more on top of
    // them. Sharing it caps in-flight staging across generations, which is what actually bounds
    // cacheDir.
    //
    // Two, not five: each permit is a full staged copy of an app, and every worker is streaming
    // to the same volume the staging lives on. More permits multiply the cache peak for very
    // little overlap.
    private val gate = StagingGate(MAX_STAGED_APPS)

    private val _progress = MutableStateFlow<BackupProgress?>(null)

    /** Progress of the run in flight; null when idle. */
    val progress: StateFlow<BackupProgress?> = _progress.asStateFlow()

    // replay = 1 so a completion is not lost to a subscriber that has not attached yet. A run can
    // outlive the UI that started it by design, so "nobody is collecting right now" is the normal
    // case here, not the edge case — and a replay = 0 SharedFlow drops silently in that state
    // (tryEmit still returns true), which is how the freezer tile lost completions before.
    //
    // DROP_OLDEST so tryEmit never fails and never suspends the run: only the latest completion
    // is worth showing, and a slow collector must not be able to hold a batch open.
    private val _completions = MutableSharedFlow<BackupRunResult>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Outcome of the last run to finish, cancel or be rejected.
     *
     * Replayed, so a subscriber attaching late still sees it — which means the subscriber has to
     * say when it has been *shown*. See [consumeCompletion].
     */
    val completions: SharedFlow<BackupRunResult> = _completions.asSharedFlow()

    /**
     * One run's handle plus the one fact the run itself cannot observe: that a later `start()`
     * replaced it. A superseded run is cancelled exactly like an explicit cancel, but must not
     * *report* like one — the replacement is about to report for the same UI, and two outcome
     * messages for one tap is worse than none.
     */
    private class Run {
        @Volatile
        var superseded = false

        // Assigned immediately after construction, before the coroutine's first suspension point
        // can read it. Not a constructor parameter because the coroutine body needs the Run.
        lateinit var job: Deferred<BackupRunResult?>
    }

    private var activeRun: Run? = null

    /**
     * Start a run, cancelling and replacing any run already in flight.
     *
     * Cancel-and-replace rather than the same-op coalescing `the legacy bulk executor` does: two backup
     * requests are not interchangeable — the second carries a different selection — so returning
     * the first would export the wrong apps.
     *
     * The handoff runs under [NonCancellable] so the chain actually serialises. Without it, three
     * taps in a row leave the *first* run still unwinding: run C cancels run B, B is parked in
     * `A.cancelAndJoin()`, B's own cancellation makes that join throw, and B unwinds without ever
     * waiting for A — so A and C stage into the same cache concurrently. Under `NonCancellable`
     * B's wait for A completes regardless, so waiting for B transitively waits for A. That wait is
     * unbounded by construction, which is why the builder's copy and zip loops check for
     * cancellation per chunk: promptness has to come from the work, not from a timeout here.
     *
     * Returns the run so a caller that *can* show UI does not have to race [completions] — a short
     * run can finish before a collector attaches, and `await()` has no such window. Null means the
     * run threw something unexpected; [completions] carries every other outcome, that one
     * included.
     */
    @Synchronized
    fun start(apps: List<AppInfo>): Deferred<BackupRunResult?> {
        val previous = activeRun
        previous?.superseded = true

        // Published before the coroutine starts so a UI that opens immediately after the tap sees
        // "0 of N" rather than an idle null while the replaced run is still unwinding.
        _progress.value = BackupProgress(completed = 0, saved = 0, total = apps.size, current = null)

        val run = Run()
        run.job = scope.async {
            // Read inside the coroutine, not from the shared _progress: a replacement has already
            // published its own zeroed progress by the time this run unwinds, so reporting from
            // the shared flow tells the user 0 apps were saved when the folder holds five.
            // The use case joins every worker before it throws, so the last write is visible here.
            var lastProgress: BackupProgress? = null
            try {
                withContext(NonCancellable) { previous?.job?.cancelAndJoin() }
                backupAppsUseCase(
                    apps = apps,
                    // The two Android-shaped inputs the use case needs; resolved here so the use
                    // case itself stays free of Context.
                    stagingRoot = context.cacheDir,
                    usableStagingBytes = usableStagingBytes(context.cacheDir),
                    gate = gate,
                    onProgress = {
                        lastProgress = it
                        _progress.value = it
                    },
                ).also { _completions.tryEmit(it) }
            } catch (e: CancellationException) {
                // Emit before rethrowing: the folder now holds however many bundles finished, and
                // an explicit cancel with no replacement has no other run coming to report it.
                // tryEmit neither suspends nor throws, so it is safe on an already-cancelled
                // coroutine.
                if (!run.superseded) {
                    _completions.tryEmit(
                        BackupRunResult.Cancelled(
                            saved = lastProgress?.saved ?: 0,
                            total = apps.size,
                        )
                    )
                }
                throw e
            } catch (e: Exception) {
                // Nothing awaits this Deferred in the common case, so an escaped exception would
                // sit unreported forever — SupervisorJob stops it cancelling siblings, not being
                // lost. Emitting Failed is what keeps it from also being invisible: the progress
                // indicator is about to disappear either way, and an indicator that vanishes with
                // nothing said reads as success.
                Logger.e(TAG, "backup run failed unexpectedly", e)
                _completions.tryEmit(
                    BackupRunResult.Failed(saved = lastProgress?.saved ?: 0, total = apps.size)
                )
                null
            } finally {
                // Clear only if this is still the active run. A replacement has already published
                // its own progress and owns the slot; nulling it here would blank a live run.
                // The identity check is safe against start() racing it because start() holds the
                // monitor until `activeRun = run` below.
                synchronized(this@BackupRunner) {
                    if (activeRun === run) {
                        _progress.value = null
                        // Drop the handle too, or a completed Deferred is retained for the
                        // process lifetime.
                        activeRun = null
                    }
                }
            }
        }
        activeRun = run
        return run.job
    }

    /**
     * Cancel the run in flight, if any.
     *
     * Bundles already written stay written — the run's manifest is rewritten on the way out so the
     * folder still describes what is in it.
     */
    @Synchronized
    fun cancel() {
        activeRun?.job?.cancel()
    }

    /**
     * Drop the replayed completion once it has been shown.
     *
     * [completions] replays so a run that outlives its UI can still report, but that same replay
     * makes the outcome sticky: every ViewModel created afterwards — a rotation, a
     * back-and-forward, a process restart — collects the same finished run and announces it again.
     * The runner has to own the acknowledgement rather than the collector, because the collector
     * is exactly the thing that keeps being recreated with no memory of what it already showed.
     *
     * `resetReplayCache` is the opt-in bit of `MutableSharedFlow`; a conflated `Channel` would be
     * stable API and give exactly-once delivery for free, but it would also throw away the reason
     * the replay is here — see the buffer comment above, where a `replay = 0` flow silently
     * dropping to zero subscribers is a bug this codebase has already shipped once.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeCompletion() {
        _completions.resetReplayCache()
    }

    /**
     * Free space the staging cache can actually count on, in bytes, or 0 when it cannot be
     * measured — the use case fails open on that.
     *
     * `File.usableSpace` is the obvious answer and the wrong one on Android: it ignores the
     * clearable cache the platform would evict to satisfy a write, so it under-reports and would
     * refuse batches the device could comfortably run. `getAllocatableBytes` is the number the
     * platform itself would honour.
     *
     * Broad catch on purpose. This is a courtesy measurement whose only job is to turn a doomed
     * run into an explanation up front; nothing it can throw — a documented [IOException], an
     * unmounted volume, an OEM `StorageManager` that disagrees with AOSP — is worth failing an
     * export the device might well have completed. Every such failure reads as "unknown", and
     * unknown fails open.
     */
    private fun usableStagingBytes(dir: File): Long = try {
        val storage = context.getSystemService(StorageManager::class.java)
        storage?.getAllocatableBytes(storage.getUuidForPath(dir)) ?: 0
    } catch (e: IOException) {
        Logger.w(TAG, "could not measure free space for ${dir.path}: ${e.message}")
        0
    } catch (e: Exception) {
        Logger.w(TAG, "storage service refused to measure ${dir.path}: ${e.message}")
        0
    }

    private companion object {
        const val TAG = "BackupRunner"
        const val MAX_STAGED_APPS = 2
    }
}
