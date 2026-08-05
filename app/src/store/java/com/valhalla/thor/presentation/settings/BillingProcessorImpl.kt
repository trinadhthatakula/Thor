// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.UnfetchedProduct
import com.android.billingclient.api.acknowledgePurchase
import com.valhalla.thor.R
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

@Single
class BillingProcessorImpl(
    private val context: Context,
    // [scope] below is where every retry `delay` in this class is spent — a ~31 s reconnect
    // ladder. Nothing can reach it from a test: `com.android.billingclient` is a
    // storeImplementation dependency and the only unit-test task run anywhere is
    // testFossDebugUnitTest, so this class is not on any test's classpath (see BillingPolicy).
    @Named("default") private val defaultDispatcher: CoroutineDispatcher,
    // Toast has a hard main-thread requirement, so this one stays a real dispatcher choice rather
    // than an implementation detail of `scope`.
    @Named("main") private val mainDispatcher: CoroutineDispatcher,
) : BillingProcessor {

    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    private val _isBillingAvailable = MutableStateFlow(true)
    override val isBillingAvailable: StateFlow<Boolean> = _isBillingAvailable.asStateFlow()

    private val _products = MutableStateFlow<List<BillingProduct>>(emptyList())
    override val products: StateFlow<List<BillingProduct>> = _products.asStateFlow()

    private val _activeSubscription = MutableStateFlow<ActiveSubscription?>(null)
    override val activeSubscription: StateFlow<ActiveSubscription?> = _activeSubscription.asStateFlow()

    private val _showThankYouDialog = MutableStateFlow(false)
    override val showThankYouDialog: StateFlow<Boolean> = _showThankYouDialog.asStateFlow()

    /**
     * Play's `ProductDetails` for the tiers the last **successful** catalogue query returned,
     * keyed by product ID. This is what [launchBillingFlow] charges from.
     *
     * Replaced wholesale rather than updated in place, so it always holds exactly the snapshot
     * [products] was published from. Incremental puts were safe while the catalogue was read once
     * per process; now that it is re-read (see [CatalogRefreshGate]), a tier Play has stopped
     * selling would linger here after it left [products] — and the support sheet's pending
     * change-plan confirmation outlives a refresh, so a confirm tapped afterwards would launch a
     * flow for a withdrawn tier with a stale offer token.
     *
     * A failed query leaves the previous snapshot alone; only a success replaces it. `@Volatile`
     * over an immutable map rather than a `ConcurrentHashMap`: the requirement is a consistent
     * snapshot, not atomic per-key updates.
     */
    @Volatile
    private var productDetailsById: Map<String, ProductDetails> = emptyMap()

    /**
     * Purchase tokens an acknowledgement is already in flight for, or has already succeeded for.
     *
     * The sweep runs on every connection setup and every resume, and `queryPurchasesAsync` can
     * still report `isAcknowledged == false` for a token Play has only just accepted an
     * acknowledgement for. Without this, that race is a loop: acknowledge, re-query, see it
     * unacknowledged, acknowledge again.
     */
    private val acknowledgingTokens = ConcurrentHashMap.newKeySet<String>()

    /**
     * All the reconnect bookkeeping, kept where a JVM test can drive it.
     *
     * Nothing in this file is on any unit test's classpath — billing is a `storeImplementation`
     * dependency and the test task is `testFossDebugUnitTest` — so the counting lives in
     * flavour-agnostic `BillingPolicy.kt` and this class does only the parts that genuinely cannot
     * be made pure: calling `startConnection`, reading `connectionState` (see [isConnected] for why
     * that and never `isReady`), and sleeping.
     *
     * `elapsedRealtime` rather than `currentTimeMillis` because the cooldown must survive an NTP
     * correction, and rather than `uptimeMillis` because it must keep counting while the device is
     * dozing — a phone that has been in a pocket overnight is precisely the case a resume is meant
     * to rescue.
     */
    private val reconnect = BillingReconnectLadder(SystemClock::elapsedRealtime)

    /**
     * Rate-limits re-reading the product catalogue on resume. Same clock and same reasoning as
     * [reconnect]; see [CatalogRefreshGate].
     */
    private val catalogRefresh = CatalogRefreshGate(SystemClock::elapsedRealtime)

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                for (purchase in purchases) {
                    val isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    when {
                        needsAcknowledgement(isPurchased, purchase.isAcknowledged) ->
                            acknowledgePurchase(purchase, showThankYou = true)

                        // Already acknowledged — a re-delivery of something the sweep caught
                        // first. Nothing owed to Play, but the tier list still has to catch up.
                        isPurchased -> queryActiveSubscriptions()

                        // A slow payment method (cash, bank transfer). Play will report it again
                        // as PURCHASED when it clears; saying nothing at all here reads to the
                        // user as a tap that did nothing.
                        purchase.purchaseState == Purchase.PurchaseState.PENDING ->
                            showToast(context.getString(R.string.billing_purchase_pending))
                    }
                }
            } else if (billingResult.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
                showToast(context.getString(R.string.billing_error) + ": " + billingResult.responseCode)
            }
        }
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        // Opt-in since billing 9.x, and kept — but it is lazy, not proactive, and it has a second
        // effect that is easy to miss and was very nearly fatal here. See [isConnected]: enabling
        // this flag makes `isReady` a constant `true`, so `isReady` must never be used anywhere in
        // this file as a stand-in for "the binding is up".
        //
        // What it does do: in billing-9.1.0 the only reachable call to the reconnect helper
        // `BillingClientImpl.zzaI(int)` sits behind `zzbw(long)`/`zzbx(long)`, which run at the head
        // of each API callable — so it rebuilds the binding on the *next API call*. That covers the
        // two call sites here that are deliberately unguarded, [queryProducts] and
        // [acknowledgePurchase]; the latter is the money path, where a dropped binding costs a real
        // refund, so the library retrying underneath it is worth keeping.
        //
        // What it does NOT do is notify *this* client's listener. The library's own reconnect passes
        // an internal `zzbv` to `zzbu(listener, i)` with `i != 0`, which does not overwrite the app's
        // stored `zzK` — so `onBillingSetupFinished` never fires again and `_isBillingAvailable` /
        // `_products` are never repaired. [scheduleReconnect] is what actually rebuilds a binding
        // lost to a background Play Store self-update, and it is the only thing that can.
        .enableAutoServiceReconnection()
        .build()

    /**
     * Whether the Play binding is actually up.
     *
     * **Not `billingClient.isReady`**, which this class cannot use at all. `isReady` is
     * short-circuited by the very flag set above — verified with `javap` against the artifact Gradle
     * resolves (`com.android.billingclient:billing:9.1.0`):
     *
     * ```
     * BillingClient$Builder.enableAutoServiceReconnection()  ->  putfield zza:Z   (constant 1)
     * BillingClientImpl.<init>  (all four overloads)         ->  this.zzH = builder.zza
     * BillingClientImpl.isReady()                            ->  if (zzH) return true; else zzby()
     * ```
     *
     * So from construction onwards — before `startConnection` has ever been called — `isReady`
     * answers `true` on a client with no binding at all. Guarding on it made
     * `scheduleReconnect`'s `else -> connectToBilling()` arm unreachable (the ladder issued exactly
     * one bind instead of the documented 1/2/4/8/16 s ladder) and made `refreshPurchases`'
     * `reconnect.onResume()` re-arm dead code. Both of this class's recovery mechanisms were inert
     * on device while every JVM test passed, because the tests drive the ladder directly and never
     * construct a `BillingClient`.
     *
     * `getConnectionState()` is not affected: it returns the raw `zzb` field under the client's own
     * monitor, which is why it is the honest predicate.
     */
    private val isConnected: Boolean
        get() = billingClient.connectionState == BillingClient.ConnectionState.CONNECTED

    init {
        // startConnection() reaches PackageManager.queryIntentServices and bindService with no
        // thread hop of its own. This singleton is resolved on the first-frame path, which is
        // precisely when Thor is already saturating system_server by enumerating every installed
        // package, so the binder round-trips go to `scope` rather than the constructor's thread.
        scope.launch { connectToBilling() }
    }

    private fun connectToBilling() {
        // Before startConnection, not after: zzbu can answer BILLING_UNAVAILABLE on this very
        // thread before startConnection returns, and the listener below must find the ladder
        // already knowing an attempt is in progress.
        reconnect.onAttemptStarted()
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // Set back to true, not merely left alone: recovery has to be visible or the
                    // support sheet keeps rendering the "Rate on Play Store" fallback forever.
                    _isBillingAvailable.value = true
                    reconnect.reset()
                    queryProducts()
                    queryActiveSubscriptions()
                } else {
                    _isBillingAvailable.value = false
                    scheduleReconnect(reconnect.onFailure())
                }
            }

            override fun onBillingServiceDisconnected() {
                _isBillingAvailable.value = false
                scheduleReconnect(reconnect.onFailure())
            }
        })
    }

    /**
     * Carries out one [step] of [reconnect]: waits out its backoff, then reconnects — unless
     * something else got there first.
     *
     * This ladder is not a belt-and-braces backup for the library's own retries; it is the only
     * thing here that can restore the *observable* state. `enableAutoServiceReconnection` is *lazy*,
     * not proactive: decompiling billing-9.1.0 puts the sole reachable call to
     * `BillingClientImpl.zzaI(int)` behind `zzbw(long)`/`zzbx(long)`, which run at the head of each
     * API callable, so the library only rebuilds a binding when an API call is made on a
     * disconnected client. When it does, it reconnects with an internal listener of its own and
     * never calls `onBillingSetupFinished` on the one this class registered — so `_isBillingAvailable`
     * and `_products`, which are written nowhere else, stay stale however many times the library
     * silently repairs the binding underneath. That is why the two mechanisms are not
     * interchangeable and why the budget below cannot be the last word.
     *
     * One run of it is worth ~31 s and then it stops, because a device with no usable Play Store
     * would otherwise get an unbounded background wakeup loop out of a donation button. The escape
     * from that terminal state is [refreshPurchases] — a resume is the signal a fixed 31-second
     * budget cannot see.
     */
    private fun scheduleReconnect(step: BillingReconnectStep) {
        if (step is BillingReconnectStep.Exhausted) {
            Logger.w(
                "BillingProcessor",
                "Reconnect ladder spent after ${step.attemptsSpent} attempts; " +
                        "waiting for a resume to re-arm it"
            )
        }
        if (step !is BillingReconnectStep.Retry) return
        scope.launch {
            delay(step.delayMillis)
            when {
                // After close() the client is CLOSED for good; the ladder has to be told, or the
                // queued-retry flag it is still holding would make every later call read as
                // "a retry is already pending" on an instance that can never retry again.
                billingClient.connectionState == BillingClient.ConnectionState.CLOSED ->
                    reconnect.stop()
                // The library's own reconnection, or a resume-driven attempt, won the race. Both
                // branches still have to clear the queued flag — a step that silently declines to
                // run and says nothing is how a ladder stalls without ever reporting exhaustion.
                isConnected -> reconnect.reset()
                billingClient.connectionState == BillingClient.ConnectionState.CONNECTING ->
                    reconnect.reset()

                else -> connectToBilling()
            }
        }
    }

    private fun showToast(message: String) {
        scope.launch(mainDispatcher) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Asks Play about every ID in [SUPPORT_TIER_PRODUCT_IDS] and publishes whichever ones it sells.
     *
     * The list is deliberately wider than the tiers that exist, so that adding a tier is a Play
     * Console change rather than an app release; unknown IDs come back in the unfetched list at no
     * cost. [SUPPORT_TIER_PRODUCT_IDS] carries the full reasoning, including why Play cannot simply
     * be asked what it sells.
     *
     * **Raw `queryProductDetailsAsync`, not the `billing-ktx` `queryProductDetails()` suspend
     * wrapper this used to call.** That wrapper is lossy in exactly the way that matters here: its
     * `ProductDetailsResult` exposes only `getBillingResult()` and `getProductDetailsList()`, and
     * its internal listener never touches `getUnfetchedProductList()` (verified with `javap` against
     * billing-9.1.0). Probing IDs that may not exist without being able to read *why* one came back
     * unfetched would mean a tier created in Play Console but misconfigured — no eligible base plan,
     * say — would simply never appear, with nothing anywhere saying so. `acknowledgePurchase` still
     * uses billing-ktx; only this call needed the raw form.
     *
     * Note the listener signature is the breaking one introduced in Play Billing 8.0.0
     * (`onProductDetailsResponse(BillingResult, QueryProductDetailsResult)`), not the pre-8.0
     * `(BillingResult, List<ProductDetails>)`.
     */
    private fun queryProducts() {
        val productList = SUPPORT_TIER_PRODUCT_IDS.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        // Before the call, not in the callback, so a query whose callback never arrives still
        // counts as an attempt and cannot re-fire on every resume for the life of the process.
        //
        // Read precisely: this throttles the *populated*-catalogue path only. While `products` is
        // empty, `shouldFetch` returns true without ever reading the stamp — deliberately, because
        // a user looking at no tiers at all is the one case worth retrying eagerly. See
        // [CatalogRefreshGate].
        catalogRefresh.onFetchStarted()
        billingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
            // This runs on a library callback thread, so an escaping exception is an uncaught crash
            // rather than a failed coroutine. Everything it touches (`productDetailsById`,
            // `_products`) is already thread-safe; the mapping below is a few dozen field reads.
            try {
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Logger.e(
                        "BillingProcessor",
                        "Failed to query product details: ${billingResult.responseCode}"
                    )
                    return@queryProductDetailsAsync
                }

                // `.orEmpty()` on both lists is belt-and-braces, and the reason recorded here was
                // wrong: these are NOT platform types. `getProductDetailsList()` and
                // `getUnfetchedProductList()` both carry `@androidx.annotation.NonNull` (javap on
                // billing-9.1.0), so Kotlin already types them non-null — written as `?: emptyList()`
                // this would warn as a useless elvis, and `.orEmpty()` differs only in that the
                // compiler stays silent about it. The emitted branch is the same either way, so the
                // fallback costs nothing and still catches a library that violates its own contract;
                // just do not read it as evidence that the annotation is missing.
                val detailsList = queryResult.productDetailsList.orEmpty()
                val mappedProducts = detailsList.map { details ->
                    // Base plan by identity, recurring phase by recurrence mode — never
                    // firstOrNull on either. See [selectBaseOffer] / [recurringPhase].
                    val chargedPhase =
                        selectBaseOffer(details.toSubscriptionOffers())?.recurringPhase()
                    BillingProduct(
                        id = details.productId,
                        name = details.name,
                        formattedPrice = chargedPhase?.formattedPrice ?: "",
                        description = details.description,
                        billingPeriod = chargedPhase?.billingPeriod ?: "",
                        priceAmountMicros = chargedPhase?.priceAmountMicros ?: 0L,
                        priceCurrencyCode = chargedPhase?.priceCurrencyCode ?: ""
                    )
                }
                // Before `_products`, deliberately: the list is what the UI renders and taps
                // through to [launchBillingFlow], so the details it charges from must already be
                // the ones this response carried. See [productDetailsById].
                productDetailsById = detailsList.associateBy { it.productId }
                // Sorted here rather than at the one screen that renders it: `products` promises an
                // order (see [BillingProcessor.products]) and Play answers in no particular one.
                _products.value = sortSupportTiers(mappedProducts)
                reportUnfetchedProducts(queryResult.unfetchedProductList.orEmpty())
            } catch (e: Exception) {
                Logger.e("BillingProcessor", "Error handling product details response", e)
            }
        }
    }

    /**
     * Logs the candidate IDs Play refused, for the two reasons that mean something is wrong.
     *
     * Most of [SUPPORT_TIER_PRODUCT_IDS] is expected to come back `PRODUCT_NOT_FOUND` on every
     * query — that is the design, not a fault — so [isWorthReporting] drops those and leaves the
     * cases that mean a tier which *does* exist in Play Console is not reaching users.
     *
     * ⚠️ **This is a debug-build diagnostic only.** `Logger` gates every level on `Logger.isDebug`,
     * which `ThorApplication` sets from `BuildConfig.DEBUG || BuildConfig.PRIVILEGE_TRACE` — both
     * false in `storeRelease` — and there is no crash reporter or analytics in this app to carry it
     * instead. So on the builds that can actually complete a purchase, a misconfigured tier is still
     * diagnosed by noticing the row is missing. Giving this a sink that survives release (a debug
     * screen row, or persisted state) is the follow-up; it is deliberately not bundled here, because
     * it is new UI rather than part of making the catalogue dynamic.
     */
    private fun reportUnfetchedProducts(unfetched: List<UnfetchedProduct>) {
        for (product in unfetched) {
            val reason = unfetchedProductReasonOf(product.statusCode)
            if (!reason.isWorthReporting()) continue
            Logger.w(
                "BillingProcessor",
                "Play returned no details for ${product.productId}: $reason " +
                        "(status ${product.statusCode})"
            )
        }
    }

    private fun queryActiveSubscriptions() {
        if (!isConnected) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchaseList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                // The whole list, not just the active one: this is the only backstop for a
                // purchase whose onPurchasesUpdated never arrived, and Google revokes and refunds
                // anything still unacknowledged after three days. Silent — the thank-you dialog
                // belongs to the flow the user just completed, not to a sweep at startup.
                for (purchase in purchaseList) {
                    val isPurchased = purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    if (needsAcknowledgement(isPurchased, purchase.isAcknowledged)) {
                        acknowledgePurchase(purchase, showThankYou = false)
                    }
                }
                // Most recent, not first: an upgrade leaves the replaced subscription in the list
                // until Play retires it, and taking whichever Play happened to list first is the
                // same ordering bet the offer selection above stopped making. Newest is the tier
                // the user last chose.
                val active = purchaseList
                    .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .maxByOrNull { it.purchaseTime }
                if (active != null) {
                    val activeProductId = active.products.firstOrNull()
                    if (!activeProductId.isNullOrEmpty()) {
                        _activeSubscription.value = ActiveSubscription(
                            productId = activeProductId,
                            purchaseToken = active.purchaseToken
                        )
                    } else {
                        Logger.w("BillingProcessor", "Active purchase has empty or null product list")
                        _activeSubscription.value = null
                    }
                } else {
                    _activeSubscription.value = null
                }
            } else {
                Logger.e("BillingProcessor", "Failed to query active purchases: ${billingResult.responseCode}")
            }
        }
    }

    /**
     * Acknowledges [purchase], retrying with bounded backoff.
     *
     * A failed acknowledgement is as terminal as never sending one — Google refunds either way —
     * so a single non-OK response code is not something to log and walk away from. Every response
     * code is retried rather than only the transient ones: the attempt count is small, and
     * classifying Play's codes as permanent is exactly the kind of guess that loses the money.
     * Beyond the last attempt, the `queryPurchasesAsync` sweep on the next connection or resume is
     * the backstop.
     */
    private fun acknowledgePurchase(purchase: Purchase, showThankYou: Boolean) {
        // Also the loop guard: a sweep racing an in-flight acknowledgement still reads
        // isAcknowledged == false.
        if (!acknowledgingTokens.add(purchase.purchaseToken)) return
        val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        scope.launch {
            var attempt = 0
            while (true) {
                val responseCode = try {
                    billingClient.acknowledgePurchase(acknowledgePurchaseParams).responseCode
                } catch (e: CancellationException) {
                    // Scope teardown, not a billing failure. Caught by the clause below otherwise,
                    // which would spend the remaining attempts retrying a cancelled coroutine.
                    throw e
                } catch (e: Exception) {
                    Logger.e("BillingProcessor", "Error acknowledging purchase", e)
                    BillingClient.BillingResponseCode.ERROR
                }
                if (responseCode == BillingClient.BillingResponseCode.OK) {
                    if (showThankYou) _showThankYouDialog.value = true
                    queryActiveSubscriptions()
                    return@launch
                }
                attempt++
                if (attempt >= MAX_ACKNOWLEDGE_ATTEMPTS) {
                    Logger.e(
                        "BillingProcessor",
                        "Failed to acknowledge purchase after $attempt attempts: $responseCode"
                    )
                    // Release the token so the next sweep can try again rather than skipping it
                    // for the rest of the process.
                    acknowledgingTokens.remove(purchase.purchaseToken)
                    return@launch
                }
                delay(billingRetryDelayMillis(attempt - 1))
            }
        }
    }

    override fun launchBillingFlow(
        activity: Activity,
        productId: String,
        oldPurchaseToken: String?,
        oldProductId: String?
    ) {
        // One read of the volatile field, so the null check and everything below see the same
        // snapshot even if a catalogue refresh lands mid-method.
        val productDetails = productDetailsById[productId]
        if (productDetails == null) {
            Logger.e("BillingProcessor", "Product details not found for $productId")
            return
        }
        // Same selection as the price the tier advertised. Taking firstOrNull here charged the
        // user whichever offer Play happened to list first, which need not be the one the sheet
        // quoted them.
        val offerToken = selectBaseOffer(productDetails.toSubscriptionOffers())?.offerToken
        if (offerToken.isNullOrEmpty()) {
            Logger.e("BillingProcessor", "Offer token not found or empty for $productId")
            return
        }

        val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)

        if (!oldProductId.isNullOrEmpty()) {
            val replacementParams = SubscriptionProductReplacementParams.newBuilder()
                .setOldProductId(oldProductId)
                .setReplacementMode(SubscriptionProductReplacementParams.ReplacementMode.CHARGE_PRORATED_PRICE)
                .build()
            productDetailsParamsBuilder.setSubscriptionProductReplacementParams(replacementParams)
        }

        val productDetailsParamsList = listOf(productDetailsParamsBuilder.build())
        val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)

        if (!oldPurchaseToken.isNullOrEmpty()) {
            val updateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                .setOldPurchaseToken(oldPurchaseToken)
                .build()
            billingFlowParamsBuilder.setSubscriptionUpdateParams(updateParams)
        }

        val billingFlowParams = billingFlowParamsBuilder.build()

        if (isConnected) {
            billingClient.launchBillingFlow(activity, billingFlowParams)
        } else {
            Logger.e("BillingProcessor", "BillingClient is not connected; cannot launch billing flow")
        }
    }

    override fun dismissThankYouDialog() {
        _showThankYouDialog.value = false
    }

    override fun refreshPurchases() {
        // The disconnected branch used to return early, reasoning that onBillingSetupFinished runs
        // the identical sweep the moment the client connects. That holds only while something is
        // still trying to connect. Past the ladder's 5 attempts nothing calls startConnection ever
        // again, so the callback that was supposed to run the sweep never arrives, and this became
        // an unconditional no-op for the rest of the process: _isBillingAvailable stuck false, the
        // support sheet stuck on the "Rate on Play Store" fallback, and an unacknowledged purchase
        // never swept — which Google refunds after three days. A resume is the one signal that a
        // fixed retry budget cannot account for, so it re-arms the ladder instead of being dropped.
        //
        // The main-thread constraint the old comment protected is real and still holds: HomeActivity
        // calls this from onResume. Everything below runs on `scope`, and what a resume produces is
        // at most a *queued* startConnection — never a synchronous bindService on the main thread.
        // BillingReconnectLadder.onResume is arithmetic; scheduleReconnect only launches.
        scope.launch {
            if (isConnected) {
                queryActiveSubscriptions()
                // The catalogue can fail on its own, without the binding ever dropping. Binding is
                // local IPC and needs no network; `queryProductDetails` is a network call, so first
                // launch in airplane mode gives OK from onBillingSetupFinished and
                // SERVICE_UNAVAILABLE from the product query, which is logged and dropped. Nothing
                // then re-runs it: queryProducts has one call site, the OK branch of
                // onBillingSetupFinished, and that branch needs a *reconnect* to run again — which
                // never comes, because the connection never broke. The support sheet gates on
                // `isBillingAvailable && products.isNotEmpty()`, so it renders the "Rate on Play
                // Store" fallback until the process is killed.
                //
                // A resume is the same "the outside world changed" signal for a failed catalogue
                // fetch as it is for a failed binding; the connection path was given that escape
                // hatch above and the catalogue path needs it too.
                //
                // This used to be `if (_products.value.isEmpty())` — repair only, never refresh —
                // which was sound while the tier list was a hardcoded constant: the only way it
                // could change was a release, and a release restarts the process. That stopped being
                // true with [SUPPORT_TIER_PRODUCT_IDS]. The whole property that list buys is that a
                // tier created in Play Console shows up without an app update, and a process that
                // re-reads the catalogue only when it has none would have withheld exactly that tier
                // until the app was killed — the feature would have looked broken on the one device
                // its author was watching. [CatalogRefreshGate] keeps the unthrottled repair path
                // for an empty catalogue and adds an hourly re-read for a populated one.
                if (catalogRefresh.shouldFetch(_products.value.isEmpty())) queryProducts()
                return@launch
            }
            scheduleReconnect(reconnect.onResume())
        }
    }

    /**
     * Maps Play's offer list onto the flavor-agnostic model the selection rules in `BillingPolicy`
     * operate on. Every library constant stays on this side of the boundary.
     */
    private fun ProductDetails.toSubscriptionOffers(): List<SubscriptionOffer> =
        // This `.orEmpty()` is load-bearing, unlike the four below it:
        // `getSubscriptionOfferDetails()` is `@Nullable` and returns null for a one-time-purchase
        // product, so Kotlin types it nullable and this is a real branch. The `.orEmpty()` calls
        // inside the map are on `@NonNull` receivers and are dead by the type system. They read
        // identically; only the annotation tells them apart, which is why they are called out here.
        subscriptionOfferDetails.orEmpty().map { offer ->
            SubscriptionOffer(
                offerId = offer.offerId,
                offerToken = offer.offerToken,
                // No `?.` on `pricingPhases`: its backing field is assigned unconditionally in
                // `SubscriptionOfferDetails(JSONObject)` from `getJSONArray` — which throws rather
                // than yielding null — so an offer with a null phase list cannot be constructed.
                // (Contrast the siblings that do have an `aconst_null` path; those getters are
                // annotated @Nullable.) `.orEmpty()` stays: dropping it would trade the fallback for
                // an intrinsic that throws.
                phases = offer.pricingPhases.pricingPhaseList.orEmpty().map { phase ->
                    SubscriptionPricingPhase(
                        formattedPrice = phase.formattedPrice.orEmpty(),
                        billingPeriod = phase.billingPeriod.orEmpty(),
                        isRecurring = phase.recurrenceMode ==
                                ProductDetails.RecurrenceMode.INFINITE_RECURRING,
                        // Ordering only — never rendered. `formattedPrice` above is what the user
                        // sees, already localized by Play to their currency and conventions.
                        priceAmountMicros = phase.priceAmountMicros,
                        priceCurrencyCode = phase.priceCurrencyCode.orEmpty()
                    )
                }
            )
        }

    /**
     * TERMINAL teardown — safe ONLY at process shutdown (e.g. [android.app.Application.onTerminate]).
     *
     * This is a Koin `@Single`, so the same instance is reused for the whole process. Both the
     * [billingClient] (via [BillingClient.endConnection]) and [scope] (via cancel) are disposed
     * permanently and CANNOT be restarted on this instance. Do NOT call this mid-lifecycle
     * (e.g. on Support-sheet dismissal): a later billing interaction would then use a dead client
     * and a cancelled scope. A dismissal-driven teardown would first require changing the Koin
     * binding to factory/scoped, or making the client + scope lazily recreatable.
     *
     * The ladder is stopped first, and it is what makes the resume-driven re-arm safe here: a
     * post-close [refreshPurchases] gets `Stopped` and never reaches `scope.launch`. Cancelling the
     * scope is the second, independent guard — a coroutine launched on it would never run its body
     * — and a retry already sleeping past its `delay` finds `connectionState == CLOSED`. Three
     * checks for one invariant because a resume can arrive at any of the three moments.
     */
    override fun close() {
        reconnect.stop()
        try {
            billingClient.endConnection()
        } catch (e: Exception) {
            Logger.e("BillingProcessor", "Error ending billing connection", e)
        }
        scope.cancel()
    }

    private companion object {
        /** ~1 + 2 + 4 s of retries before the next sweep takes over. */
        const val MAX_ACKNOWLEDGE_ATTEMPTS = 4
    }
}
