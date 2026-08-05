// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.content.pm.IPackageDataObserver
import android.os.IBinder
import com.valhalla.thor.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * How long [awaitDataObserver] waits for a verdict before giving up on one.
 *
 * The same number and the same "one bounded wait, then give up" shape as `awaitInstallerResult`'s
 * default in `ShizukuReflector`. One constant in one file on purpose: this is not a per-gateway
 * knob, and a privilege mode that needed a different answer here would be telling us something
 * about its transport rather than about its timeout.
 */
private const val DATA_OBSERVER_TIMEOUT_MS = 15_000L

/**
 * The wait [awaitDataObserver] falls back to once this transport has been *seen* to drop a verdict.
 *
 * ### Why a second number exists at all
 *
 * The full [DATA_OBSERVER_TIMEOUT_MS] is priced for one app: a user taps "clear data", and fifteen
 * seconds is a defensible ceiling for `PackageManagerService` to finish wiping a large package.
 * Batches change that arithmetic. `MainViewModel.performLoggedMultiAction` runs its list through one
 * sequential `forEachIndexed` — deliberately, because it emits an ordered "step i of n" log — so a
 * transport that never calls back turns a fifty-app cache sweep into fifty consecutive full-length
 * waits. Twelve and a half minutes of them, every one ending in [DataClearOutcome.UNVERIFIED].
 *
 * ### Why one observation is enough to change the wait
 *
 * Whether `onRemoveCompleted` comes back is a property of the **ROM and the transport**, not of the
 * package being cleared. A vendor build that drops the callback drops it for every package; there is
 * no version of this where app 1 goes unanswered and app 2 is fine. So the first full-length timeout
 * is not bad luck to be re-rolled forty-nine more times, it is evidence — and the honest response to
 * evidence is to stop paying full price for it.
 *
 * ### Why it is a shortened wait and not a skipped one
 *
 * Skipping would make [DataClearOutcome.UNVERIFIED] structural: no observer could ever answer again,
 * on any ROM, once one had failed to. A ROM that is merely *slow* would be permanently misreported.
 * Shortening keeps the callback path live and costs at most this much per package. The belief is
 * cleared by the very next verdict that does arrive — see [transportDeliversVerdicts] — so a single
 * genuinely slow wipe degrades one subsequent call and then repairs itself.
 *
 * The trade this buys, stated plainly rather than hidden: a package whose verdict would have landed
 * between this and [DATA_OBSERVER_TIMEOUT_MS] is reported [DataClearOutcome.UNVERIFIED] instead of
 * its real answer. That window only opens on a transport that has just demonstrably failed to answer
 * within fifteen seconds, and it closes on the first answer of any kind.
 */
private const val DEGRADED_OBSERVER_TIMEOUT_MS = 1_500L

/**
 * Whether the last [awaitDataObserver] to reach a conclusion got there via the callback.
 *
 * `true` — the optimistic start, and where any arriving verdict puts it back — means the next call
 * waits the full [DATA_OBSERVER_TIMEOUT_MS]. `false`, set only by a wait that expired with nothing
 * delivered, drops the next call to [DEGRADED_OBSERVER_TIMEOUT_MS].
 *
 * Process-wide on purpose. The thing being remembered is a fact about the transport, and every clear
 * in the process shares one of those. Threading it through `SystemRepositoryImpl` →
 * `ShizukuSystemGateway` → `ShizukuReflector` to make it per-batch would carry a global fact down
 * four layers as a parameter and still be a global fact.
 *
 * It is only ever a *timeout* input. Nothing here can produce [DataClearOutcome.CLEARED] — that
 * invariant is unchanged and this flag cannot touch it.
 */
private val transportDeliversVerdicts = AtomicBoolean(true)

/**
 * Puts [transportDeliversVerdicts] back to its optimistic start.
 *
 * Exists for tests, which would otherwise be order-dependent: one case that deliberately times out
 * would shorten the wait of whichever case JUnit happened to run next. Call it in `@Before`.
 */
internal fun resetObserverTransportBelief() {
    transportDeliversVerdicts.set(true)
}

/**
 * What a clear-data / clear-cache call actually achieved, as opposed to what it returned.
 *
 * The distinction that matters is not [CLEARED] versus [REFUSED] — both are the platform speaking —
 * but either of those versus [UNVERIFIED], which is Thor admitting it does not know. Every caller
 * collapses this to a `Boolean`, and both non-[CLEARED] values collapse to `false`. That is the
 * conservative direction and it is deliberate: clearing data twice costs a user nothing, whereas a
 * false "done" costs them the chance to try a privilege mode that would have worked.
 */
internal enum class DataClearOutcome {
    /** `onRemoveCompleted(pkg, succeeded = true)` arrived. The only value that means success. */
    CLEARED,

    /** `onRemoveCompleted(pkg, succeeded = false)` arrived. The platform said no, and we heard it. */
    REFUSED,

