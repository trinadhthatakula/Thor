package com.valhalla.thor.presentation.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.valhalla.thor.domain.repository.FreezerRepository
import com.valhalla.thor.domain.repository.SystemRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class FreezerTileService : TileService() {

    private val freezerRepository: FreezerRepository by inject()
    private val manageAppUseCase: ManageAppUseCase by inject()
    private val systemRepository: SystemRepository by inject()

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
        scope?.launch {
            val hasPrivilege = withContext(Dispatchers.IO) {
                systemRepository.isRootAvailable() ||
                        systemRepository.isShizukuAvailable() ||
                        systemRepository.isDhizukuAvailable()
            }
            if (!hasPrivilege) {
                Toast.makeText(
                    applicationContext,
                    "Grant Root / Shizuku / Dhizuku first",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val pkgs = withContext(Dispatchers.IO) { freezerRepository.getAllPackageNames() }
            if (pkgs.isEmpty()) return@launch
            pkgs.forEach { pkg ->
                withContext(Dispatchers.IO) { manageAppUseCase.setAppDisabled(pkg, true) }
            }
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
                tile.subtitle = "No privilege granted"
            }
            tile.updateTile()
            return
        }
        val pkgs = withContext(Dispatchers.IO) { freezerRepository.getAllPackageNames() }
        tile.state = if (pkgs.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (pkgs.isEmpty()) "No apps" else "${pkgs.size} apps · tap to freeze"
        }
        tile.updateTile()
    }
}
