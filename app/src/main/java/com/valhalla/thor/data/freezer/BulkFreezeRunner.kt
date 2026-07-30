// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.data.source.local.UadHelper
import com.valhalla.thor.data.source.local.UadSnapshot
import com.valhalla.thor.domain.model.BulkAction
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.domain.model.BulkScope
import com.valhalla.thor.domain.model.bulkActionFor
import com.valhalla.thor.domain.model.freezableCandidates
import com.valhalla.thor.domain.repository.FreezeProfileRepository
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs bulk freeze/unfreeze for every surface that needs it — the QS tile, the launcher
 * Freeze-all / Unfreeze-all shortcuts, and the freeze profiles sheet.
 *
 * As a @Single it owns a process-lifetime scope, which is the point: a QS shade collapse
 * destroys the TileService, and pinning the batch to a service-lifetime scope would leave a
 * partial freeze. Because the scope lives here rather than in a companion object on the
 * service, nothing retains the destroyed service.
 *
 * Profiles route through here rather than freezing directly, and that is not a stylistic
 * preference: [targetsFor] is where the [com.valhalla.thor.domain.model.FreezeTier] block is
 * applied to a *list* of packages. A fourth surface that called `setAppDisabled` itself would
 * be the one bulk path able to freeze what every dialog in the app refuses to offer a confirm
 * button for.
 */
