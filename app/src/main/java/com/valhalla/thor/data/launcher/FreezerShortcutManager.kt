// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.valhalla.thor.R
import com.valhalla.thor.data.freezer.AppFreezeStateReader
import com.valhalla.thor.data.freezer.BulkFreezeRunner
import com.valhalla.thor.data.receivers.FreezerShortcutPinnedReceiver
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkOutcome
import com.valhalla.thor.domain.model.FreezeState
import com.valhalla.thor.domain.repository.AppShortcutController
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** Owns all launcher-shortcut plumbing for the Freezer feature. */
@Single(binds = [AppShortcutController::class])
class FreezerShortcutManager(
    private val context: Context,
    private val freezerRepository: FreezerRepository,
    private val bulkFreezeRunner: BulkFreezeRunner,
    private val stateReader: AppFreezeStateReader,
) : AppShortcutController {
    // App-scoped: bulk work must survive the (finishing) trampoline activity.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Solid launcher-tile backgrounds for the bulk action shortcuts (shared with the in-app preview).
    private val freezeShortcutBg = FreezerShortcutContract.FREEZE_TILE_COLOR
    private val unfreezeShortcutBg = FreezerShortcutContract.UNFREEZE_TILE_COLOR

    private companion object {
        const val LAUNCH_ACTIVITY = "com.valhalla.thor.presentation.launcher.FreezerLaunchActivity"
    }

    init {
        // Rebuild pinned icons off the runner's completions rather than off a call site.
        //
        // The QS tile calls BulkFreezeRunner.launch directly — correctly: a tile has no reason
        // to know shortcuts exist — so a rebuild hung off runBulk was reachable from the
        // launcher Freeze-all shortcut and from nowhere else. Apps froze from the tile and
        // their pinned icons stayed full colour.
        //
        // The dependency direction is forced: this class already holds the runner, so it
        // subscribes. The runner must not hold this class back (Koin cycle), which also keeps
        // it free of any launcher concern.
        //
        // Startup ordering gets this close: Koin builds this @Single as a constructor argument
        // of AutoFreezeManager, ThorApplication.onCreate calls autoFreezeManager.startObserving()
        // synchronously, and Application.onCreate always completes before the framework binds
        // FreezerTileService. But `scope.launch` only *schedules* the collector, so subscription
        // itself is not ordered against the first run — which is why `completions` carries
        // replay = 1. A replayed completion just costs one extra rebuild from live state.
        scope.launch {
            bulkFreezeRunner.completions.collect { rebuildPinnedIcons() }
        }
    }

    fun isPinSupported(): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Ask the launcher to pin a home-screen shortcut for an app. The icon follows the app's state
     *  (grey while frozen, full colour while enabled). Runs off the caller's thread — the icon
     *  decode is heavy — so any surface (dialog, details, freezer) can call this directly. */
    fun pinAppShortcut(packageName: String, label: String) {
        scope.launch { pinAppShortcutSuspend(packageName, label) }
    }

    /** Suspending pin so bulk callers can pin sequentially instead of spawning N concurrent bitmap
     *  decodes + binder pin requests (which risks OOM / overwhelming the shortcut service). */
    suspend fun pinAppShortcutSuspend(packageName: String, label: String) {
        val shortcut = buildAppShortcut(packageName, label)
        // A shortcut id previously greyed by disableShortcuts stays disabled on re-pin unless we
        // re-enable it — otherwise a re-frozen app comes back greyed/uninteractive.
        ShortcutManagerCompat.enableShortcuts(context, listOf(shortcut))
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, pinnedCallback(label).intentSender)
    }

    /** Update an already-pinned per-app shortcut so its icon reflects the app's current state.
     *  No-op if no such shortcut exists. Call after any freeze/unfreeze of the package. */
    fun refreshAppShortcut(packageName: String) {
        scope.launch { updateShortcutIcon(packageName) }
    }

    /** Ask the launcher to pin a Freeze-all / Unfreeze-all action shortcut. */
    fun pinBulkShortcut(action: String) {
        val shortcut = bulkShortcut(action)
        val label = shortcut.shortLabel.toString()
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, pinnedCallback(label).intentSender)
    }

    /** Publish (or remove) the Freeze-all + Unfreeze-all long-press dynamic shortcuts. */
    fun syncDynamicShortcuts(enabled: Boolean) {
        // Binder IPC — called from Main (cold-start + Settings); keep it off the caller's thread.
        scope.launch {
            if (enabled) {
                ShortcutManagerCompat.setDynamicShortcuts(
                    context,
                    listOf(
                        bulkShortcut(FreezerShortcutContract.ACTION_FREEZE_ALL),
                        bulkShortcut(FreezerShortcutContract.ACTION_UNFREEZE_ALL),
                    )
                )
            } else {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            }
        }
    }

    /** Grey out a per-app shortcut (the ceiling — pinned icons can't be silently removed). */
    override fun disableAppShortcut(packageName: String) {
        ShortcutManagerCompat.disableShortcuts(
            context,
            listOf(FreezerShortcutContract.appShortcutId(packageName)),
            context.getString(R.string.shortcut_no_longer_frozen)
        )
    }

    /**
     * Bulk freeze/unfreeze every package in the freezer, off the finishing activity.
     *
     * Returns the run so the caller can await its [BulkOutcome] and report it. The icon rebuild
     * is deliberately *not* part of that Deferred: it hangs off the runner's completions (see
     * the `init` block), so a caller that finishes early never truncates it, and a caller that
     * awaits does not wait on shortcut bookkeeping it does not care about.
     */
    fun runBulk(disable: Boolean): Deferred<BulkOutcome> =
        // Delegate so this shares the tile's candidate filter, Semaphore(5), deadline and
        // result reporting. It previously ran sequentially and discarded every Result.
        bulkFreezeRunner.launch(if (disable) BulkOp.FREEZE else BulkOp.UNFREEZE)

    /**
     * Fire-and-forget rebuild of every pinned per-app icon, for a caller that runs its own
     * batch instead of going through [BulkFreezeRunner] — currently only Settings'
     * Unfreeze-all. Runs on this manager's process-lifetime scope, so a finishing caller
     * cannot truncate it.
     */
    fun refreshPinnedShortcutIcons() {
        scope.launch { rebuildPinnedIcons() }
    }

    /**
     * Repaint every pinned per-app shortcut from live freeze state.
     *
     * No dedupe needed: `BulkFreezeRunner.launch` coalesces same-op taps onto one run, one run
     * emits one completion, and the completions buffer collapses a burst into a single trailing
     * rebuild. So impatient re-taps — the expected case, since the bulk shortcut shows nothing
     * for up to two seconds — cost one rebuild, not N concurrent icon decodes over every pinned
     * package. Correctness under coalescing comes from reading live state here rather than
     * trusting anything carried on the emission.
     */
    private suspend fun rebuildPinnedIcons() {
        try {
            val pinnedIds = pinnedShortcutIds()
            val updated = freezerRepository.getAllPackageNames()
                .filter { FreezerShortcutContract.appShortcutId(it) in pinnedIds }
                .mapNotNull { pkg -> appLabel(pkg)?.let { buildAppShortcut(pkg, it) } }
            if (updated.isNotEmpty()) {
                pushShortcutUpdate(updated)
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation — it breaks cooperative coroutine cancellation. This
            // only arrives when the process-lifetime scope itself dies, so ending the
            // completions collector with it is correct.
            throw e
        } catch (e: Exception) {
            // stateOf (called by buildAppShortcut) catches only NameNotFoundException; a
            // binder-death RuntimeException would otherwise escape to Android's default
            // uncaught handler and kill the process — and would also terminate the collector
            // for the rest of the process lifetime. Log and continue.
            Logger.e("FreezerShortcut", "pinned icon rebuild failed", e)
        }
    }

    // updateShortcuts reports failure by returning false, not by throwing. ShortcutManager
    // rate-limits a *background* publisher (~10 accepted calls per 24h, the counter reset
    // whenever the app has been in the foreground), and the tile path is background by
    // definition. A dropped update is indistinguishable on screen from "we never called it",
    // so log it rather than let the next report of this bug start from zero again.
    private fun pushShortcutUpdate(shortcuts: List<ShortcutInfoCompat>) {
        if (!ShortcutManagerCompat.updateShortcuts(context, shortcuts)) {
            Logger.d(
                "FreezerShortcut",
                "updateShortcuts rejected ${shortcuts.size} pinned icon(s) — rate-limited?"
            )
        }
    }

    private fun pinnedShortcutIds(): Set<String> =
        ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .mapTo(HashSet()) { it.id }

    private fun bulkShortcut(action: String): ShortcutInfoCompat {
        val spec = when (action) {
            FreezerShortcutContract.ACTION_FREEZE_ALL -> BulkSpec(
                FreezerShortcutContract.SHORTCUT_FREEZE_ALL,
                R.string.freeze_all_apps,
                R.drawable.frozen,
                freezeShortcutBg
            )
            FreezerShortcutContract.ACTION_UNFREEZE_ALL -> BulkSpec(
                FreezerShortcutContract.SHORTCUT_UNFREEZE_ALL,
                R.string.unfreeze_all_apps,
                R.drawable.unfreeze,
                unfreezeShortcutBg
            )
            else -> error("Unsupported bulk shortcut action: $action")
        }
        val label = context.getString(spec.labelRes)
        return ShortcutInfoCompat.Builder(context, spec.id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(bulkIcon(spec.iconRes, spec.background))
            .setIntent(trampolineIntent(action))
            .build()
    }

    private data class BulkSpec(val id: String, val labelRes: Int, val iconRes: Int, val background: Int)

    // A launcher-visible adaptive icon: the white-tinted glyph centred on a solid colour tile. The raw
    // frozen/unfreeze vectors are white-on-transparent (meant to be tinted by the host), so passing them
    // to createWithResource renders an invisible/white blob on the launcher — hence this composed bitmap.
    private fun bulkIcon(iconRes: Int, backgroundColor: Int): IconCompat {
        val size = 216
        val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        try {
            ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
                setTint(Color.WHITE)
                val inset = size / 4 // glyph fills the centre ~50%, within the adaptive safe zone
                setBounds(inset, inset, size - inset, size - inset)
                draw(canvas)
            }
        } catch (e: Exception) {
            // Fall back to a solid coloured tile rather than a broken/blank icon.
            Logger.e("FreezerShortcut", "bulk icon glyph load failed", e)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    // Fires (broadcast) ONLY when the launcher actually pins the shortcut — Android provides no
    // failure/cancel callback, so this confirms success and can't detect a user cancel.
    private fun pinnedCallback(label: String): PendingIntent {
        val intent = Intent(context, FreezerShortcutPinnedReceiver::class.java)
            .putExtra(FreezerShortcutPinnedReceiver.EXTRA_LABEL, label)
        return PendingIntent.getBroadcast(
            context,
            label.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Explicit-component intent → our (non-exported) trampoline, targeted by string class name so
    // this class doesn't compile-depend on FreezerLaunchActivity. Shortcuts require an action.
    private fun trampolineIntent(action: String): Intent =
        Intent().apply {
            setClassName(context, LAUNCH_ACTIVITY)
            this.action = Intent.ACTION_VIEW
            // Start the trampoline in its own task (it also declares an empty taskAffinity), so tapping
            // a shortcut never brings Thor's existing task to the foreground.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(FreezerShortcutContract.EXTRA_ACTION, action)
        }

    private fun buildAppShortcut(packageName: String, label: String): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, FreezerShortcutContract.appShortcutId(packageName))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(appIcon(packageName, grayscale = stateReader.stateOf(packageName) == FreezeState.FROZEN))
            .setIntent(
                trampolineIntent(FreezerShortcutContract.ACTION_LAUNCH)
                    .putExtra(FreezerShortcutContract.EXTRA_PACKAGE, packageName)
            )
            .build()

    // Rebuild + push the current-state icon for a package's pinned shortcut (no-op if absent).
    private fun updateShortcutIcon(packageName: String) {
        val label = appLabel(packageName) ?: return
        pushShortcutUpdate(listOf(buildAppShortcut(packageName, label)))
    }

    private fun appLabel(packageName: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(
            pm.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        ).toString()
    } catch (e: Exception) {
        null
    }

    // The app's own icon, optionally desaturated to grey (used while the app is frozen).
    private fun appIcon(packageName: String, grayscale: Boolean): IconCompat {
        return try {
            val src = context.packageManager.getApplicationIcon(packageName).toBitmap()
            val out = if (grayscale) {
                createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { gray ->
                    Canvas(gray).drawBitmap(
                        src, 0f, 0f,
                        Paint().apply {
                            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                        }
                    )
                }
            } else {
                src
            }
            IconCompat.createWithBitmap(out)
        } catch (e: Exception) {
            Logger.e("FreezerShortcut", "icon load failed for $packageName", e)
            IconCompat.createWithResource(context, R.drawable.frozen)
        }
    }
}
