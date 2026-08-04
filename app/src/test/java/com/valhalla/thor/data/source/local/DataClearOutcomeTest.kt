// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import android.content.pm.IPackageDataObserver
import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What [awaitDataObserver] concludes from what the platform did or did not say.
 *
 * The whole point of the function is one asymmetry: **only a `true` callback may produce
 * [DataClearOutcome.CLEARED]**, and every other way the world can go — a refusal, silence, a throw,
 * a verdict that arrives too late, a second verdict that disagrees with the first — must land on
 * [DataClearOutcome.UNVERIFIED] or [DataClearOutcome.REFUSED]. That asymmetry is the fix; the three
 * call sites it feeds all collapse it to a `Boolean`, so a bug here reappears as the exact thing
 * this change exists to retire: Thor telling a user their data is gone on the strength of a dispatch
 * receipt. These tests are therefore mostly negative, and the roll-up at the end restates the
 * invariant over every failure shape at once so that a new one added later has somewhere obvious to
 * go.
 *
 * ### What this file cannot test, and does not pretend to
 *
 * Nothing here touches binder. On the JVM the observer is necessarily the non-binder fallback in
 * `newDataObserver` (`the fallback is the JVM fixture`, below, pins that), so what is exercised is
 * the latch, the timeout and the outcome mapping — not marshalling, not `onTransact`, not whether
 * `PackageManagerService` calls back at all. Those need a device, and the R8 keep rule that protects
 * the callback in release is verifiable only in a minified build. Read the assertions here as "given
 * a verdict, Thor draws the right conclusion", never as "the verdict arrives".
 *
 * It also depends on `Logger.isDebug` still being `false`, which it is in unit tests because only
 * `ThorApplication` and `ThorRootService` ever set it and neither runs here. If some future test
 * flips that global, the `android.util.Log` calls inside [awaitDataObserver] start throwing
 * `"… not mocked"` and this file goes red for a reason that has nothing to do with it.
 */
class DataClearOutcomeTest {

    private val pkg = "com.example.victim"

    /**
     * Short enough that four timeout cases cost under a second, long enough that a loaded CI machine
     * cannot lose a *synchronous* callback to scheduling. Every synchronous case counts the latch
     * down before `await` is ever reached, so the only tests this number can make flaky are the ones
     * that are deliberately waiting it out.
     */
    private val shortTimeoutMs = 150L

    /**
     * The one input that means success.
     *
     * `succeeded = true` on `onRemoveCompleted` is `PackageManagerService` saying it did the work.
     * It is also the only sentence in this whole mechanism that Thor is entitled to relay to a user
     * as "done".
     */
    @Test
    fun `a true callback is the only thing that means cleared`() {
        val outcome = awaitDataObserver("test", pkg, shortTimeoutMs) { observer ->
            observer.onRemoveCompleted(pkg, true)
        }

        assertEquals(DataClearOutcome.CLEARED, outcome)
    }

    /**
     * A refusal is an answer, and is worth keeping distinct from silence even though both end up
     * `false` at the call site.
     *
     * This is the case the fix was written for: at shell uid, PMS *accepts* a cache clear, logs that
     * it is ignoring it, and reports `succeeded = false` on the observer nobody was passing. The
     * distinction survives only in the log — the callers reduce [DataClearOutcome.REFUSED] and
     * [DataClearOutcome.UNVERIFIED] to the same `false` — which is why a bug report can tell "the
     * platform said no" from "we never heard back" and the return type cannot.
     */
    @Test
    fun `a false callback is a refusal, not a failure to ask`() {
        val outcome = awaitDataObserver("test", pkg, shortTimeoutMs) { observer ->
            observer.onRemoveCompleted(pkg, false)
        }

        assertEquals(DataClearOutcome.REFUSED, outcome)
        assertNotEquals(DataClearOutcome.UNVERIFIED, outcome)
    }

    /**
     * A throw out of `fire` is [DataClearOutcome.UNVERIFIED], not [DataClearOutcome.REFUSED], and
     * the difference is not cosmetic.
     *
     * The design note this implements said to map a throw to REFUSED. That is wrong from inside the
     * function: a `SecurityException` from PMS and a binder that died before PMS ever saw the call
     * arrive here as the same object, and calling the second one "refused" claims knowledge of a
     * conversation that never happened. UNVERIFIED is the honest reading of both, and since both
     * collapse to `false` at every caller, nothing is lost by refusing to guess.
     */
    @Test
    fun `fire throwing is unverified, never refused`() {
        val outcome = awaitDataObserver("test", pkg, shortTimeoutMs) {
            throw SecurityException("Neither user 2000 nor current process has CLEAR_APP_USER_DATA")
        }

        assertEquals(DataClearOutcome.UNVERIFIED, outcome)
    }

