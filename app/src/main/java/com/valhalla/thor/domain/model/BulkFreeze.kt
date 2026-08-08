// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Which direction a bulk run moves apps. The QS tile only ever issues [FREEZE]. */
enum class BulkOp { FREEZE, UNFREEZE }

/**
 * Which list of packages a bulk run acts on.
 *
 * Before profiles there was only one answer, so the runner keyed its single job slot on
 * [BulkOp] alone. That is exactly the bug this type exists to prevent: with two profiles,
 * "freeze A" followed by "freeze B" are both FREEZE, so the second call would have been
 * coalesced onto the first run and B would silently never freeze.
 */
sealed interface BulkScope {
    /** The freezer watchlist — the QS tile and both launcher Freeze-all/Unfreeze-all shortcuts. */
    data object Watchlist : BulkScope

    /** One freeze profile, by row id. */
    data class Profile(val id: Long) : BulkScope
}

/**
 * A bulk run's full identity: what to do, to which list, and — when the caller insists — how.
 *
 * Equality is the coalescing key: two requests are the same run only if every part matches.
 *
 * [mode] is null for every caller that has no opinion, which is nearly all of them — the tile, both
 * launcher shortcuts and the Freezer's own Freeze-all all mean "freeze the way this user has said
 * they want apps frozen", and get the [FreezerMode] out of preferences at run time. A profile row's
 * explicit *Suspend* is the exception: it names the verb, so it carries it.
 *
 * Widening the key is deliberate, and is the opposite call to the one made about where a run came
 * from (which is not part of a run's identity and must never be added here). A suspend-run and a
 * disable-run of the same profile are genuinely different operations over the same packages, and
 * coalescing the second onto the first would silently do the wrong one.
 */
data class BulkRequest(
    val op: BulkOp,
    val scope: BulkScope = BulkScope.Watchlist,
    val mode: FreezerMode? = null,
)

/**
 * The concrete per-package action a bulk run performs.
 *
 * Separate from [BulkOp] because "freeze" is two different system calls depending on the
 * user's [FreezerMode]: disable the package, or suspend it.
 */
enum class BulkAction { UNFREEZE, SUSPEND, DISABLE }

/**
 * Resolve [op] × [mode] to the action to perform on each package.
 *
 * Extracted as a pure function because this is the Freeze-vs-Suspend rule the project already
 * regressed on once (GH#239): unfreezing must restore both dimensions (unsuspend *and*
 * enable), while freezing picks exactly one according to the mode. [mode] is irrelevant when
 * unfreezing — [BulkAction.UNFREEZE] maps to `forceUnfreeze`, which handles both cases.
 */
fun bulkActionFor(op: BulkOp, mode: FreezerMode): BulkAction = when {
    op == BulkOp.UNFREEZE -> BulkAction.UNFREEZE
    mode == FreezerMode.SUSPEND -> BulkAction.SUSPEND
    else -> BulkAction.DISABLE
}

/**
 * The same rule, with [BulkRequest.mode] deciding whether [globalMode] is consulted at all.
 *
 * A separate function rather than the resolution being inlined at the runner's one call site,
 * because that call site sits behind four collaborators no JVM test can build. This is the whole
 * of what the override means, and it is assertable.
 */
fun bulkActionFor(request: BulkRequest, globalMode: FreezerMode): BulkAction =
    bulkActionFor(request.op, request.mode ?: globalMode)

/**
 * Outcome of a bulk run.
 *
 * [op] is carried on the result because one runner serves both directions: without it an
 * UNFREEZE run is reported with freeze wording ("Froze 5 apps").
 *
 * [unresolved] is the third bucket that makes a deadline honest: those packages were either
 * never started or were still running when the deadline fired. Reporting them as failures
 * (as the pre-rework tile did) claims knowledge we do not have.
 */
data class BulkResult(
    val op: BulkOp,
    val total: Int,
    val succeeded: Int,
    val failed: Int,
) {
    val unresolved: Int get() = total - succeeded - failed
}

/**
 * A [BulkResult] held for a surface that is not on screen yet, with the moment it was parked.
 *
 * The QS tile is the only such surface, and it is why the stamp exists. `BulkFreezeRunner` lives
 * for the whole process, so a result parked for the tile subtitle waits until the shade next opens
 * — which may be seconds later, or the following morning. Unstamped, "Froze 12 apps" is what the
 * user reads on a tile they pull down at breakfast about a run they started the night before, and
 * it reads as *just now*: the subtitle is the tile's live status line everywhere else.
 *
 * [publishedAtMs] must come from a monotonic source (`SystemClock.elapsedRealtime`), not from wall
 * clock time. A parked result is compared only against a later reading of the same source, and
 * wall clock can be set backwards by the user or by NTP, which would make an hours-old report look
 * fresh again.
 */
data class ParkedBulkResult(
    val result: BulkResult,
    val publishedAtMs: Long,
)

/**
 * [parked] if it is still worth showing at [nowMs], or null once it has aged past [ttlMs].
 *
 * A pure function so the rule can be asserted at all: the only thing that parks or reads a result
 * is `BulkFreezeRunner`, which takes four collaborators no JVM test can build.
 *
 * The expiry is *read-side*: nothing sweeps the parked value on a timer, and nothing needs to. The
 * tile paints synchronously at the top of every `onStartListening`, so the only moment staleness
 * can be observed is a moment this function is already being called at. A timer would add a
 * process-lifetime coroutine to publish a change no one is subscribed to see.
 *
 * A negative age is treated as stale rather than clamped. It cannot arise from
 * `elapsedRealtime` — but if some later caller passes a clock that can go backwards, the failure
 * this function exists to prevent is showing an old report as new, so it fails that way.
 */
fun freshParkedResult(parked: ParkedBulkResult?, nowMs: Long, ttlMs: Long): ParkedBulkResult? =
    parked?.takeIf { (nowMs - it.publishedAtMs) in 0 until ttlMs }

/**
 * How a bulk run ended, as its caller sees it.
 *
 * This exists because a nullable [BulkResult] cannot say it: the runner returned null both for
 * "there was nothing to act on" and for "this blew up and was caught", so every surface that
 * awaited a run reported a Room or binder failure as *nothing to do*. That is the one wrong thing
 * to say about a failure — it tells the user their apps are fine when nobody knows whether they
 * are, and it does so on the run most likely to have left the watchlist half-frozen.
 *
 * [NothingToDo] is a real, ordinary outcome and not a degenerate [Completed]: a run with no
 * privilege or an empty target list touched nothing, and `BulkResult(0, 0, 0)` would be reported
 * as "Froze 0 apps" — a false report of a freeze that never ran.
 */
sealed interface BulkOutcome {
    /** The batch ran. [result] counts what it did, including partial and unresolved work. */
    data class Completed(val result: BulkResult) : BulkOutcome

    /** No privilege, or nothing left to act on after the tier filter. No package was touched. */
    data object NothingToDo : BulkOutcome

    /**
     * The run raised, and it was caught so the process would survive it.
     *
     * Says nothing about how far it got: the throw can come from computing the targets (nothing
     * touched) or from the middle of the batch (some packages already mutated), so a caller can
     * report that it did not finish but must not claim what state the apps are in.
     */
    data class Failed(val cause: Throwable) : BulkOutcome
}
