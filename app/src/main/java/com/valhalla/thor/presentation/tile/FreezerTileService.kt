// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.service.quicksettings.TileService
import com.valhalla.thor.R
import com.valhalla.thor.data.freezer.PrivilegeSweepTargetResolver
import com.valhalla.thor.data.freezer.launchSurfaceSweep
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkRequest
import com.valhalla.thor.domain.model.PrivilegeSweepPhase
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.PrivilegeSweepStatus
import com.valhalla.thor.domain.repository.PrivilegeSweepController
import com.valhalla.thor.util.AppLocale
import com.valhalla.thor.util.LocalizedResources
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Quick Settings tile that bulk-freezes the freezer watchlist.
 *
 * The tile owns no work. [the durable sweep controller] is an app-scoped @Single that runs the batch and
 * publishes its state, so a QS shade collapse destroying this service cannot truncate a
 * freeze and cannot leave anything retaining the destroyed instance. This service only
 * observes and paints.
 *
 * The retention claim holds unconditionally: [scope] is cancelled in [onStopListening], again
 * defensively at the top of [onStartListening], and again in [onDestroy] — so no lifecycle
 * ordering leaves a live collector holding this instance.
 */
class FreezerTileService : TileService() {

    private val sweepResolver: PrivilegeSweepTargetResolver by inject()
    private val sweepController: PrivilegeSweepController by inject()
    private val privilegeManager: PrivilegeManager by inject()

    private var scope: CoroutineScope? = null
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val freezableCount = MutableStateFlow<Int?>(null)
    private var latestStatus: PrivilegeSweepStatus? = null