    /**
     * The catch is on `Throwable` on purpose, and this is the case that proves it.
     *
     * `NoClassDefFoundError` is not hypothetical here: it is precisely what a device that stopped
     * shipping `android.content.pm.IPackageDataObserver` would produce, which is the scenario the
     * vendored aidl's header argues must degrade to "could not confirm". Narrowing this to
     * `Exception` would let that one escape the function entirely and take the call site's
     * `runCatching` with it — still not a false success, but a crash instead of an answer.
     */
    @Test
    fun `an Error is absorbed just as an Exception is`() {
        val outcome = awaitDataObserver("test", pkg, shortTimeoutMs) {
            throw NoClassDefFoundError("android/content/pm/IPackageDataObserver")
        }

        assertEquals(DataClearOutcome.UNVERIFIED, outcome)
    }

    /**
     * A verdict that arrives and is then buried by a throw does not count.
     *
     * This is the sharpest form of the invariant, and the one place where the implementation
     * deliberately reports *less* than it knows: the callback has already stored CLEARED, and the
     * `catch` still returns a hard UNVERIFIED rather than reading the stored value back. Answering
     * `outcome.get()` there would look like a free improvement and would be a false success — a
     * `fire` that both called back and then blew up has not established that the clear it reported
     * is the clear that finished.
     */
    @Test
    fun `a verdict followed by a throw is still unverified`() {
        val outcome = awaitDataObserver("test", pkg, shortTimeoutMs) { observer ->
            observer.onRemoveCompleted(pkg, true)
            throw IllegalStateException("the transport died on the way home")
        }

        assertEquals(DataClearOutcome.UNVERIFIED, outcome)
    }

    /**
     * Silence times out, and times out as a failure.
     *
     * `clearApplicationUserData` returns `void` and `deleteApplicationCacheFiles` returns a boolean
     * about nothing, so "the call returned and nothing else happened" is exactly what the old code
     * reported as success. It is now the definition of not knowing.
     */
    @Test
    fun `a verdict that never arrives is unverified`() {
        val started = System.nanoTime()
        val outcome = awaitDataObserver("test", pkg, shortTimeoutMs) { /* dispatched into the void */ }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(DataClearOutcome.UNVERIFIED, outcome)
        assertTrue(
            "returned after ${elapsedMs}ms, which is short of the ${shortTimeoutMs}ms it was told " +
                "to wait — the wait is not actually happening",
            elapsedMs >= shortTimeoutMs
        )
    }

    /**
     * First verdict wins, in the direction that matters.
     *
     * One `IPackageDataObserver` covers one operation, so a second `onRemoveCompleted` should not
     * happen at all — but "should not happen" is not a guarantee about someone else's process. The
     * compare-and-set makes the second one inert, and the case worth pinning is `false` then `true`:
     * without it, a stray success could overturn a refusal Thor had already heard and turn the one
     * honest answer in the sequence into a false "done".
     */
    @Test
    fun `the first verdict wins and a second cannot overturn it`() {
        val refusedThenCleared = awaitDataObserver("test", pkg, shortTimeoutMs) { observer ->
            observer.onRemoveCompleted(pkg, false)
            observer.onRemoveCompleted(pkg, true)
        }
        assertEquals(DataClearOutcome.REFUSED, refusedThenCleared)

        val clearedThenRefused = awaitDataObserver("test", pkg, shortTimeoutMs) { observer ->
            observer.onRemoveCompleted(pkg, true)
            observer.onRemoveCompleted(pkg, false)
        }
        assertEquals(DataClearOutcome.CLEARED, clearedThenRefused)
    }

    /**
     * A verdict that lands after the wait expired changes nothing, and does not blow up on its way
     * to being ignored.
     *
     * The `assertEquals` after the gate is a restatement — `outcome` is a `val` that left the
     * function before the callback ran, so it could not have changed. The load-bearing assertion is
     * the one above it: the late callback runs to completion. Counting down a latch nobody is
     * waiting on is harmless, but only because the function holds no state that outlives it; if a
     * future version registered the observer anywhere, this is the test that would notice.
     *
     * A real device produces this every time the timeout is the thing that fires — PMS is slow, not
     * absent — so it is an ordinary path, not a pathological one.
     */
    @Test
    fun `a verdict that arrives after the wait expired changes nothing`() {
        val gate = CountDownLatch(1)
        val lateCallbackFinished = CountDownLatch(1)

        val outcome = awaitDataObserver("test", pkg, shortTimeoutMs) { observer ->
            Thread {
                gate.await()
                observer.onRemoveCompleted(pkg, true)
                lateCallbackFinished.countDown()
            }.apply { isDaemon = true }.start()
        }

        assertEquals(DataClearOutcome.UNVERIFIED, outcome)

        gate.countDown()
        assertTrue(
            "the late callback never finished — something in it threw",
            lateCallbackFinished.await(5, TimeUnit.SECONDS)
        )
        assertEquals(DataClearOutcome.UNVERIFIED, outcome)
    }