    /**
     * Nothing arrived, or the attempt fell over before anything could. **Not** a success, and not a
     * refusal either — it is the absence of an answer, and the only honest thing to do with it is
     * report failure and say so in the log.
     */
    UNVERIFIED,
}

/**
 * Runs [fire] with a real `IPackageDataObserver` and waits, once and briefly, for the verdict.
 *
 * ### Why this exists
 *
 * `IPackageManager.clearApplicationUserData(String, IPackageDataObserver, int)` returns `void`, and
 * `deleteApplicationCacheFiles(String, IPackageDataObserver)` returns a boolean that says nothing
 * about the outcome. In both cases `PackageManagerService` posts the work to its own handler and
 * reports the result on `onRemoveCompleted`. Every call site in Thor used to pass `null` there and
 * report success on "the binder call did not throw" — which at shell uid is a claim PMS actively
 * contradicts: it accepts cache clears it then declines, logs that it is silently ignoring the
 * request, and tells the caller none of it.
 *
 * ### Why the observer is a real Stub subclass
 *
 * A `java.lang.reflect.Proxy` cannot be marshalled over binder, so the callback has to be a genuine
 * `IPackageDataObserver.Stub`. `Stub` is not in the public SDK, so Thor vendors AOSP's
 * `app/src/main/aidl/android/content/pm/IPackageDataObserver.aidl` purely to give the Kotlin
 * compiler something to subclass. At runtime `PathClassLoader` delegates parent-first, the boot
 * classpath's framework class wins, and the generated copy is never loaded — so the object built
 * below extends the *framework* Stub and the framework's own `onTransact` dispatches to it. Read
 * that aidl's header comment for the full argument; the consequence here is that R8 must not rename
 * `onRemoveCompleted` (see the keep rule in `app/proguard-rules.pro`), because `onTransact` looks it
 * up by its original name and descriptor.
 *
 * ### Threading
 *
 * The callback arrives on a **binder thread**, never the caller's, so this cannot self-deadlock —
 * and this function **blocks** the calling thread on a latch for up to [timeoutMillis]. That is
 * safe because every caller is already off the main thread, which was checked rather than assumed:
 * the Shizuku sites are reached through `SystemRepositoryImpl.clearCache` / `clearAppData`, both of
 * which are `withContext(Dispatchers.IO)`, and the daemon site runs on a binder thread in the
 * separate `:root` process. A caller that is not one of those two must hop off the main thread
 * before calling this.
 *
 * It deliberately uses a `CountDownLatch` rather than the `CompletableDeferred` +
 * `withTimeoutOrNull` shape `awaitInstallerResult` uses, for one reason only: none of the three call
 * sites is `suspend`, and making them so would mean editing `ShizukuReflector` and
 * `ShizukuSystemGateway` for no behavioural gain. The parts of the house pattern that actually
 * matter are kept — the observer exists *before* the operation is dispatched, so the callback can
 * never be missed; the wait is bounded; and a timeout is not a success. There is nothing to
 * unregister afterwards, which is why there is no `finally`: unlike a `BroadcastReceiver` the
 * observer is not registered with anything, it is just an object the framework holds until it calls
 * back or is collected.
 *
 * ### The one invariant
 *
 * **Only the callback can produce [DataClearOutcome.CLEARED].** The result starts at
 * [DataClearOutcome.UNVERIFIED] and the sole write to it is the callback's; every other path —
 * [fire] throwing, the class being missing, the latch timing out, a verdict arriving *after* the
 * timeout, anything at all going wrong — leaves it where it started. That is a property of the
 * shape of this function rather than of remembering to handle each case, which is the point.
 *
 * First verdict wins. A second `onRemoveCompleted` is logged and discarded, so a late `true` can
 * never upgrade a refusal into a success.
 *
 * @param tag the log tag of the calling site, so a bug report can tell the daemon's clear from
 *   Shizuku's.
 * @param packageName the package being cleared; used for logging and to spot a callback that names
 *   something else.
 * @param timeoutMillis the longest this will wait for the verdict. Defaults to
 *   [DATA_OBSERVER_TIMEOUT_MS]; only tests should pass anything else. The wait actually used is this
 *   or [DEGRADED_OBSERVER_TIMEOUT_MS], whichever is smaller, once [transportDeliversVerdicts] has
 *   been knocked down — so this is a ceiling, never a floor.
 * @param fire dispatches the actual privileged call, handing it the observer. It must not swallow
 *   its own failures — a throw here is information, and it is turned into
 *   [DataClearOutcome.UNVERIFIED].
 */
