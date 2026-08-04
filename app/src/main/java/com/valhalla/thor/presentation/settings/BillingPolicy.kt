// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

/**
 * The decisions the Play billing flow makes, lifted out of the billing library so they can be
 * unit-tested.
 *
 * `com.android.billingclient` is a `storeImplementation` dependency and the unit-test task CI runs
 * is `testFossDebugUnitTest`, so nothing that names `BillingClient`, `Purchase` or `ProductDetails`
 * is reachable from a test. The store implementation therefore maps those types onto the plain
 * values below at the call site — where the library constants belong — and lets these functions
 * decide. That also keeps the two offer-selection sites (product listing and `launchBillingFlow`)
 * answering the same question the same way; they used to disagree by accident.
 *
 * Nothing here is referenced by the foss flavour: a handful of pure functions and one small state
 * machine, all of which R8 drops.
 */

/**
 * True when Google will revoke and refund this purchase unless Thor acknowledges it.
 *
 * Google auto-refunds any purchase still unacknowledged after three days, so the load-bearing half
 * of this predicate is [isAcknowledged]. `onPurchasesUpdated` is not a reliable delivery — Thor is
 * an app manager and a prime low-memory kill candidate while the Play sheet, a separate process,
 * is foreground — so a purchase read back by `queryPurchasesAsync` on a later launch is routinely
 * `PURCHASED` and not acknowledged. Sweeping on that state is what makes the money safe.
 */
fun needsAcknowledgement(isPurchased: Boolean, isAcknowledged: Boolean): Boolean =
    isPurchased && !isAcknowledged

/**
 * One pricing phase of a subscription offer, mapped from
 * `ProductDetails.PricingPhase`.
 */
data class SubscriptionPricingPhase(
    val formattedPrice: String,
    /** ISO-8601 period the phase is billed at, e.g. `P1M`. Empty when Play reported none. */
    val billingPeriod: String,
    /** `recurrenceMode == INFINITE_RECURRING` — the phase the subscriber keeps paying forever. */
    val isRecurring: Boolean
)

/**
 * One subscription offer, mapped from `ProductDetails.SubscriptionOfferDetails`.
 */
data class SubscriptionOffer(
    /**
     * Null or empty for a plain base plan; non-empty for a Play Console offer (free trial,
     * introductory price). This is the field that tells the two apart.
     */
    val offerId: String?,
    val offerToken: String,
    val phases: List<SubscriptionPricingPhase>
)

/**
 * Picks the offer Thor means to sell: the base plan.
 *
 * Taking `firstOrNull()` handed the choice to Play's list ordering. Adding a free trial in Play
 * Console is a console-only change — no app release — and from that moment the first entry can be
 * the trial, so the tier advertises "Free / month" and `launchBillingFlow` sends the trial's
 * token. Which offer the user is charged for is an app decision, not an ordering accident.
 *
 * The base plan is the offer with no [SubscriptionOffer.offerId]. If every offer carries one
 * (possible when a base plan is only sold through offers), the fewest-phases offer is the closest
 * thing to a plain recurring price: a trial or intro offer always prepends its phase in front of
 * the base phase, so it can only ever have more.
 */
fun selectBaseOffer(offers: List<SubscriptionOffer>): SubscriptionOffer? =
    offers.firstOrNull { it.offerId.isNullOrEmpty() } ?: offers.minByOrNull { it.phases.size }

/**
 * The phase whose price and period describe what the subscriber is actually charged, ongoing.
 *
 * Last rather than first: a trial or intro offer lists its discounted phases before the recurring
 * one, and it is the recurring one the tier advertises. Falls back to the last phase for an offer
 * with no infinitely recurring phase at all (a prepaid plan), which is still nearer the truth than
 * the first.
 */
fun SubscriptionOffer.recurringPhase(): SubscriptionPricingPhase? =
    phases.lastOrNull { it.isRecurring } ?: phases.lastOrNull()

/** The billing periods Thor can name in words. Anything else is [UNKNOWN] and goes unlabelled. */
enum class SubscriptionPeriod { WEEKLY, MONTHLY, QUARTERLY, HALF_YEARLY, YEARLY, UNKNOWN }

/**
 * Maps an ISO-8601 billing period from Play onto a period Thor has a translated label for.
 *
 * [UNKNOWN] is deliberate rather than a fallback to "month": a four-week base plan (`P4W`) is not
 * a monthly one, and showing the price with no period is honest where showing the wrong period is
 * not.
 */
fun subscriptionPeriodOf(billingPeriod: String): SubscriptionPeriod =
    when (billingPeriod.uppercase()) {
        "P1W" -> SubscriptionPeriod.WEEKLY
        "P1M" -> SubscriptionPeriod.MONTHLY
        "P3M" -> SubscriptionPeriod.QUARTERLY
        "P6M" -> SubscriptionPeriod.HALF_YEARLY
        "P1Y", "P12M" -> SubscriptionPeriod.YEARLY
        else -> SubscriptionPeriod.UNKNOWN
    }