@Single
class BulkFreezeRunner(
    private val freezerRepository: FreezerRepository,
    private val freezeProfileRepository: FreezeProfileRepository,
    private val manageAppUseCase: ManageAppUseCase,
    private val preferenceRepository: PreferenceRepository,
    private val privilegeManager: PrivilegeManager,
    private val stateReader: AppFreezeStateReader,
    private val uadHelper: UadHelper,
    private val notifier: BulkResultNotifier,
    @Named("io") private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val _freezableCount = MutableStateFlow<Int?>(null)

    /**
     * How many watchlist apps could be **frozen** right now; null until the first sweep lands.
     *
     * Freeze-specific by definition, not by accident. This used to be "candidates for whichever
     * op swept last", which meant an UNFREEZE run left it holding the unfreeze count: after
     * Unfreeze-all it published 0 — "nothing to freeze" — at the exact moment every restored app
     * had become freezable. The tile is freeze-only (D1), so one op-agnostic count had no
     * consistent meaning for the only thing that reads it.
     */
    val freezableCount: StateFlow<Int?> = _freezableCount.asStateFlow()

    private val _lastResult = MutableStateFlow<BulkResult?>(null)

    /** Outcome of the last completed run, consumed once by whoever displays it. */
    val lastResult: StateFlow<BulkResult?> = _lastResult.asStateFlow()

    // replay = 1 so a completion is not lost to a subscriber that has not attached yet.
    // extraBufferCapacity alone only buffers for an *already-subscribed but slow* collector; with
    // zero subscribers tryEmit still returns true and discards. FreezerShortcutManager subscribes
    // from `scope.launch` in its init, which dispatches asynchronously, so there is a window —
    // narrow, but the correctness of the icon rebuild should not rest on it losing a race.
    //
    // Replaying to a late subscriber is harmless here precisely because subscribers re-read live
    // package state instead of trusting the emitted value: a replayed completion costs one extra
    // idempotent rebuild, never a wrong icon.
    //
    // extraBufferCapacity + DROP_OLDEST so tryEmit never fails and never suspends the run: a
    // slow subscriber (the icon rebuild decodes one bitmap per pinned app) must not be able to
    // hold a batch open, and coalescing a burst down to "rebuild once more afterwards" is
    // correct for the same reason.
    private val _completions = MutableSharedFlow<BulkOp>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Emits once per run that actually touched packages — the seam for side effects that must
     * follow *any* bulk run, whichever surface started it.
     *
     * This exists because side effects bolted to one caller are unreachable from the other.
     * The pinned-shortcut icon rebuild used to hang off `FreezerShortcutManager.runBulk`, so a
     * freeze started from the QS tile — which calls [launch] directly, having no reason to know
     * shortcuts exist — froze the apps and left every pinned icon in full colour. Device
     * evidence: `dumpsys shortcut` showed the publisher's call count and stored icon bitmap
     * unchanged across a tile freeze that did set `enabled=3`.
     *
     * Not emitted for a no-op run ([run] returned null: no privilege, or nothing to act on) —
     * nothing changed, so nothing needs rebuilding. Not emitted for a cancelled run either;
     * the conflicting op that replaced it emits its own completion, and subscribers rebuild
     * from live state, so the later emission subsumes the lost one.
     */
    val completions: SharedFlow<BulkOp> = _completions.asSharedFlow()

    private val _runningRequests = MutableStateFlow<List<BulkRequest>>(emptyList())

    /**
     * Every request in flight, oldest first: the one actually touching packages, then whatever
     * has been serialized behind it. Empty when nothing is running.
     *
     * A *list* rather than a single slot because a same-op run of a different scope queues behind
     * its predecessor instead of replacing it, so "what is running" genuinely has more than one
     * answer. One slot forced the newer launch to overwrite the older — and the older is the one
     * still mutating packages. Running a freeze profile during a watchlist freeze therefore made
     * the QS tile paint itself idle (and surface a stale "Froze N apps" subtitle) while the
     * watchlist freeze it started was still going.
     *
     * Requests rather than a bare Boolean for the same reason [freezableCount] is freeze-specific:
     * a shared "is running" flag painted the freeze-only tile as WORKING while a background
     * Unfreeze-all shortcut ran. The *scope* is carried too, so the tile can tell "the watchlist
     * is being frozen" from "some profile is being frozen" — only the first is the tile's own
     * work — and the profiles sheet can spin every row it has queued rather than only the last.
     */
    val runningRequests: StateFlow<List<BulkRequest>> = _runningRequests.asStateFlow()

    /**
     * A launched run and the request that started it, kept together so [launch] can tell a
     * coalescable repeat from a genuinely different run.
     *
     * B-1: keyed on the request, not the op alone. The op was enough while the watchlist was the
     * only target list; with profiles it would coalesce "freeze profile A" and "freeze profile B"
     * into one run over A.
     */
    private class InFlight(val request: BulkRequest, val job: Deferred<BulkResult?>)

    // Every run that has not settled yet, oldest first — the single source of truth for both
    // [runningRequests] and the coalescing key, whose newest launch is the last entry. Nearly
    // always at most one entry, but a same-op run of a different scope *queues behind* its
    // predecessor instead of replacing it. A conflicting op has to cancel the whole chain:
    // cancelling the tail alone would only stop the job still waiting to start and leave the one
    // actually mutating packages running alongside the replacement — precisely the
    // FREEZE-versus-UNFREEZE overlap the launch KDoc bounds everywhere else.
    private val unsettled = mutableListOf<InFlight>()

    // Instance-scoped, NOT per run. A per-run Semaphore bounds one generation only: when a
    // conflicting op replaces an in-flight batch, workers abandoned by that batch may still be
    // parked in a blocking binder call, and a fresh Semaphore(5) would let five more start on
    // top of them. Sharing it caps total in-flight workers across generations.
    private val semaphore = Semaphore(MAX_CONCURRENT)

    /**
     * Re-derive how many watchlist apps could be frozen and publish it to [freezableCount].
     *
     * Always sweeps for [BulkOp.FREEZE], whatever op prompted the sweep — see [freezableCount]
     * for why the published count is freeze-specific.
     *
     * B-4: confined to [io] so callers on the main thread (e.g. Task 7's tile) do not run
     * N PackageManager binder calls on the UI thread.
     */
    suspend fun refreshFreezableCount() {
        withContext(io) {
            _freezableCount.value = targetsFor(BulkRequest(BulkOp.FREEZE)).size
        }
    }

    /**
     * The packages [request] would act on right now — the one place any surface asks that.
     *
     * Shared by [refreshFreezableCount] and [run] because a count derived differently from the
     * batch it describes is a tile that lies: it would offer "Freeze 3" and then freeze two, or
     * sit READY over a watchlist with nothing left it is allowed to touch.
     *
     * The [UadSnapshot] is taken once per call, not per package. FREEZE gets a real one so the
     * blocked tier — unsafe system apps, and every system app when the UAD list will not load —
     * is excluded exactly as `MainViewModel.performCountedFreeze`, `AutoFreezeManager` and the
     * in-app freeze dialog exclude it; before this, the tile was the one bulk path that would
     * happily freeze what the dialog refuses to render a confirm button for. A profile is
     * filtered by the same call, so putting a blocked app in one cannot freeze it either.
     *
     * UNFREEZE gets [UadSnapshot.UNFILTERED] on purpose. Unfreezing is how a user escapes a bad
     * freeze, so it must never be gated on the same list: if `uad_lists.json` failed to load,
     * a filtered unfreeze would classify every system app as blocked and strand them frozen.
     */
    private suspend fun targetsFor(request: BulkRequest): List<String> {
        val members = when (val scope = request.scope) {
            BulkScope.Watchlist -> freezerRepository.getAllPackageNames()
            // A deleted profile reads as empty, which run() turns into a no-op rather than an
            // error — the sheet row is already gone by then, so there is nothing to report to.
            is BulkScope.Profile -> freezeProfileRepository.packagesOf(scope.id)
        }
        if (members.isEmpty()) return emptyList()
        val uad = if (request.op == BulkOp.FREEZE) uadHelper.snapshot() else UadSnapshot.UNFILTERED
        return freezableCandidates(members, request.op) { stateReader.candidateOf(it, uad) }
    }

    /**
     * [refreshFreezableCount] with its own failure handling, for the fire-and-forget sweep the
     * post-run finally abandons on timeout. It runs on [scope], so an escaping exception would
     * reach Android's default uncaught handler and kill the process — `SupervisorJob` stops a
     * failing child from cancelling its siblings, not from being reported (B-2). Nothing awaits
     * this job, so nothing else can catch it.
     */
    private suspend fun sweepFreezableCount() {
        try {
            refreshFreezableCount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("BulkFreezeRunner", "post-run candidate sweep failed", e)
        }
    }

    /**
     * Start a bulk run and return it. Returns the in-flight run instead of starting a second one
     * — the pre-rework tile spawned a fresh unbounded batch over the same packages on every tap.
     *
     * Returning the run rather than making callers watch [runningRequests] is deliberate: a fast
     * run can leave that list before an observer starts collecting, and `await()` has no such
     * window.
     * The awaited value is the [BulkResult], or null when the run was a no-op (no privilege, or
     * nothing to act on) — which is what lets a caller that *is* allowed to show UI, such as
     * `FreezerLaunchActivity`, report an outcome the notifier may have had to drop.
     *
     * Same-request coalescing: a second tap for the same op *over the same scope* returns the
     * existing run — any unsettled run matching it, not merely the most recent launch, so a repeat
     * still coalesces once something is queued behind it. Keyed on the whole [BulkRequest], not the
     * op: "freeze profile A" and "freeze profile B" are both FREEZE but must not collapse into one
     * run over A.
     *
     * Same-op, different scope: the new run **waits** for the previous one rather than
     * cancelling it. Two freezes do not contradict each other, so cancelling A to start B would
     * leave A half-frozen with nothing said about it — the user tapped both and is owed both.
     * The wait is bounded by the outgoing run's own deadline and post-run sweep.
     *
     * Conflicting-op replacement: the new job cancels **every** unsettled run — the whole
     * serialized chain, not only its tail — and joins each before touching any package. The
     * distinction matters because the tail of a chain is the run that has not started yet; the
     * head is the one holding packages open. That handoff is **bounded, not absolute**. What
     * actually holds:
     *
     * - the outgoing run gets [CANCEL_GRACE_MS] to unwind, so every worker that observes
     *   cancellation is finished before the replacement starts;
     * - [semaphore] is an instance field, so the number of workers in flight is capped at
     *   [MAX_CONCURRENT] **across** generations, not per run;
     * - a worker parked in a blocking binder call still can outlive the grace period — the
     *   Shizuku and Dhizuku reflection paths do not observe cancellation — and mutate its
     *   package after the replacement batch has begun.
     *
     * So the overlap is bounded in worker count and in expected duration; it is not
     * eliminated. This matters because FREEZE and UNFREEZE are not idempotent with respect to
     * each other: a straggling FREEZE worker can re-disable a package that the replacing
     * UNFREEZE batch already enabled, and that package is not in the unfreeze target list
     * either (it was not frozen when the candidates were computed).
     */
    fun launch(op: BulkOp): Deferred<BulkResult?> = launch(BulkRequest(op))

    @Synchronized
    fun launch(request: BulkRequest): Deferred<BulkResult?> {
        val newest = unsettled.lastOrNull()
        // Same-request coalescing: return the in-flight run unchanged. Matched against the whole
        // chain rather than its tail, because once one run is queued behind another the request
        // being repeated is often the *head* — a watchlist freeze from the tile, with a profile
        // freeze already queued behind it, would otherwise not match the tail, take the serialize
        // path instead, and enqueue a second full watchlist batch that re-acts on every package and
        // re-posts a result after the first one already reported. That is precisely the double-act
        // this method promises cannot happen.
        //
        // Only while the chain is uniform in op, though. A conflicting-op launch cancels every
        // entry from inside its own coroutine body, so between that launch and the cancellation
        // landing, a doomed entry still reports `isActive` — handing its job back would coalesce a
        // caller onto a run that is about to be cancelled, and its freeze would never happen. When
        // every entry shares the incoming op there has been no such launch: it would still be in
        // the chain itself, carrying the op that fails this check.
        if (unsettled.all { it.request.op == request.op }) {
            unsettled.firstOrNull { it.job.isActive && it.request == request }
                ?.let { return it.job }
        }

        // Handoff: capture the previous run BEFORE this one joins the chain, along with whether
        // it is a run we may cancel. The new job's first act is to settle it, so it will not
        // touch any package until the previous one has fully unwound (run body AND finally
        // sweep). The previous request stays published throughout, because it is retired from
        // [unsettled] by its own completion rather than by whoever launched next.
        val previous = newest?.job
        // Same op, different scope → serialize instead of replace. See the KDoc: cancelling a
        // freeze to start another freeze abandons the first one half-done.
        val cancelPrevious = newest?.request?.op != request.op
        // Snapshot the whole chain under the monitor, not just the tail. Taken before this job
        // joins [unsettled], so it can never contain the job about to await it.
        val doomed = if (cancelPrevious) unsettled.map { it.job } else emptyList()

        val job = scope.async {
            try {
                // Wait for any previous job to fully settle (including its NonCancellable sweep)
                // before we start. cancel() alone would not be enough — it returns immediately
                // and puts us back in a two-jobs-in-flight state. join() rather than await() on
                // the serialize path: we have nothing to do with the previous run's outcome, and
                // await() would rethrow its failure as ours.
                // Oldest first, so the run that is actually touching packages is the first one
                // stopped. Each cancelled job settles fully (body AND NonCancellable sweep)
                // before the next cancel, and before this run reaches any package of its own.
                if (cancelPrevious) doomed.forEach { it.cancelAndJoin() } else previous?.join()
                // B-8: run() returns null for no-op cases (no privilege, empty target list);
                // do not publish BulkResult(0,0,0) because the tile already communicates
                // "nothing to freeze" — a false "Froze 0 apps" message is worse than silence.
                run(request)?.also { result ->
                    // Publish to _lastResult for FREEZE only. The tile is freeze-only (D1) and
                    // _lastResult is process-lifetime, so parking an UNFREEZE result here would
                    // render it in the tile subtitle the next time the shade opens — possibly
                    // hours later. The notification reports both ops, but only when the user
                    // permits notifications; the returned Deferred is what gives an UNFREEZE
                    // caller an unconditional surface. See the BulkResultNotifier KDoc.
                    //
                    // UNFREEZE clears instead of skipping: an unconsumed FREEZE result (one the
                    // shade never opened to display) describes packages this run has just
                    // unfrozen, so leaving it would show "Froze N apps" on a tile that is now
                    // READY again. Stale either way — drop it. That clear is scope-agnostic:
                    // a profile unfreeze can restore watchlist apps too, so it invalidates the
                    // parked freeze report just as surely as an Unfreeze-all does.
                    //
                    // A profile FREEZE parks nothing. The subtitle is the *tile's* report of the
                    // tile's own list, and the sheet that started a profile run reports it there
                    // by awaiting this Deferred — pushing it into the tile as well would put
                    // "Froze 4 apps" over a watchlist those four may not even belong to.
                    if (request.op == BulkOp.UNFREEZE) _lastResult.value = null
                    else if (request.scope == BulkScope.Watchlist) _lastResult.value = result
                    // Unconditional, unlike the lines around it: a completion is not a *report*
                    // of the run, it is the fact that package state changed. It must not inherit
                    // the tile's freeze-only rule (an unfreeze recolours icons too) nor the
                    // notifier's permission gate (icons are not a notification).
                    //
                    // Ahead of notifier.post for the same reason: post() is a binder call into
                    // NotificationManager and the outer catch would swallow anything it threw,
                    // silently skipping the emission for a run whose packages are already
                    // mutated. tryEmit cannot throw or suspend, so nothing is owed to ordering
                    // the other way.
                    _completions.tryEmit(request.op)
                    if (result.total > 0) notifier.post(result)
                }
            } catch (e: CancellationException) {
                // B-2: CancellationException is an Exception in Kotlin; rethrowing here keeps
                // structured cancellation intact and prevents the finally sweep from misreading
                // a cancelled run as a normal completion.
                throw e
            } catch (e: Exception) {
                // B-2: an escaped exception from run() (e.g. Room/DataStore IOException, binder
                // death) would reach Android's default uncaught handler and kill the process.
                // Log it and clear any stale result so the surface is not left showing the
                // previous run's outcome.
                Logger.e("BulkFreezeRunner", "bulk $request run failed unexpectedly", e)
                _lastResult.value = null
                null
            } finally {
                // B-6: sweep before this run leaves [runningRequests], for a stable combined
                // emission — completion, and therefore retirement, is what ends this block.
                // NOT runCatching: CancellationException is an Exception in Kotlin, and this
                // runs in a finally where cancellation is exactly what we may be unwinding
                // from. withContext(NonCancellable) lets the sweep finish even then, and the
                // narrow catch keeps a PackageManager failure from masking the real outcome.
                //
                // R4: race a cancellable join() rather than wrapping refreshFreezableCount()
                // in withTimeoutOrNull directly — for the same reason B-10 gives below. The
                // sweep ends in freezableCandidates(), a plain non-suspending filter over one
                // PackageManager binder call per watchlist entry, so a timeout wrapped around
                // it has no suspension point to fire at and would wait out the loop anyway.
                // Only join() on a separate job is actually interruptible. Without a bound
                // this is the one unbounded wait in the class: NonCancellable means a wedged
                // binder (PMS lock contention right after a batch of pm disable/uninstall is
                // not exotic) keeps this run in _runningRequests for the process lifetime,
                // leaving the tile on "Freezing…" and every later tap coalescing into the
                // stuck job.
                withContext(NonCancellable) {
                    val sweep = scope.launch { sweepFreezableCount() }
                    if (withTimeoutOrNull(SWEEP_GRACE_MS) { sweep.join() } == null) {
                        // Abandoned, not cancelled: it still publishes when the binder returns.
                        // The cost is that the run retires against the pre-run count for one
                        // paint — the tile re-sweeps on every onStartListening, so it heals.
                        Logger.d(
                            "BulkFreezeRunner",
                            "post-run candidate sweep exceeded ${SWEEP_GRACE_MS}ms; releasing the run"
                        )
                    }
                }
            }
        }
        unsettled += InFlight(request, job)
        publishRunning()
        // R1/R2: retire on *completion* rather than from the coroutine's own finally. A job
        // cancelled before its body ever ran never reaches that finally — a real case, since a
        // conflicting op cancels the whole chain and the tail of a chain is by definition the run
        // that has not started — and it would then sit in [unsettled] for the process lifetime,
        // pinning the tile on "Freezing…" and making every later conflicting op cancel-and-join a
        // long-dead job. Completion fires after the body, so the post-run sweep still lands
        // before the request is withdrawn.
        job.invokeOnCompletion { retire(job) }
        return job
    }

    /** Drop a settled run from the chain and republish. Identity, not equality: two runs of the
     * same request can overlap across a cancel/replace handoff. */
    @Synchronized
    private fun retire(job: Deferred<BulkResult?>) {
        unsettled.removeAll { it.job === job }
        publishRunning()
    }

    /** Republish [runningRequests] from [unsettled]. Callers hold the monitor. */
    private fun publishRunning() {
        _runningRequests.value = unsettled.map { it.request }
    }

    /**
     * Clear [lastResult] after it has been shown, consuming only the exact result that was
     * displayed. Compare-and-set means a new result published between the caller's read and
     * this call is not silently dropped.
     */
    fun consumeResult(shown: BulkResult) {
        _lastResult.compareAndSet(shown, null)
    }

    // B-8: returns null when there is nothing to act on, so the caller can skip publishing a
    // misleading BulkResult(0,0,0).
    private suspend fun run(request: BulkRequest): BulkResult? {
        val op = request.op
        // Await readiness here rather than trusting the caller to have awaited it. PrivilegeState
        // starts at active = NONE / isReady = false, and hasAnyPrivilege is `active != NONE`, so
        // the raw snapshot reads false until BOTH the probe and the first DataStore emission have
        // landed — reading it directly turns a cold-start run into a silent no-op.
        //
        // The tile happens to be safe because it awaits `isReady` itself before calling launch()
        // (it needs the resolved state to paint), but FreezerLaunchActivity's shortcuts run their
        // own direct probe and call straight through. The runner must not depend on its caller
        // having awaited anything.
        if (!privilegeManager.state.first { it.isReady }.hasAnyPrivilege) return null

        val targets = targetsFor(request)
        if (targets.isEmpty()) return null

        // Resolved once per run, not per package: the mode cannot change mid-batch, and the
        // op × mode decision is a pure function so it can be unit-tested away from binders.
        val action = bulkActionFor(op, preferenceRepository.userPreferences.first().freezerMode)

        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)

        // B-10: The batch is a child of `scope`, NOT of the withTimeoutOrNull block below.
        // withTimeoutOrNull is a scoping builder, so wrapping the batch directly would cancel
        // the children and then block until they finish. The Shizuku and Dhizuku reflection
        // paths make blocking binder calls that do not observe cancellation; the root path
        // (Odin exec) does unwind promptly via suspendCancellableCoroutine, but defense in
        // depth requires the deadline to hold for the paths that do not. Racing a cancellable
        // join() instead lets us abandon and report on time.
        val job = scope.launch {
            targets.forEach { pkg ->
                launch {
                    semaphore.withPermit {
                        ensureActive()
                        val result = try {
                            when (action) {
                                BulkAction.UNFREEZE -> manageAppUseCase.forceUnfreeze(pkg)
                                BulkAction.SUSPEND -> manageAppUseCase.setAppSuspended(pkg, true)
                                BulkAction.DISABLE -> manageAppUseCase.setAppDisabled(pkg, true)
                            }
                        } catch (e: CancellationException) {
                            // CancellationException IS an Exception in Kotlin, so it must be
                            // rethrown ahead of any broad catch or ensureActive() above is
                            // defeated and the batch silently ignores cancellation.
                            throw e
                        } catch (e: Exception) {
                            Logger.e("BulkFreezeRunner", "bulk $op failed for $pkg", e)
                            Result.failure(e)
                        }
                        // B-3: this split is safe only if a cancelled worker throws rather
                        // than returning Result.failure. SystemRepositoryImpl.runGatewayAction
                        // re-throws CancellationException, and the withContext(IO) wrappers
                        // around each call site are cancellation-atomic, so a deadline-killed
                        // worker unwinds before reaching this branch.
                        if (result.isSuccess) succeeded.incrementAndGet()
                        else failed.incrementAndGet()
                    }
                }
            }
        }

        try {
            val finished = withTimeoutOrNull(DEADLINE_MS) { job.join() } != null
            if (!finished) {
                Logger.d("BulkFreezeRunner", "bulk $op hit the ${DEADLINE_MS}ms deadline")
            }
        } finally {
            // R3: cancel the sibling batch job on every exit — timeout, normal completion
            // (harmless no-op on an already-finished job), or outer cancellation of run()'s
            // caller. Without this, cancelling the outer job (e.g. cancelAndJoin() from a
            // replacement launch) leaves up to MAX_CONCURRENT workers mutating packages
            // unsupervised because the sibling relationship means they are not automatically
            // cancelled with the caller.
            job.cancel()
            // ...then give the workers that DO observe cancellation a bounded window to
            // actually unwind, so they are gone before a replacement batch starts. Two
            // requirements shape this:
            //   NonCancellable — we are usually in this finally *because* the caller was
            //     cancelled, so a plain join() would resume-with-cancellation immediately and
            //     wait for nothing.
            //   withTimeoutOrNull — an unbounded join() re-introduces exactly the hang the R3
            //     comment above warns about, because the Shizuku/Dhizuku binder calls never
            //     observe cancellation and would hold this until they return on their own.
            // The grace period is short: it covers unwinding, not completion.
            withContext(NonCancellable) { withTimeoutOrNull(CANCEL_GRACE_MS) { job.join() } }
        }

        return BulkResult(
            op = op,
            total = targets.size,
            succeeded = succeeded.get(),
            failed = failed.get(),
        )
    }

    private companion object {
        const val MAX_CONCURRENT = 5
        const val DEADLINE_MS = 30_000L

        // How long a run waits for its abandoned workers to unwind before returning. Bounded on
        // purpose: the Shizuku/Dhizuku paths make blocking binder calls that ignore
        // cancellation, so an unbounded join would stall the next batch behind them.
        const val CANCEL_GRACE_MS = 2_000L

        // How long the post-run finally waits for the candidate sweep before releasing the run.
        // Deliberately NOT CANCEL_GRACE_MS: that bounds an unwind, this bounds real work — one
        // binder call per watchlist entry, against a PackageManager that has just been made to
        // disable or uninstall a batch of packages. Generous enough that it is a wedge detector
        // rather than a load limiter, because a sweep that misses only costs one stale paint.
        const val SWEEP_GRACE_MS = 10_000L
    }
}