    /**
     * The verdict is believed even when it names a different package, and that is deliberate.
     *
     * One observer instance is created per call and handed to exactly one operation, so the
     * `packageName` the framework echoes back is diagnostic, not an identity check — it is logged
     * so a bug report can show a mismatch, and nothing branches on it. Rejecting a mismatched name
     * would trade a real verdict for a timeout on the strength of a string an OEM might normalise.
     */
    @Test
    fun `a callback naming another package is still this call's verdict`() {
        val outcome = awaitDataObserver("test", pkg, shortTimeoutMs) { observer ->
            observer.onRemoveCompleted("com.example.somethingelse", true)
        }

        assertEquals(DataClearOutcome.CLEARED, outcome)
    }

    /**
     * A `null` package name in the callback is survivable.
     *
     * The generated `Stub` unparcels a `@Nullable String`, so `null` is representable on this
     * interface whatever AOSP's own callers do with it. It reaches nothing but string interpolation
     * in a log line, and this pins that it stays that way.
     */
    @Test
    fun `a callback with no package name still carries its verdict`() {
        val outcome = awaitDataObserver("test", pkg, shortTimeoutMs) { observer ->
            observer.onRemoveCompleted(null, true)
        }

        assertEquals(DataClearOutcome.CLEARED, outcome)
    }

    /**
     * The invariant, restated over every failure shape at once.
     *
     * The tests above each say why one case matters; this one says the thing they have in common,
     * and is the test to extend when a new way of failing turns up. Its value is that it fails for a
     * change that "improves" one branch — reading the stored outcome back after a throw, honouring a
     * late callback, treating an empty result as fine — without anyone having to notice which
     * individual test it belonged to.
     */
    @Test
    fun `no path except a true callback yields CLEARED`() {
        val waysToNotSucceed: List<Pair<String, (IPackageDataObserver) -> Unit>> = listOf(
            "dispatched and never answered" to { _: IPackageDataObserver -> },
            "refused" to { o: IPackageDataObserver -> o.onRemoveCompleted(pkg, false) },
            "refused twice" to { o: IPackageDataObserver ->
                o.onRemoveCompleted(pkg, false)
                o.onRemoveCompleted(pkg, false)
            },
            "refused, then a stray success" to { o: IPackageDataObserver ->
                o.onRemoveCompleted(pkg, false)
                o.onRemoveCompleted(pkg, true)
            },
            "threw a SecurityException" to { _: IPackageDataObserver ->
                throw SecurityException("denied")
            },
            "threw an Error" to { _: IPackageDataObserver ->
                throw NoClassDefFoundError("android/content/pm/IPackageDataObserver")
            },
            "succeeded, then threw" to { o: IPackageDataObserver ->
                o.onRemoveCompleted(pkg, true)
                throw IllegalStateException("boom")
            },
            "answered only after the wait expired" to { o: IPackageDataObserver ->
                Thread {
                    Thread.sleep(shortTimeoutMs * 4)
                    o.onRemoveCompleted(pkg, true)
                }.apply { isDaemon = true }.start()
            },
        )

        waysToNotSucceed.forEach { (description, fire) ->
            val outcome = awaitDataObserver("test", pkg, shortTimeoutMs, fire)
            assertNotEquals(
                "\"$description\" was reported as a completed wipe",
                DataClearOutcome.CLEARED,
                outcome
            )
        }
    }

    /**
     * On the JVM the observer is the non-binder fallback, which is why any of the above runs at all.
     *
     * `object : IPackageDataObserver.Stub()` cannot be constructed here: the generated constructor
     * calls `Binder.attachInterface`, and in AGP's mockable `android.jar` every non-constructor
     * method is rewritten to throw `"Method … not mocked"` (constructors survive as a bare
     * `super()`, which is the same rewrite `FakeContext` in `ViewModelTestDoubles` leans on from the
     * other side). Without the fallback in `newDataObserver`, every case in this file would collapse
     * to UNVERIFIED for the same uninteresting reason and the file would prove nothing.
     *
     * **If this assertion ever fails, it is good news and this file needs re-reading, not fixing**:
     * it would mean the `Stub` is now constructible on the JVM, the fallback is no longer the thing
     * these tests exercise, and the coverage above just silently got better. It is asserted rather
     * than assumed so that the change announces itself.
     */
    @Test
    fun `the fallback is the JVM fixture`() {
        var captured: IPackageDataObserver? = null

        awaitDataObserver("test", pkg, shortTimeoutMs) { observer ->
            captured = observer
            observer.onRemoveCompleted(pkg, true)
        }

        val observer = captured
        assertNotNull("fire was never called", observer)
        assertFalse(
            "the observer is a real Binder — AGP's mockable android.jar has changed and these " +
                "tests now exercise the Stub path instead of the fallback",
            observer is Binder
        )
    }
}