private const val BILLING_RETRY_BASE_DELAY_MS = 1_000L
private const val BILLING_RETRY_MAX_DELAY_MS = 30_000L

/**
 * Delay before retry number [attempt] (0-based) of an acknowledgement or a reconnection.
 *
 * Doubling from a second and capped, because both callers are bounded and both have a backstop: a
 * failed acknowledgement is retried by the `queryPurchasesAsync` sweep on the next launch, and a
 * failed reconnection by the next one the billing library itself schedules.
 */
fun billingRetryDelayMillis(attempt: Int): Long {
    if (attempt <= 0) return BILLING_RETRY_BASE_DELAY_MS
    // Clamp the shift before shifting: `1_000L shl 64` wraps back round to 1_000L rather than
    // saturating, so capping only the result would let a large attempt count reset the backoff.
    val shift = attempt.coerceAtMost(5)
    return (BILLING_RETRY_BASE_DELAY_MS shl shift).coerceAtMost(BILLING_RETRY_MAX_DELAY_MS)
}

/**
 * Retries one run of the reconnect ladder is worth: 1 + 2 + 4 + 8 + 16 s of waiting, 31 s in total.
 *
 * Retries, not binds. Only [BillingReconnectLadder.onFailure] spends one of these, and the *initial*
 * `startConnection` is not a failure — it goes through [BillingReconnectLadder.onAttemptStarted],
 * which stamps the clock but touches no budget. So a cold ladder issues six `startConnection` calls,
 * at roughly t=0, 1, 3, 7, 15 and 31 s, and gives up after the sixth.
 */
const val BILLING_MAX_RECONNECT_ATTEMPTS = 5

/**
 * How long after the last connection attempt a resume is allowed to re-arm an exhausted ladder.
 *
 * Deliberately the same 30 s as [BILLING_RETRY_MAX_DELAY_MS], the longest single wait the ladder
 * will take on its own: the slowest step it is willing to take is also the fastest a user can
 * restart it. That is the entire bound on resume-driven reconnects, and it is a floor on the
 * *rate* rather than a cap on the *count*, because there is no honest count — a user may legitimately
 * resume Thor a hundred times over an afternoon during which Play never becomes reachable.
 *
 * The arithmetic that bound is worth, counting binds rather than budget (see
 * [BILLING_MAX_RECONNECT_ATTEMPTS] for why those differ): a cold ladder issues six
 * `startConnection` calls over 31 s, then refuses every resume until 30 s after the last of them.
 * A re-armed run issues five, because the re-arm itself spends an attempt queueing the first. So no
 * pattern of resumes — including holding the app switcher open and flicking back and forth — pushes
 * Thor past six binds in any ~61 s window.
 */
const val BILLING_REARM_COOLDOWN_MILLIS = 30_000L

/**
 * What a caller should do about a Play billing connection that is not up.
 *
 * Every non-[Retry] case is a distinct reason for doing nothing, because "returned null" is what
 * made the previous shape of this logic unreviewable: two of these were reachable in situations the
 * comments claimed they were not, and there was no way to tell them apart from outside.
 */
sealed interface BillingReconnectStep {
    /** Wait [delayMillis], then call `startConnection` again. */
    data class Retry(val delayMillis: Long) : BillingReconnectStep

    /** A retry is already queued and will fire on its own; a second one would only stack binds. */
    data object AlreadyQueued : BillingReconnectStep

    /** The ladder has spent its [attemptsSpent] attempts. Only a resume re-arms it from here. */
    data class Exhausted(val attemptsSpent: Int) : BillingReconnectStep

    /** A resume arrived inside [BILLING_REARM_COOLDOWN_MILLIS] of the last attempt. */
    data object TooSoon : BillingReconnectStep

    /** Terminal teardown has run; the client and its scope are gone and nothing can revive them. */
    data object Stopped : BillingReconnectStep
}

