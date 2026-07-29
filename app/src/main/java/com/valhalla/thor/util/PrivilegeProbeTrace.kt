// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.util

import android.os.Process
import android.os.SystemClock
import com.valhalla.thor.BuildConfig
import java.util.concurrent.atomic.AtomicLongArray

/** The three probes `PrivilegeManager` runs, in the order they are logged. */
enum class PrivilegeProbeTier { ROOT, SHIZUKU, DHIZUKU }

/**
 * Debug-only stopwatch for one privilege probe run.
 *
 * The app deliberately holds its loaders until the first probe lands (`isLoading = !priv.isReady`
 * in AppListViewModel/FreezerViewModel) so privileged controls never flash disabled, which puts
 * probe latency directly on the cold-start critical path. The root probe is the one that can
 * actually hurt: it blocks on Odin's shell init, bounded only by that library's 10s timeout. This
 * exists so a slow tier can be *identified* instead of guessed at — the protocol for running and
 * reading it is `docs/follow-ups/privilege-manager-cold-start.md`.
 *
 * Non-invasive by construction: no extra suspension point, no dispatcher hop, no extra probe. Each
 * tier is timed inside the coroutine that already runs it ([timeProbe] is `inline`), so what is
 * measured is what ships.
 *
 * Zero-cost in release: [start] is `inline` and `BuildConfig.DEBUG` is a compile-time `false`
 * there, so the trace folds to `null`, [timeProbe] collapses to a bare `block()`, and no clock is
 * read, no array allocated and no message string built.
 *
 * Monotonic clock only. A wall-clock read straddling an NTP step produces a negative or absurd
 * duration, which is worse than no measurement because it still looks like data.
 */
class PrivilegeProbeTrace(private val runStartedAtMs: Long) {

    // Written by three different probe coroutines and read by whichever finishes last; atomic so
    // visibility does not rest on await()'s happens-before edge being reasoned about correctly.
    private val tierMs = AtomicLongArray(PrivilegeProbeTier.entries.size)

    fun record(tier: PrivilegeProbeTier, elapsedMs: Long) {
        tierMs.set(tier.ordinal, elapsedMs)
    }

    /**
     * One line per probe run: total, then each tier's duration and result.
     *
     * The tier times overlap — the probes run concurrently — so they do not sum to `total`. The
     * tier closest to `total` is the one holding the run up; a `total` well above every tier means
     * the cost is dispatch/thread starvation, not any single probe.
     */
    fun logRun(root: Boolean, shizuku: Boolean, dhizuku: Boolean) {
        val total = SystemClock.elapsedRealtime() - runStartedAtMs
        Logger.d(
            TAG,
            "probe total=${total}ms" +
                    " root=${tierMs.get(PrivilegeProbeTier.ROOT.ordinal)}ms/$root" +
                    " shizuku=${tierMs.get(PrivilegeProbeTier.SHIZUKU.ordinal)}ms/$shizuku" +
                    " dhizuku=${tierMs.get(PrivilegeProbeTier.DHIZUKU.ordinal)}ms/$dhizuku"
        )
    }

    companion object {
        /** Stable and greppable: `adb logcat -s ThorPrivPerf`. */
        const val TAG = "ThorPrivPerf"

        /** `null` — and therefore compiled out along with every call it feeds — outside debug. */
        // Inlined for constant folding, not for call overhead: only at the call site can the
        // compiler see `BuildConfig.DEBUG == false`, prove `trace` is null and drop the rest.
        @Suppress("NOTHING_TO_INLINE")
        inline fun start(): PrivilegeProbeTrace? =
            if (BuildConfig.DEBUG) PrivilegeProbeTrace(SystemClock.elapsedRealtime()) else null

        /**
         * The number cold start actually waits on: process start -> first `isReady`.
         *
         * Logged separately from [logRun] because the two are not the same. `isReady` is published
         * by a `combine` of the probe run *and* the DataStore preference read, and it is preceded
         * by process init, Koin start and the first ViewModel resolution (PrivilegeManager is a
         * lazy `@Single`, so the probe does not even begin until something injects it). A fast
         * probe run and a slow time-to-ready means the cost is somewhere other than the probes.
         */
        fun logFirstReady(active: String) {
            val sinceProcessStart =
                SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
            Logger.d(TAG, "ready sinceProcessStart=${sinceProcessStart}ms active=$active")
        }
    }
}

/**
 * Times [block] into [tier] and returns its result untouched; a plain pass-through when the
 * receiver is null (release, or any caller that did not start a trace).
 *
 * `inline` on purpose: a non-inline `suspend` wrapper would wrap the probe in a continuation, and
 * one carrying a context would add a dispatcher hop — both charged to the thing being measured.
 * `finally` rather than a straight-line record so a probe that throws still reports its cost.
 */
inline fun PrivilegeProbeTrace?.timeProbe(
    tier: PrivilegeProbeTier,
    block: () -> Boolean
): Boolean {
    if (this == null) return block()
    val startedAt = SystemClock.elapsedRealtime()
    try {
        return block()
    } finally {
        record(tier, SystemClock.elapsedRealtime() - startedAt)
    }
}
