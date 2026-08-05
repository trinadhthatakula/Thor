// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billing rules that decide money, kept away from BillingClient so they can be run.
 *
 * The scenarios below are the ones that were live defects: a purchase read back on a later cold
 * start was never acknowledged (Google refunds after three days), and both offer-selection sites
 * took `firstOrNull`, so adding a free trial in Play Console — no app release involved — moved the
 * trial to the front and the app quoted and charged it.
 */
class BillingPolicyTest {

    // ── Acknowledgement ─────────────────────────────────────────────────────

    @Test
    fun `a purchase that Play has not seen acknowledged must be acknowledged`() {
        assertTrue(needsAcknowledgement(isPurchased = true, isAcknowledged = false))
    }

    @Test
    fun `an already acknowledged purchase is left alone`() {
        // The old code only ever acknowledged from onPurchasesUpdated, so the sweep it lacked had
        // to be able to tell these two apart from the same query result.
        assertFalse(needsAcknowledgement(isPurchased = true, isAcknowledged = true))
    }

    @Test
    fun `a pending purchase is never acknowledged`() {
        // Play has not taken the money yet; acknowledging is not ours to do until it reports
        // PURCHASED.
        assertFalse(needsAcknowledgement(isPurchased = false, isAcknowledged = false))
        assertFalse(needsAcknowledgement(isPurchased = false, isAcknowledged = true))
    }

    // ── Offer selection ─────────────────────────────────────────────────────

    private val basePlan = SubscriptionOffer(
        offerId = null,
        offerToken = "base-token",
        phases = listOf(
            SubscriptionPricingPhase(
                formattedPrice = "$5.00",
                billingPeriod = "P1M",
                isRecurring = true
            )
        )
    )

    private val freeTrial = SubscriptionOffer(
        offerId = "free-trial",
        offerToken = "trial-token",
        phases = listOf(
            SubscriptionPricingPhase(
                formattedPrice = "Free",
                billingPeriod = "P1W",
                isRecurring = false
            ),
            SubscriptionPricingPhase(
                formattedPrice = "$5.00",
                billingPeriod = "P1M",
                isRecurring = true
            )
        )
    )

    @Test
    fun `the base plan wins even when Play lists a free trial first`() {
        assertEquals(basePlan, selectBaseOffer(listOf(freeTrial, basePlan)))
    }

    @Test
    fun `the base plan wins when Play lists it first`() {
        assertEquals(basePlan, selectBaseOffer(listOf(basePlan, freeTrial)))
    }

    @Test
    fun `an empty offer id counts as the base plan, not as an offer`() {
        val emptyIdBase = basePlan.copy(offerId = "")
        assertEquals(emptyIdBase, selectBaseOffer(listOf(freeTrial, emptyIdBase)))
    }

    @Test
    fun `with only promotional offers the fewest-phases one wins`() {
        // No plain base plan on the product at all: an intro offer (one discounted phase then the
        // recurring one) still beats a trial that stacks another phase in front.
        val introOffer = freeTrial.copy(offerId = "intro", offerToken = "intro-token")
        val stackedOffer = SubscriptionOffer(
            offerId = "trial-plus-intro",
            offerToken = "stacked-token",
            phases = freeTrial.phases + freeTrial.phases
        )
        assertEquals(introOffer, selectBaseOffer(listOf(stackedOffer, introOffer)))
    }

    @Test
    fun `a product with no offers selects nothing`() {
        assertNull(selectBaseOffer(emptyList()))
    }

    // ── Advertised price ────────────────────────────────────────────────────

    @Test
    fun `the advertised phase is the recurring one, not the trial`() {
        // firstOrNull here is what rendered the tier as "Free / month".
        assertEquals("$5.00", freeTrial.recurringPhase()?.formattedPrice)
        assertEquals("P1M", freeTrial.recurringPhase()?.billingPeriod)
    }