/**
 * Decides when to rebuild a lost Play billing connection, and when to stop trying.
 *
 * Lifted out of the store flavour's `BillingProcessorImpl` because nothing in that file can be
 * asserted: `com.android.billingclient` is a `storeImplementation` dependency and the unit-test task
 * is `testFossDebugUnitTest`, so a `store` class is not on any test's classpath. The counting is
 * where the money-losing failure lives — an exhausted ladder means the support sheet renders the
 * "Rate on Play Store" fallback forever and a subscription is never sold — so the counting is the
 * part that had to become testable. Everything that names `BillingClient` (`startConnection`,
 * `isReady`, `connectionState`) stays on the other side of this boundary; this class only counts and
 * compares clock readings. That split is also why the class takes a clock instead of calling one: a
 * test drives sixty virtual seconds in a loop, which is not a thing `SystemClock` will do.
 *
 * The caller drives it with five events and obeys the returned [BillingReconnectStep]:
 *
 *  - [onAttemptStarted] immediately before every `startConnection`, including the first
 *  - [reset] when the connection is up, or when something outside the ladder is bringing it up
 *  - [onFailure] on a non-OK setup result or a service disconnect
 *  - [onResume] when the user returns to the app and the client is not ready
 *  - [stop] from terminal teardown
 *
 * `@Synchronized` rather than the `@Volatile` fields this replaced: every one of these is a
 * read-modify-write of two or three fields at once, and they arrive on at least two threads — the
 * billing library's main-thread callbacks and the processor's `Dispatchers.Default` scope — so
 * `@Volatile` was buying visibility for an operation that was never atomic to begin with. The
 * critical sections are a handful of integer comparisons; nothing blocks inside one.
 *
 * @param elapsedRealtimeMillis a monotonic clock that keeps counting through deep sleep. Wall time
 *   would let an NTP correction or a user changing the date shorten or freeze the cooldown, and an
 *   uptime clock that stops in doze would make the cooldown unbounded for exactly the multi-day
 *   background sit this whole mechanism exists to survive.
 */
class BillingReconnectLadder(
    private val elapsedRealtimeMillis: () -> Long,
    private val maxAttempts: Int = BILLING_MAX_RECONNECT_ATTEMPTS,
    private val rearmCooldownMillis: Long = BILLING_REARM_COOLDOWN_MILLIS
) {
    private var attempt = 0
    private var retryQueued = false

    /** Null until the first attempt: a cold start must not be told to wait for a cooldown it missed. */
    private var lastAttemptAt: Long? = null
    private var stopped = false

    /**
     * A `startConnection` call is being made right now.
     *
     * Clearing [retryQueued] *here* rather than when the retry coroutine finishes is the whole fix
     * for the ladder dying after one step. `BillingClientImpl.zzbu` answers `BILLING_UNAVAILABLE`
     * synchronously, on the calling thread, when the Play Store service cannot be resolved or bound
     * — which is the normal path on a device with no usable Play Store, not an edge case. So the
     * failure callback re-enters the ladder from inside the very coroutine that is servicing the
     * queued retry. Guarding on "is that coroutine still alive?" answered yes to its own caller and
     * dropped the step, ending the ladder after ~1 s instead of ~31 s.
     */
    @Synchronized
    fun onAttemptStarted() {
        retryQueued = false
        lastAttemptAt = elapsedRealtimeMillis()
    }

    /**
     * Stand down: the connection is up, or someone else is actively bringing it up and will report
     * back through [onFailure] or a successful setup either way.
     *
     * The second case is the library's own lazy auto-reconnection winning the race with a queued
     * retry. Clearing the budget for it is deliberate: a spent budget is a statement about attempts
     * that failed, and an attempt that is still in flight has not.
     */
    @Synchronized
    fun reset() {
        attempt = 0
        retryQueued = false
    }

    /** A connection attempt failed, or an established binding dropped. */
    @Synchronized
    fun onFailure(): BillingReconnectStep {
        if (stopped) return BillingReconnectStep.Stopped
        if (retryQueued) return BillingReconnectStep.AlreadyQueued
        if (attempt >= maxAttempts) return BillingReconnectStep.Exhausted(attempt)
        val delayMillis = billingRetryDelayMillis(attempt)
        attempt++
        retryQueued = true
        return BillingReconnectStep.Retry(delayMillis)
    }

    /**
     * The user came back to the app and the client is not connected.
     *
     * A resume is information the back-off ladder structurally cannot have: it is evidence about the
     * *outside world* — hours may have passed, the Play Store's self-update may have finished, a
     * flight-mode toggle may have landed — where every other input to the ladder is evidence about
     * the last 31 seconds. Treating it as a full re-arm rather than a single extra attempt follows
     * from that: the run that failed was about a different world.
     *
     * Bounded by [rearmCooldownMillis] measured from the last attempt, not by a count. A count is
     * the thing that produced the defect this replaces — any fixed budget is a promise that Play
     * will be reachable within it, and Play makes no such promise.
     */
    @Synchronized
    fun onResume(): BillingReconnectStep {
        if (stopped) return BillingReconnectStep.Stopped
        if (retryQueued) return BillingReconnectStep.AlreadyQueued
        val since = lastAttemptAt?.let { elapsedRealtimeMillis() - it }
        if (since != null && since < rearmCooldownMillis) return BillingReconnectStep.TooSoon
        attempt = 0
        return onFailure()
    }

    /**
     * Terminal, matching the processor's `close()`: the billing client is CLOSED and the scope that
     * would run a retry is cancelled, so a later [onResume] must not hand the caller a `Retry` it
     * can only drop on the floor.
     */
    @Synchronized
    fun stop() {
        stopped = true
        retryQueued = false
    }
}