    /**
     * Applies the chosen locale on API 28–32 to the strings **this service** resolves.
     *
     * `Service` extends `ContextWrapper` and is attached the same way an Activity is, so the same
     * wrap works here. What it covers is everything [paint] reads: `tile_checking`, `tile_freezing`,
     * `tile_no_privilege`, `tile_no_apps`, the `freezer` content description and
     * the retained sweep summary.
     *
     * **What it does not cover, on any API level:** the tile's *label* and *icon*, declared as
     * `android:label="@string/freezer"` on the `<service>` in the manifest. Those are read by
     * SystemUI out of Thor's APK using SystemUI's own resources and configuration — Thor's process
     * is not involved and neither a wrapped context here nor `LocaleManager.setApplicationLocales`
     * on 33+ reaches it. The tile name therefore follows the **system** locale while its subtitle
     * follows the app language. That split is a property of how QS tiles load metadata, not
     * something left undone here.
     *
     * The wrap is one-shot — `ContextWrapper.attachBaseContext` refuses a second call — and how long
     * this instance lives is SystemUI's decision, not Thor's: `TileServiceManager` may hold the
     * binding across shade sessions rather than unbinding with each one. [getResources] therefore
     * goes through [LocalizedResources] exactly as `ThorApplication` does, so the subtitle cannot
     * end up a language behind the app that painted it. On API 33+ that indirection is inert.
     */
    override fun attachBaseContext(newBase: Context) {
        localizedResources = LocalizedResources(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    @Volatile
    private var localizedResources: LocalizedResources? = null

    override fun getResources(): Resources =
        localizedResources?.current() ?: super.getResources()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        localizedResources?.invalidate()
        // invalidate() only fixes the *next* resource read, and by this point the strings are
        // already gone: `subtitle`, `stateDescription` and `contentDescription` are copies handed
        // to SystemUI by the last [paint], and SystemUI is displaying them now. Nothing else would
        // repaint them — the collector in [onStartListening] fires on privilege and freezer state,
        // neither of which moves when the language does — so the open shade would keep the previous
        // language until some unrelated state change happened to come along. [paint] returns
        // immediately when this service is not listening, so this costs nothing when it is not.
        paint()
    }

    override fun onStartListening() {
        // Cancel any previous scope before replacing it. The framework does not deliver
        // onStartListening twice without an intervening onStopListening (TileService.H only
        // dispatches it when mListening == false, and every path that clears mListening calls
        // onStopListening in the same breath), so this is not fixing an observed leak. It is
        // two cheap unconditional lines that make the class KDoc's "nothing retains the
        // destroyed instance" claim true by construction, instead of true only as long as that
        // framework detail holds.
        scope?.cancel()
        val listenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = listenScope

        // Phase 1: paint synchronously from whatever is already cached, so the tile is never
        // blank while the sweep runs. Phase 2 is the collector below, which repaints as the
        // real state lands.
        paint()

        listenScope.launch {
            try {
                combine(
                    privilegeManager.state,
                    freezableCount,
                    sweepController.observeLatest(PrivilegeSweepSource.QS_TILE),
                ) { _, _, status -> status }.collect { status ->
                    latestStatus = status
                    paint()
                }
            } catch (e: CancellationException) {
                // CancellationException is an Exception in Kotlin, so it must be rethrown
                // ahead of the broad catch or the shade closing looks like a crash.
                throw e
            } catch (e: Exception) {
                Logger.e("FreezerTile", "tile state collector failed", e)
            }
        }

        // Phase 2: re-derive from live per-app state. The watchlist alone cannot tell us
        // whether anything is still freezable, which is why the tile used to stay lit after
        // freezing everything.
        listenScope.launch {
            try {
                refreshFreezableCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This is the one that matters: the sweep does a Room read plus N
                // PackageManager.getApplicationInfo calls, and AppFreezeStateReader.stateOf
                // catches only NameNotFoundException. A SQLiteException or a binder-death
                // RuntimeException would otherwise reach Android's default uncaught handler —
                // there is no CoroutineExceptionHandler anywhere in :app — and kill the
                // process straight from the QS shade.
                Logger.e("FreezerTile", "candidate sweep failed", e)
            }
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        // Do not cancel actionScope here: it owns only the enqueue handoff, which must survive
        // SystemUI destroying this TileService immediately after the tap.
        super.onDestroy()
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        actionScope.launch {
            launchSurfaceSweep(
                resolver = sweepResolver,
                controller = sweepController,
                request = BulkRequest(BulkOp.FREEZE),
                source = PrivilegeSweepSource.QS_TILE,
            )
        }
    }

    private suspend fun refreshFreezableCount() {
        freezableCount.value = sweepResolver.resolve(
            BulkRequest(BulkOp.FREEZE),
            PrivilegeSweepSource.QS_TILE,
        ).packageNames.size
    }

    /**
     * Push the current state onto the tile. The [qsTile] null-check is belt-and-suspenders;
     * the real protection against post-collapse [android.service.quicksettings.Tile.updateTile]
     * calls is that [scope] is cancelled in [onStopListening] on the same Main looper, so a
     * cancelled continuation in the collector can never run its body afterwards.
     */
    private fun paint() {
        val tile = qsTile ?: return
        val status = latestStatus
        val visual = tileVisualFor(
            privilege = privilegeManager.state.value,
            freezableCount = freezableCount.value,
            status = status,
        )
        val count = freezableCount.value ?: 0

        tile.state = tileStateFor(visual)

        val terminal = status?.takeIf {
            it.phase == PrivilegeSweepPhase.SUCCEEDED ||
                it.phase == PrivilegeSweepPhase.PARTIAL ||
                it.phase == PrivilegeSweepPhase.CANCELLED ||
                it.phase == PrivilegeSweepPhase.FAILED ||
                it.phase == PrivilegeSweepPhase.OBSERVER_FAILURE
        }
        val subtitle = if (terminal != null) {
            getString(
                R.string.sweep_result_summary,
                terminal.succeeded,
                terminal.failed,
                terminal.busy,
                terminal.unresolved,
            )
        } else {
            when (visual) {
                TileVisual.CHECKING -> getString(R.string.tile_checking)
                TileVisual.WORKING -> getString(R.string.tile_freezing)
                TileVisual.NO_PRIVILEGE -> getString(R.string.tile_no_privilege)
                TileVisual.NOTHING_TO_FREEZE -> getString(R.string.tile_no_apps)
                TileVisual.READY ->
                    resources.getQuantityString(R.plurals.tile_subtitle_format, count, count)
            }
        }

        // Never setLabel: it mutates the tile's identity in the QS picker.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        // contentDescription carries the identity, stateDescription the state — TalkBack reads
        // them in that order and concatenates them itself. Putting the subtitle in both
        // announced it twice ("Freezer: 3 apps, 3 apps"), with a hardcoded ": " separator in an
        // app that ships ar/es/fr/zh.
        //
        // Below R there is no stateDescription, so contentDescription is the only channel that
        // can convey state at all (subtitle itself is Q+). Keep the combined form there rather
        // than silently dropping the state announcement for TalkBack users on 28-29.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = subtitle
            tile.contentDescription = getString(R.string.freezer)
        } else {
            tile.contentDescription = "${getString(R.string.freezer)}: $subtitle"
        }
        tile.updateTile()
    }
}
