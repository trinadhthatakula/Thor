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
 * Every subscription product ID Thor asks Play about, whether or not it exists yet.
 *
 * **This list is the entire catalogue.** Play Billing has no enumeration API and cannot be given
 * one: `queryProductDetailsAsync` is the only call that returns product metadata, and
 * `QueryProductDetailsParams.Builder` has exactly one setter — `setProductList` — whose `build()`
 * throws `"Product list must be set to a non empty list."` (verified with `javap` against
 * `com.android.billingclient:billing:9.1.0`). Enumeration exists only in the Google Play Developer
 * API, behind an OAuth scope that is read-*write* over the whole developer account, so it cannot go
 * in a shipped APK — least of all a FOSS one anyone can rebuild. Naming the IDs is not a shortcut
 * around a better API; it is the only thing the client can do.
 *
 * So the list is deliberately wider than the tiers that exist today. **Asking about an ID that does
 * not exist is free**: the response code is `OK`, the known products come back in
 * `getProductDetailsList()` and the unknown ones in `getUnfetchedProductList()` with
 * [UnfetchedProductReason.PRODUCT_NOT_FOUND] — no error, no dialog, no degraded result. The whole
 * batch is one round trip. That is what buys the property this list exists for: **creating a tier
 * whose ID is already in here is a Play Console change and needs no app release.** Creating one
 * outside it does need a release, which is the cost of the ID set being a compile-time constant —
 * and the price of not putting a remote-controlled catalogue URL in an app whose privacy policy
 * promises exactly one network call.
 *
 * Order here is irrelevant: [sortSupportTiers] re-orders by the price Play reports, so this stays
 * ascending only for a reader's benefit.
 *
 * Keep this at or under [MAX_PRODUCTS_PER_QUERY] — see that constant for what a longer list costs.
 */
val SUPPORT_TIER_PRODUCT_IDS: List<String> = listOf(
    "support_tier_1",
    "support_tier_2",
    "support_tier_3",
    "support_tier_5",
    "support_tier_10",
    "support_tier_15",
    "support_tier_20",
    "support_tier_25",
    "support_tier_50",
    "support_tier_100"
)

/**
 * The most product IDs to put in a single [SUPPORT_TIER_PRODUCT_IDS] query.
 *
 * Not a limit the library enforces — it is where the query stops being one binder round trip.
 * Beyond it the call is split, and a single failing chunk fails the whole query rather than
 * degrading to a partial catalogue, so a longer list trades the "no release needed" property for a
 * new way to render an empty support sheet. If the tier set ever genuinely needs to be this wide,
 * that is the point to reconsider the design rather than to raise the number.
 */
const val MAX_PRODUCTS_PER_QUERY = 20

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
    val isRecurring: Boolean,
    /**
     * [formattedPrice] as a number, in millionths of [priceCurrencyCode]'s unit.
     *
     * Carried purely so [sortSupportTiers] can order tiers without parsing a localized string. It
     * is never displayed: [formattedPrice] is what Play has already formatted for the user's locale
     * and currency, and re-deriving that from a number is how an app ends up showing `$1.00` to
     * someone Play is charging ₹99.
     *
     * `0` means Play reported no price at all, which for a paid subscription means "unknown" rather
     * than "free" — [sortSupportTiers] sorts those last rather than treating them as the cheapest.
     */
    val priceAmountMicros: Long = 0L,
    /**
     * ISO 4217 code for [priceAmountMicros], e.g. `USD`, `INR`. Empty when Play reported none.
     *
     * Play prices a query in one currency — the user's billing country — so in practice every tier
     * in a response shares this value and comparing the raw micros is same-currency by
     * construction. It is carried anyway so [sortSupportTiers] can *enforce* that rather than
     * assume it; comparing 99 INR against 1 USD as bare numbers would silently invert the list.
     */
    val priceCurrencyCode: String = ""
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