    @Test
    fun `an offer with no recurring phase falls back to its last phase`() {
        val prepaid = SubscriptionOffer(
            offerId = "prepaid",
            offerToken = "prepaid-token",
            phases = listOf(
                SubscriptionPricingPhase("$1.00", "P1W", isRecurring = false),
                SubscriptionPricingPhase("$5.00", "P1M", isRecurring = false)
            )
        )
        assertEquals("$5.00", prepaid.recurringPhase()?.formattedPrice)
    }

    @Test
    fun `an offer with no phases at all has no price`() {
        assertNull(basePlan.copy(phases = emptyList()).recurringPhase())
    }

    // ── Period labelling ────────────────────────────────────────────────────

    @Test
    fun `the periods Play bills base plans at map to a label`() {
        assertEquals(SubscriptionPeriod.WEEKLY, subscriptionPeriodOf("P1W"))
        assertEquals(SubscriptionPeriod.MONTHLY, subscriptionPeriodOf("P1M"))
        assertEquals(SubscriptionPeriod.QUARTERLY, subscriptionPeriodOf("P3M"))
        assertEquals(SubscriptionPeriod.HALF_YEARLY, subscriptionPeriodOf("P6M"))
        assertEquals(SubscriptionPeriod.YEARLY, subscriptionPeriodOf("P1Y"))
        assertEquals(SubscriptionPeriod.YEARLY, subscriptionPeriodOf("P12M"))
    }

    @Test
    fun `a four-week plan is not called monthly`() {
        // The whole point of carrying the period instead of hardcoding "/ month".
        assertEquals(SubscriptionPeriod.UNKNOWN, subscriptionPeriodOf("P4W"))
        assertEquals(SubscriptionPeriod.UNKNOWN, subscriptionPeriodOf("P2M"))
        assertEquals(SubscriptionPeriod.UNKNOWN, subscriptionPeriodOf(""))
    }

    // ── Retry backoff ───────────────────────────────────────────────────────

    @Test
    fun `backoff doubles from a second`() {
        assertEquals(1_000L, billingRetryDelayMillis(0))
        assertEquals(2_000L, billingRetryDelayMillis(1))
        assertEquals(4_000L, billingRetryDelayMillis(2))
        assertEquals(8_000L, billingRetryDelayMillis(3))
    }

    @Test
    fun `backoff never exceeds the cap and never wraps back round`() {
        // `1_000L shl 64` is 1_000L again, so a large attempt count must not reset the wait.
        for (attempt in 0..70) {
            assertTrue(
                "attempt $attempt gave ${billingRetryDelayMillis(attempt)}",
                billingRetryDelayMillis(attempt) in 1_000L..30_000L
            )
        }
        assertEquals(30_000L, billingRetryDelayMillis(6))
        assertEquals(30_000L, billingRetryDelayMillis(64))
    }

    @Test
    fun `backoff never goes backwards`() {
        var previous = 0L
        for (attempt in 0..10) {
            val delay = billingRetryDelayMillis(attempt)
            assertTrue("attempt $attempt shrank the wait", delay >= previous)
            previous = delay
        }
    }

    // ── Reconnect ladder ────────────────────────────────────────────────────
    //
    // The ladder is the only thing that rebuilds a billing binding: the library's
    // `enableAutoServiceReconnection` reconnects on the next API call, and the store processor
    // guards every API call on `isReady`, so the two fail together. When the ladder stops, billing
    // is off for the rest of the process — `isBillingAvailable` stays false, the support sheet
    // renders the "Rate on Play Store" fallback, and the sweep that keeps Google from refunding an
    // unacknowledged purchase after three days never runs again. Hence the detail below.

    /** Drives [BillingReconnectLadder] against a clock the test moves by hand. */
    private class Clock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    /** One `startConnection` that immediately fails, as a device with no Play Store answers. */
    private fun BillingReconnectLadder.attemptAndFail(): BillingReconnectStep {
        onAttemptStarted()
        return onFailure()
    }