internal fun awaitDataObserver(
    tag: String,
    packageName: String,
    timeoutMillis: Long = DATA_OBSERVER_TIMEOUT_MS,
    fire: (IPackageDataObserver) -> Unit,
): DataClearOutcome {
    // Starts at UNVERIFIED and only ever moves once, from the callback. Nothing below writes it.
    val outcome = AtomicReference(DataClearOutcome.UNVERIFIED)
    val latch = CountDownLatch(1)

    val observer = newDataObserver(tag, packageName) { reportedPackage, succeeded ->
        val verdict = if (succeeded) DataClearOutcome.CLEARED else DataClearOutcome.REFUSED
        // Set before the CAS, and set by *any* callback including a late or duplicate one. This flag
        // answers "does this transport deliver?", and a verdict that arrived too late to be used
        // still answers it yes.
        transportDeliversVerdicts.set(true)
        if (outcome.compareAndSet(DataClearOutcome.UNVERIFIED, verdict)) {
            Logger.d(tag, "clear($packageName): observer reported $verdict for $reportedPackage")
            latch.countDown()
        } else {
            // Not expected, and not trusted either: whichever verdict arrived first is the one this
            // call is judged by, so a second callback cannot turn a REFUSED into a CLEARED.
            Logger.w(
                tag,
                "clear($packageName): a second observer callback (succeeded=$succeeded) arrived " +
                    "after ${outcome.get()} and was ignored"
            )
        }
    }

    // `minOf`, not a plain substitution: a test that asks for 50ms must still get 50ms, and the
    // degraded path is a ceiling on the wait rather than a value for it.
    val effectiveTimeoutMillis =
        if (transportDeliversVerdicts.get()) timeoutMillis
        else minOf(timeoutMillis, DEGRADED_OBSERVER_TIMEOUT_MS)

    return try {
        fire(observer)

        if (latch.await(effectiveTimeoutMillis, TimeUnit.MILLISECONDS)) {
            outcome.get()
        } else {
            // Read nothing back here. A verdict that lands a microsecond after the wait expired is
            // still a verdict Thor did not see in time, and honouring it would make the result
            // depend on scheduler luck.
            transportDeliversVerdicts.set(false)
            val shortened =
                if (effectiveTimeoutMillis < timeoutMillis) " (shortened from ${timeoutMillis}ms " +
                    "because the previous wait went unanswered)" else ""
            Logger.w(
                tag,
                "clear($packageName): issued but unconfirmed within ${effectiveTimeoutMillis}ms" +
                    "$shortened — reporting failure, because a clear that cannot be confirmed is " +
                    "not a clear that happened"
            )
            DataClearOutcome.UNVERIFIED
        }
    } catch (t: Throwable) {
        // Deliberately Throwable and deliberately a hard UNVERIFIED rather than `outcome.get()`.
        // A refusal that arrives as an exception (SecurityException from PMS) and a transport that
        // died before PMS ever saw the call are indistinguishable from here, and both collapse to
        // `false` at every caller, so inventing a REFUSED out of one would be a claim this function
        // is not in a position to make.
        Logger.e(tag, "clear($packageName): the privileged call failed before any verdict", t)
        DataClearOutcome.UNVERIFIED
    }
}

/**
 * The callback object handed to [awaitDataObserver]'s `fire`.
 *
 * Normally an `IPackageDataObserver.Stub` subclass — the only shape that can cross a binder, and
 * the whole reason the aidl is vendored.
 *
 * The fallback is not defensive padding, it has exactly one known trigger: a JVM unit test. The
 * generated `Stub()` constructor calls `Binder.attachInterface`, and in AGP's mockable `android.jar`
 * every non-constructor method throws `"… not mocked"` — the same rewrite `FakeContext` in
 * `ViewModelTestDoubles` relies on from the other side (constructors survive as a bare `super()`).
 * Without the fallback, `DataClearOutcomeTest` could not reach a single line of the latch logic.
 *
 * On a device the fallback is unreachable, and if it somehow were reached it would fail safe rather
 * than quietly: a plain interface implementation is not a `Binder`, so writing it into a parcel
 * throws, `fire` throws, and the outcome is [DataClearOutcome.UNVERIFIED]. [asBinder] throwing is
 * what guarantees that — it must never return some *other* binder, which the framework would
 * happily call back instead of us.
 */
private fun newDataObserver(
    tag: String,
    packageName: String,
    onVerdict: (reportedPackage: String?, succeeded: Boolean) -> Unit,
): IPackageDataObserver =
    try {
        object : IPackageDataObserver.Stub() {
            override fun onRemoveCompleted(reportedPackage: String?, succeeded: Boolean) {
                onVerdict(reportedPackage, succeeded)
            }
        }
    } catch (t: Throwable) {
        Logger.w(
            tag,
            "clear($packageName): IPackageDataObserver.Stub could not be constructed (${t.message}) " +
                "— falling back to a non-binder observer, which no framework call can reach"
        )
        object : IPackageDataObserver {
            override fun onRemoveCompleted(reportedPackage: String?, succeeded: Boolean) {
                onVerdict(reportedPackage, succeeded)
            }

            override fun asBinder(): IBinder =
                throw UnsupportedOperationException(
                    "this IPackageDataObserver is not a Binder and must not be marshalled"
                )
        }
    }