/**
 * Orders the support tiers cheapest first, using the price Play reported.
 *
 * This replaces a `when` on the product ID that mapped `support_tier_5` → 5, `…_10` → 10, `…_25` →
 * 25, `…_50` → 50 and **everything else → 0**. That `else` is the reason this function exists: it
 * did not mean "unknown", it meant "cheaper than every tier there is". `support_tier_1` happened to
 * land in the right place under it — $1 *is* the cheapest — which made the rule look correct while
 * it was still guessing; a `support_tier_100` would have rendered above the $5 tier, at the top of
 * the sheet. A rule that has to be extended by hand for every new tier is also the rule that
 * silently misplaces the tier nobody remembered to add, which is precisely what
 * [SUPPORT_TIER_PRODUCT_IDS] exists to make routine.
 *
 * Price from Play is authoritative, needs no maintenance, and cannot drift from what the row
 * displays, because it is the same [SubscriptionPricingPhase] the row's [formatted price]
 * [SubscriptionPricingPhase.formattedPrice] came from.
 *
 * The ordering, in full:
 *  - tiers with a price Play reported come before tiers without one — an absent price is unknown,
 *    not free, and putting it first is the exact mistake the old `else -> 0` made;
 *  - then by currency, so a response that somehow mixed them groups rather than interleaves
 *    (see [SubscriptionPricingPhase.priceCurrencyCode]; with the single currency Play actually
 *    returns, this comparison is constant and the result is plain price order);
 *  - then by price, ascending;
 *  - then by ID, so two tiers at the same price have a fixed order instead of inheriting whatever
 *    order Play happened to answer in.
 */
fun sortSupportTiers(products: List<BillingProduct>): List<BillingProduct> =
    products.sortedWith(
        // `false` sorts before `true`, so "has no price" last.
        compareBy<BillingProduct> { it.priceAmountMicros <= 0L }
            .thenBy { it.priceCurrencyCode }
            .thenBy { it.priceAmountMicros }
            .thenBy { it.id }
    )

/**
 * Why Play declined to return details for a product ID that was asked about.
 *
 * Mapped from `UnfetchedProduct.getStatusCode()`, which arrives as a bare `int`. The constants are
 * **not contiguous** — `UnfetchedProduct.StatusCode` in billing 9.1.0 defines `UNKNOWN = 0`,
 * `INVALID_PRODUCT_ID_FORMAT = 2`, `PRODUCT_NOT_FOUND = 3`, `NO_ELIGIBLE_OFFER = 4`, and **no 1**
 * (verified with `javap`). [UNRECOGNISED] covers 1 along with any value a later library version
 * adds, so a new code reads as "something Thor has not been taught" instead of being folded into
 * `UNKNOWN`, which is itself a real status Play sends.
 */
enum class UnfetchedProductReason {
    /** Play sent status 0 — it declined to say why. */
    UNKNOWN,

    /** The ID is not a well-formed product ID. A typo in [SUPPORT_TIER_PRODUCT_IDS]. */
    INVALID_PRODUCT_ID_FORMAT,

    /** No such product in Play Console. The **expected** answer for most of the candidate set. */
    PRODUCT_NOT_FOUND,

    /** The product exists, but has no base plan this user can be sold. Usually a misconfiguration. */
    NO_ELIGIBLE_OFFER,

    /** A status code this version of Thor does not know. */
    UNRECOGNISED
}

fun unfetchedProductReasonOf(statusCode: Int): UnfetchedProductReason = when (statusCode) {
    0 -> UnfetchedProductReason.UNKNOWN
    2 -> UnfetchedProductReason.INVALID_PRODUCT_ID_FORMAT
    3 -> UnfetchedProductReason.PRODUCT_NOT_FOUND
    4 -> UnfetchedProductReason.NO_ELIGIBLE_OFFER
    else -> UnfetchedProductReason.UNRECOGNISED
}

/**
 * Whether an unfetched product is worth a log line.
 *
 * [PRODUCT_NOT_FOUND][UnfetchedProductReason.PRODUCT_NOT_FOUND] is not. Deliberately probing IDs
 * that mostly do not exist is the design (see [SUPPORT_TIER_PRODUCT_IDS]), so most of the candidate
 * set answers this way on every single query and logging it would bury the two cases that mean a
 * tier the developer *did* create is not being sold: a malformed ID, or a product with no eligible
 * offer. Filtering here rather than at the log call keeps that judgement testable.
 */
fun UnfetchedProductReason.isWorthReporting(): Boolean =
    this != UnfetchedProductReason.PRODUCT_NOT_FOUND