    /**
     * Runs a whole ladder to exhaustion and returns its last step.
     *
     * Six attempts, not five: the first is the connection that opened the run, and the budget is
     * only spent once the fifth *queued retry* has fired and failed. Asking `onFailure()` for the
     * sixth verdict without making that attempt reports [BillingReconnectStep.AlreadyQueued] — the
     * retry is still pending — which is the shape the store processor's coroutine also has.
     */
    private fun BillingReconnectLadder.exhaust(): BillingReconnectStep {
        repeat(BILLING_MAX_RECONNECT_ATTEMPTS) { attemptAndFail() }
        return attemptAndFail()
    }

    @Test
    fun `the ladder walks one two four eight sixteen seconds and then stops`() {
        val ladder = BillingReconnectLadder(Clock())
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.attemptAndFail())
        assertEquals(BillingReconnectStep.Retry(2_000L), ladder.attemptAndFail())
        assertEquals(BillingReconnectStep.Retry(4_000L), ladder.attemptAndFail())
        assertEquals(BillingReconnectStep.Retry(8_000L), ladder.attemptAndFail())
        assertEquals(BillingReconnectStep.Retry(16_000L), ladder.attemptAndFail())
        assertEquals(BillingReconnectStep.Exhausted(5), ladder.attemptAndFail())
        // And it stays spent — nothing about repeating the failure revives it.
        assertEquals(BillingReconnectStep.Exhausted(5), ladder.attemptAndFail())
    }

    @Test
    fun `a synchronous failure from inside the queued retry is not read as a retry already pending`() {
        // The defect this pins: `BillingClientImpl.zzbu` answers BILLING_UNAVAILABLE on the caller's
        // thread when the Play Store service will not bind, so the failure callback re-enters the
        // ladder from inside the coroutine servicing the queued retry. Guarding on "is that
        // coroutine still alive?" answered yes to its own caller, dropped the step, and ended the
        // ladder after ~1 s rather than ~31 s. `onAttemptStarted` is what clears the flag.
        val ladder = BillingReconnectLadder(Clock())
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.onFailure())
        ladder.onAttemptStarted()
        assertEquals(BillingReconnectStep.Retry(2_000L), ladder.onFailure())
    }

    @Test
    fun `a second failure before the queued retry fires is dropped`() {
        // The half of the old guard that was right: a setup failure and a service disconnect can
        // both land for one lost binding, and they must not each queue a startConnection.
        val ladder = BillingReconnectLadder(Clock())
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.onFailure())
        assertEquals(BillingReconnectStep.AlreadyQueued, ladder.onFailure())
        assertEquals(BillingReconnectStep.AlreadyQueued, ladder.onFailure())
    }

    @Test
    fun `a resume re-arms a spent ladder and gives it the whole budget back`() {
        val clock = Clock()
        val ladder = BillingReconnectLadder(clock)
        assertEquals(BillingReconnectStep.Exhausted(5), ladder.exhaust())

        clock.now += BILLING_REARM_COOLDOWN_MILLIS
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.onResume())
        // Not one extra attempt — a fresh run. The evidence a resume carries is about the world
        // outside the 31 seconds that failed.
        assertEquals(BillingReconnectStep.Retry(2_000L), ladder.attemptAndFail())
        assertEquals(BillingReconnectStep.Retry(4_000L), ladder.attemptAndFail())
        assertEquals(BillingReconnectStep.Retry(8_000L), ladder.attemptAndFail())
        assertEquals(BillingReconnectStep.Retry(16_000L), ladder.attemptAndFail())
        assertEquals(BillingReconnectStep.Exhausted(5), ladder.attemptAndFail())
    }

    @Test
    fun `the first resume of a process is not blocked by a cooldown it never started`() {
        // `lastAttemptAt` is null, not zero: subtracting a sentinel from an elapsed-realtime reading
        // taken seconds after boot would put the very first resume inside a cooldown that no attempt
        // ever opened.
        val ladder = BillingReconnectLadder(Clock(now = 0L))
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.onResume())
    }

    @Test
    fun `a resume inside the cooldown is refused`() {
        val clock = Clock()
        val ladder = BillingReconnectLadder(clock)
        assertEquals(BillingReconnectStep.Exhausted(5), ladder.exhaust())

        clock.now += BILLING_REARM_COOLDOWN_MILLIS - 1
        assertEquals(BillingReconnectStep.TooSoon, ladder.onResume())
        clock.now += 1
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.onResume())
    }

    @Test
    fun `a resume while the ladder is mid-run leaves it alone`() {
        val clock = Clock()
        val ladder = BillingReconnectLadder(clock)
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.attemptAndFail())
        // A retry is already queued, so a resume must not queue a second startConnection...
        assertEquals(BillingReconnectStep.AlreadyQueued, ladder.onResume())
        // ...nor reset the counter behind the run's back once that retry has fired, which is what
        // the cooldown covers: an attempt made a moment ago is the same evidence a resume brings.
        ladder.onAttemptStarted()
        assertEquals(BillingReconnectStep.TooSoon, ladder.onResume())
    }

    @Test
    fun `hammering the app switcher cannot exceed five connection attempts a minute`() {
        // Item 4 of the brief, stated as arithmetic. A resume every 50 ms for ten virtual minutes,
        // with Play unreachable throughout — the pathological case, since the real user cannot
        // resume an app twenty times a second.
        val clock = Clock()
        val ladder = BillingReconnectLadder(clock)
        val attemptTimes = mutableListOf<Long>()
        var retryDueAt: Long? = null

        fun connect() {
            ladder.onAttemptStarted()
            attemptTimes += clock.now
            retryDueAt = (ladder.onFailure() as? BillingReconnectStep.Retry)
                ?.let { clock.now + it.delayMillis }
        }

        connect() // the constructor's first connection
        val totalMillis = 10 * 60 * 1_000L
        while (clock.now < totalMillis) {
            clock.now += 50
            retryDueAt?.let { due -> if (clock.now >= due) connect() }
            val step = ladder.onResume()
            if (step is BillingReconnectStep.Retry) retryDueAt = clock.now + step.delayMillis
        }

        // A full run is 5 binds over 31 s, then 30 s of cooldown before another can start: at most
        // 5 per ~61 s, so ~50 in ten minutes and nowhere near the 12 000 resumes that drove it.
        assertTrue(
            "hammering produced ${attemptTimes.size} startConnection calls in 10 min",
            attemptTimes.size <= 60
        )
        // And the ladder is not simply dead either — the whole point is that resumes keep it alive.
        assertTrue(
            "the ladder stopped re-arming: only ${attemptTimes.size} attempts in 10 min",
            attemptTimes.size >= 20
        )
        // The sharper statement of the same bound: no burst is possible at any point, because the
        // shortest rung of the ladder is a second and a re-arm enters at that rung rather than
        // connecting on the spot.
        val shortestGap = attemptTimes.zipWithNext { a, b -> b - a }.minOrNull()
        assertTrue("two startConnection calls were ${shortestGap}ms apart", shortestGap!! >= 1_000L)
    }

    @Test
    fun `a successful connection hands the whole budget back`() {
        val ladder = BillingReconnectLadder(Clock())
        repeat(4) { ladder.attemptAndFail() }
        ladder.reset()
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.attemptAndFail())
    }

    @Test
    fun `reset clears a queued retry so a stood-down step cannot stall the ladder`() {
        // The path where the library's own reconnection wins the race with a queued retry: the
        // retry declines to run. If declining left the queued flag set, every later failure and
        // every later resume would read as AlreadyQueued forever — a stall with no exhaustion log.
        val ladder = BillingReconnectLadder(Clock())
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.onFailure())
        ladder.reset()
        assertEquals(BillingReconnectStep.Retry(1_000L), ladder.onFailure())
    }

    @Test
    fun `after terminal teardown nothing can re-arm the ladder`() {
        // `close()` is terminal by design — Koin @Single, and neither the BillingClient nor the
        // scope is restartable. A re-arm that survived it would hand the caller a Retry it can only
        // drop, or worse, a startConnection on a CLOSED client.
        val clock = Clock()
        val ladder = BillingReconnectLadder(clock)
        ladder.attemptAndFail()
        ladder.stop()
        clock.now += BILLING_REARM_COOLDOWN_MILLIS * 100
        assertEquals(BillingReconnectStep.Stopped, ladder.onResume())
        assertEquals(BillingReconnectStep.Stopped, ladder.onFailure())
        // Even a stray success callback arriving after teardown cannot reopen it.
        ladder.reset()
        assertEquals(BillingReconnectStep.Stopped, ladder.onResume())
    }

    // ── The candidate product set ───────────────────────────────────────────
    //
    // Play Billing has no enumeration API, so this list *is* the catalogue: an ID that is never
    // queried can never be sold, however correctly it is configured in Play Console. It is wider
    // than the tiers that exist so that adding one is a console change rather than a release.

    @Test
    fun `every tier that was previously hardcoded is still queried`() {
        // The regression that would cost real revenue silently. These four were the entire query
        // before the candidate set replaced it; dropping one stops selling it, and nothing about a
        // build or a screenshot would say so.
        for (id in listOf("support_tier_5", "support_tier_10", "support_tier_25", "support_tier_50")) {
            assertTrue("$id is no longer queried", id in SUPPORT_TIER_PRODUCT_IDS)
        }
    }

    @Test
    fun `the one-dollar tier is queried`() {
        // The tier that prompted all of this: created in Play Console, invisible to the app,
        // because the app only ever asked about four IDs and this was not one of them.
        assertTrue("support_tier_1" in SUPPORT_TIER_PRODUCT_IDS)
    }

    @Test
    fun `the candidate set fits a single round trip`() {
        assertTrue(
            "${SUPPORT_TIER_PRODUCT_IDS.size} candidates exceeds the one-query bound",
            SUPPORT_TIER_PRODUCT_IDS.size <= MAX_PRODUCTS_PER_QUERY
        )
    }

    @Test
    fun `no candidate id is listed twice`() {
        // A duplicate is not a crash, just a wasted slot against the bound above — and it would
        // publish the same tier to the sheet twice.
        assertEquals(SUPPORT_TIER_PRODUCT_IDS.size, SUPPORT_TIER_PRODUCT_IDS.toSet().size)
    }

    @Test
    fun `every candidate id is well formed`() {
        // A typo here is not a build failure and not a visible one either: Play answers
        // INVALID_PRODUCT_ID_FORMAT or PRODUCT_NOT_FOUND for it and the query otherwise succeeds,
        // so the tier simply never appears.
        val shape = Regex("""support_tier_\d+""")
        for (id in SUPPORT_TIER_PRODUCT_IDS) {
            assertTrue("'$id' is not a support tier id", shape.matches(id))
        }
    }

    // ── Tier ordering ───────────────────────────────────────────────────────

    private fun tier(id: String, micros: Long, currency: String = "USD") = BillingProduct(
        id = id,
        name = id,
        formattedPrice = "",
        description = "",
        billingPeriod = "P1M",
        priceAmountMicros = micros,
        priceCurrencyCode = currency
    )

    @Test
    fun `tiers are ordered by the price Play reported`() {
        val shuffled = listOf(
            tier("support_tier_25", 25_000_000L),
            tier("support_tier_1", 1_000_000L),
            tier("support_tier_50", 50_000_000L),
            tier("support_tier_5", 5_000_000L),
            tier("support_tier_10", 10_000_000L)
        )
        assertEquals(
            listOf("support_tier_1", "support_tier_5", "support_tier_10", "support_tier_25", "support_tier_50"),
            sortSupportTiers(shuffled).map { it.id }
        )
    }

    @Test
    fun `a tier costlier than any the old rule knew no longer sorts to the top`() {
        // The defect, exactly. The replaced rule was `when (id) { "..._5" -> 5 ... else -> 0 }`, so
        // every ID it had not been taught scored 0 — below the cheapest real tier. `support_tier_1`
        // landed correctly under it purely because $1 *is* the cheapest, which is what made the rule
        // look right while it was still guessing. `support_tier_100` is the same rule being wrong.
        val sorted = sortSupportTiers(
            listOf(
                tier("support_tier_100", 100_000_000L),
                tier("support_tier_5", 5_000_000L),
                tier("support_tier_1", 1_000_000L)
            )
        )
        assertEquals("support_tier_1", sorted.first().id)
        assertEquals("support_tier_100", sorted.last().id)
    }

    @Test
    fun `a tier Play reported no price for sorts last, not first`() {
        // The other half of `else -> 0`: an absent price is unknown, not free. Such a row renders
        // its description instead of a price, and putting it at the top of the sheet presents the
        // one tier the app understands least as the entry-level option.
        val sorted = sortSupportTiers(
            listOf(
                tier("support_tier_2", 0L, currency = ""),
                tier("support_tier_5", 5_000_000L),
                tier("support_tier_1", 1_000_000L)
            )
        )
        assertEquals(
            listOf("support_tier_1", "support_tier_5", "support_tier_2"),
            sorted.map { it.id }
        )
    }

    @Test
    fun `prices in different currencies group rather than interleave`() {
        // Play prices a query in the user's billing currency, so one response is single-currency and
        // this never fires in practice. It is asserted because the failure mode if it ever did is
        // silent and inverted: ₹99 is 99_000_000 micros and $1 is 1_000_000, so a bare numeric sort
        // would advertise the rupee tier as the expensive one.
        val sorted = sortSupportTiers(
            listOf(
                tier("support_tier_1_inr", 99_000_000L, currency = "INR"),
                tier("support_tier_5_usd", 5_000_000L, currency = "USD"),
                tier("support_tier_1_usd", 1_000_000L, currency = "USD"),
                tier("support_tier_5_inr", 499_000_000L, currency = "INR")
            )
        )
        assertEquals(
            listOf("support_tier_1_inr", "support_tier_5_inr", "support_tier_1_usd", "support_tier_5_usd"),
            sorted.map { it.id }
        )
    }

    @Test
    fun `two tiers at the same price have a fixed order`() {
        // Otherwise the sheet re-orders itself between launches for no reason the user can see,
        // because the input order is whatever Play answered in.
        val a = tier("support_tier_2", 2_000_000L)
        val b = tier("support_tier_3", 2_000_000L)
        assertEquals(listOf("support_tier_2", "support_tier_3"), sortSupportTiers(listOf(a, b)).map { it.id })
        assertEquals(listOf("support_tier_2", "support_tier_3"), sortSupportTiers(listOf(b, a)).map { it.id })
    }

    @Test
    fun `sorting nothing is not an error`() {
        assertEquals(emptyList<BillingProduct>(), sortSupportTiers(emptyList()))
    }

    // ── Unfetched products ──────────────────────────────────────────────────

    @Test
    fun `the status codes Play documents map to a reason`() {
        assertEquals(UnfetchedProductReason.UNKNOWN, unfetchedProductReasonOf(0))
        assertEquals(UnfetchedProductReason.INVALID_PRODUCT_ID_FORMAT, unfetchedProductReasonOf(2))
        assertEquals(UnfetchedProductReason.PRODUCT_NOT_FOUND, unfetchedProductReasonOf(3))
        assertEquals(UnfetchedProductReason.NO_ELIGIBLE_OFFER, unfetchedProductReasonOf(4))
    }

    @Test
    fun `status code one does not exist and is not silently folded into unknown`() {
        // `UnfetchedProduct.StatusCode` skips 1 — the constants are 0, 2, 3, 4. Treating the gap as
        // UNKNOWN would make a future library's new code indistinguishable from a real status Play
        // sends today.
        assertEquals(UnfetchedProductReason.UNRECOGNISED, unfetchedProductReasonOf(1))
        assertEquals(UnfetchedProductReason.UNRECOGNISED, unfetchedProductReasonOf(5))
        assertEquals(UnfetchedProductReason.UNRECOGNISED, unfetchedProductReasonOf(-1))
    }

    @Test
    fun `a product that simply does not exist is not worth reporting`() {
        // Most of the candidate set answers PRODUCT_NOT_FOUND on every query by design. Logging it
        // would bury the two answers that mean a tier which *does* exist is not reaching users.
        assertFalse(UnfetchedProductReason.PRODUCT_NOT_FOUND.isWorthReporting())
        assertTrue(UnfetchedProductReason.NO_ELIGIBLE_OFFER.isWorthReporting())
        assertTrue(UnfetchedProductReason.INVALID_PRODUCT_ID_FORMAT.isWorthReporting())
        assertTrue(UnfetchedProductReason.UNKNOWN.isWorthReporting())
        assertTrue(UnfetchedProductReason.UNRECOGNISED.isWorthReporting())
    }

    // ── Catalogue refresh ───────────────────────────────────────────────────

    @Test
    fun `an empty catalogue is re-read on every resume`() {
        // The repair path, unthrottled and unchanged: while the catalogue is empty the support sheet
        // is showing the "Rate on Play Store" fallback and no subscription can be sold at all.
        val clock = Clock()
        val gate = CatalogRefreshGate(clock)
        repeat(5) {
            assertTrue(gate.shouldFetch(catalogIsEmpty = true))
            gate.onFetchStarted()
            clock.now += 1_000
        }
    }

    @Test
    fun `a catalogue that has never been fetched is not throttled`() {
        // `lastFetchAt` is null rather than zero, for the same reason the reconnect ladder's is:
        // subtracting a sentinel from an elapsed-realtime reading taken shortly after boot would
        // put the first query inside a window no fetch ever opened.
        assertTrue(CatalogRefreshGate(Clock(now = 0L)).shouldFetch(catalogIsEmpty = false))
    }

    @Test
    fun `a populated catalogue is not re-read on every resume`() {
        val clock = Clock()
        val gate = CatalogRefreshGate(clock)
        gate.onFetchStarted()
        clock.now += CATALOG_REFRESH_MIN_INTERVAL_MILLIS - 1
        assertFalse(gate.shouldFetch(catalogIsEmpty = false))
    }

    @Test
    fun `a tier added in Play Console appears without the app being killed`() {
        // The property the whole candidate-set design exists to buy, stated as a test. Before this
        // gate the catalogue was re-read only when empty, so a running process kept serving the
        // tier list it had fetched at launch — and the new tier would have been withheld until the
        // app was force-stopped, on the one device its author was watching.
        val clock = Clock()
        val gate = CatalogRefreshGate(clock)
        gate.onFetchStarted()
        clock.now += CATALOG_REFRESH_MIN_INTERVAL_MILLIS
        assertTrue(gate.shouldFetch(catalogIsEmpty = false))
    }

    @Test
    fun `the stamp is taken on the attempt, so a failing query cannot re-run every resume`() {
        // onFetchStarted is called before the query, not in its callback. Stamping successes only
        // would mean a catalogue that is both stale and failing re-queries on every single resume —
        // exactly when the network is least likely to be there.
        val clock = Clock()
        val gate = CatalogRefreshGate(clock)
        gate.onFetchStarted() // a query that will never call back
        clock.now += 1_000
        assertFalse(gate.shouldFetch(catalogIsEmpty = false))
    }

    @Test
    fun `the shipped ladder is worth about thirty-one seconds`() {
        // Pins the constants the store processor actually runs with, not just the mechanism: the
        // tests above pass their own clock but take these defaults.
        assertEquals(5, BILLING_MAX_RECONNECT_ATTEMPTS)
        assertEquals(30_000L, BILLING_REARM_COOLDOWN_MILLIS)
        val budget = (0 until BILLING_MAX_RECONNECT_ATTEMPTS).sumOf { billingRetryDelayMillis(it) }
        assertEquals(31_000L, budget)
    }
}
