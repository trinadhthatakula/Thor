package com.valhalla.thor.presentation.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class FreezerTileService : TileService() {

    private val freezerRepository: FreezerRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()
    private val systemRepository: SystemRepository by inject()
    private val preferenceRepository: PreferenceRepository by inject()

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope?.launch { refreshTile() }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        // appScope (not a service-lifetime scope): collapsing the QS shade destroys this service, so
        // a bulk freeze pinned to onDestroy()-cancelled work would leave a PARTIAL freeze and skip the
        // result toast + tile refresh. The process-scoped runner lets the loop finish either way.
        appScope.launch {
            val hasPrivilege = withContext(Dispatchers.IO) {
                systemRepository.isRootAvailable() ||
                        systemRepository.isShizukuAvailable() ||
                        systemRepository.isDhizukuAvailable()
            }
            if (!hasPrivilege) {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.tile_grant_privilege_toast),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val pkgs = withContext(Dispatchers.IO) { freezerRepository.getAllPackageNames() }
            if (pkgs.isEmpty()) {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.tile_no_apps_toast),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            // Honor the persisted Freezer mode so the tile stays consistent with the in-app freezer:
            // Suspend mode suspends, Freeze mode disables (mirrors FreezerViewModel.freezeSingleApp).
            val suspendMode = withContext(Dispatchers.IO) {
                preferenceRepository.userPreferences.first().freezerMode == FreezerMode.SUSPEND
            }
            val results = pkgs.map { pkg ->
                async(Dispatchers.IO) {
                    if (suspendMode) manageAppUseCase.setAppSuspended(pkg, true)
                    else manageAppUseCase.setAppDisabled(pkg, true)
                }
            }.awaitAll()
            val failures = results.count { it.isFailure }
            val msg = if (failures == 0) {
                getString(R.string.tile_freeze_success, pkgs.size)
            } else {
                getString(
                    R.string.tile_freeze_partial_failure,
                    pkgs.size - failures,
                    pkgs.size,
                    failures
                )
            }
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            refreshTile()
        }
    }

    private suspend fun refreshTile() {
        val tile = qsTile ?: return
        val hasPrivilege = withContext(Dispatchers.IO) {
            systemRepository.isRootAvailable() ||
                    systemRepository.isShizukuAvailable() ||
                    systemRepository.isDhizukuAvailable()
        }
        if (!hasPrivilege) {
            tile.state = Tile.STATE_UNAVAILABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_no_privilege)
            }
            tile.updateTile()
            return
        }
        val pkgs = withContext(Dispatchers.IO) { freezerRepository.getAllPackageNames() }
        tile.state = if (pkgs.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (pkgs.isEmpty()) {
                getString(R.string.tile_no_apps)
            } else {
                getString(R.string.tile_subtitle_format, pkgs.size)
            }
        }
        tile.updateTile()
    }

    private companion object {
        // Process-scoped (static) so a bulk freeze outlives any single service instance: the QS shade
        // collapse destroys the tile service, so this must NOT be tied to a service-lifetime scope and
        // is intentionally never cancelled. SupervisorJob keeps one failed freeze from cancelling the rest.
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