/**
 * How long a non-empty catalogue is trusted before a resume is allowed to re-read it.
 *
 * The catalogue used to be re-read only when it was *empty* — the repair path for a query that
 * failed while the binding stayed up. That was sufficient when the tier list was a hardcoded
 * constant that could only change in a release that restarted the process anyway. It is not
 * sufficient now: with [SUPPORT_TIER_PRODUCT_IDS] the whole point is that a tier created in Play
 * Console appears without an app update, and a process that never re-queries a catalogue it already
 * has would not show it until the app was killed. An hour bounds that to one extra network call per
 * hour of use, on a resume that is already doing a purchase sweep.
 */
const val CATALOG_REFRESH_MIN_INTERVAL_MILLIS = 60 * 60 * 1_000L

/**
 * Decides whether a resume should re-read the product catalogue.
 *
 * Split out of the store flavour for the same reason as [BillingReconnectLadder] — nothing that
 * names `BillingClient` is on a unit test's classpath — and takes a clock for the same reason: a
 * test drives hours in a loop, which `SystemClock` will not do. See
 * [CATALOG_REFRESH_MIN_INTERVAL_MILLIS] for why re-reading is needed at all.
 *
 * @param elapsedRealtimeMillis monotonic, and counting through deep sleep. A phone in a pocket
 *   overnight is the case where the catalogue is most likely to be stale.
 */
class CatalogRefreshGate(
    private val elapsedRealtimeMillis: () -> Long,
    private val minIntervalMillis: Long = CATALOG_REFRESH_MIN_INTERVAL_MILLIS
) {
    /** Null until the first query: a process that has never fetched must never be throttled. */
    private var lastFetchAt: Long? = null

    /**
     * @param catalogIsEmpty whether anything is currently being shown to the user.
     *
     * An empty catalogue is never throttled. While it is empty the support sheet is rendering the
     * "Rate on Play Store" fallback and no subscription can be sold at all, so this is the repair
     * path, and it is the behaviour that was already shipping. Throttling applies only to
     * re-reading a catalogue that is already good enough to display.
     */
    @Synchronized
    fun shouldFetch(catalogIsEmpty: Boolean): Boolean {
        if (catalogIsEmpty) return true
        val last = lastFetchAt ?: return true
        return elapsedRealtimeMillis() - last >= minIntervalMillis
    }

    /**
     * A query is being issued right now.
     *
     * Stamped on the attempt rather than on success, like [BillingReconnectLadder.onAttemptStarted]:
     * stamping only successes would let a catalogue that is stale *and* failing re-query on every
     * single resume, which is the one case where the network is least likely to be there.
     */
    @Synchronized
    fun onFetchStarted() {
        lastFetchAt = elapsedRealtimeMillis()
    }
}

private const val BILLING_RETRY_BASE_DELAY_MS = 1_000L
private const val BILLING_RETRY_MAX_DELAY_MS = 30_000L

/**
 * Delay before retry number [attempt] (0-based) of an acknowledgement or a reconnection.
 *
 * Doubling from a second and capped, because both callers are bounded and both have a backstop: a
 * failed acknowledgement is retried by the `queryPurchasesAsync` sweep on the next launch, and a
 * failed reconnection by `refreshPurchases()` on the next resume, which re-arms the ladder through
 * `BillingReconnectLadder.onResume()`.
 *
 * That second half used to read "by the next one the billing library itself schedules", which is
 * wrong in a way worth naming rather than quietly correcting: **the library schedules nothing.**
 * `enableAutoServiceReconnection` is lazy — in billing 9.1.0 the only reachable call to
 * `BillingClientImpl.zzaI(int)` sits behind `zzbw(long)`/`zzbx(long)`, which run at the head of each
 * API callable, so the binding is rebuilt on the *next API call* and never on a timer. There is also
 * no listener notification: the library's own reconnect passes an internal `zzbv` to
 * `zzbu(listener, i)` with `i != 0`, which does not overwrite the app's stored `zzK`, so
 * `onBillingSetupFinished` never fires again. `BillingProcessorImpl` says all of this at its
 * `enableAutoServiceReconnection` call and at `scheduleReconnect`; this KDoc was the one place that
 * said the opposite, and a reader who believed it could have cut the resume re-arm as redundant —
 * removing the only backstop there is.
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
 * billing library's main-thread callbacks and the processor's injected default-dispatcher scope — so
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
