// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.valhalla.thor.R
import com.valhalla.thor.data.freezer.BulkFreezeRunner
import com.valhalla.thor.data.manager.PrivilegeManager
import com.valhalla.thor.domain.model.BulkOp
import com.valhalla.thor.domain.model.BulkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Quick Settings tile that bulk-freezes the freezer watchlist.
 *
 * The tile owns no work. [BulkFreezeRunner] is an app-scoped @Single that runs the batch and
 * publishes its state, so a QS shade collapse destroying this service cannot truncate a
 * freeze and cannot leave anything retaining the destroyed instance. This service only
 * observes and paints.
 */
class FreezerTileService : TileService() {

    private val runner: BulkFreezeRunner by inject()
    private val privilegeManager: PrivilegeManager by inject()

    private var scope: CoroutineScope? = null

    /** The [BulkResult] currently displayed in the subtitle; consumed in [onStopListening]. */
    private var shownResult: BulkResult? = null

    override fun onStartListening() {
        val listenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = listenScope

        // Phase 1: paint synchronously from whatever is already cached, so the tile is never
        // blank while the sweep runs. Phase 2 is the collector below, which repaints as the
        // real state lands.
        paint()

        listenScope.launch {
            combine(
                privilegeManager.state,
                runner.freezableCount,
                runner.isRunning,
                runner.lastResult,
            ) { _, _, _, _ -> Unit }.collect { paint() }
        }

        // Phase 2: re-derive from live per-app state. The watchlist alone cannot tell us
        // whether anything is still freezable, which is why the tile used to stay lit after
        // freezing everything.
        listenScope.launch { runner.refreshCandidates(BulkOp.FREEZE) }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        // Consume only the result that was actually displayed. Deferred to onStopListening so
        // the message holds for the full shade-open window and no observer sees the write.
        shownResult?.let { runner.consumeResult(it) }
        shownResult = null
    }

    override fun onClick() {
        // The privilege state may not be resolved at tap time: PrivilegeManager probes on IO
        // and the first DataStore emission may not have landed. Checking the current (possibly
        // unresolved) snapshot would silently drop the tap with no feedback. Await the first
        // ready snapshot on the listen scope instead. The await is cancelled with the scope if
        // the shade closes before the probe resolves — acceptable, the user navigated away.
        val currentScope = scope ?: return
        currentScope.launch {
            val resolved = privilegeManager.state.first { it.isReady }
            if (!resolved.hasAnyPrivilege) {
                paint()
                return@launch
            }
            runner.launch(BulkOp.FREEZE)
        }
    }

    /**
     * Push the current state onto the tile. The [qsTile] null-check is belt-and-suspenders;
     * the real protection against post-collapse [android.service.quicksettings.Tile.updateTile]
     * calls is that [scope] is cancelled in [onStopListening] on the same Main looper, so a
     * cancelled continuation in the collector can never run its body afterwards.
     */
    private fun paint() {
        val tile = qsTile ?: return
        val visual = tileVisualFor(
            privilege = privilegeManager.state.value,
            freezableCount = runner.freezableCount.value,
            isRunning = runner.isRunning.value,
        )
        val count = runner.freezableCount.value ?: 0

        tile.state = when (visual) {
            TileVisual.NO_PRIVILEGE -> Tile.STATE_UNAVAILABLE
            TileVisual.NOTHING_TO_FREEZE -> Tile.STATE_INACTIVE
            // CHECKING stays INACTIVE (clickable) on purpose: an UNAVAILABLE tile never
            // receives onClick, so an optimistic paint would swallow taps until the next
            // listen.
            TileVisual.CHECKING -> Tile.STATE_INACTIVE
            TileVisual.WORKING -> Tile.STATE_ACTIVE
            TileVisual.READY -> Tile.STATE_ACTIVE
        }

        // A finished run's message wins the subtitle for the whole shade-open window.
        // Record it in shownResult so onStopListening can consume exactly what was displayed.
        // Writing to shownResult (a plain field, not a flow) does not re-trigger the collector.
        val result = runner.lastResult.value
        val subtitle = if (result != null && !runner.isRunning.value) {
            shownResult = result
            bulkResultMessage(result).asString(this)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = subtitle
        tile.contentDescription = "${getString(R.string.freezer)}: $subtitle"
        tile.updateTile()
    }
}
