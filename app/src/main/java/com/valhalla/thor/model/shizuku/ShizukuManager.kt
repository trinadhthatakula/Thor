package com.valhalla.thor.model.shizuku

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import com.valhalla.thor.model.rootAvailable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku


class ShizukuManager : ViewModel() {

    private val _shizukuState = MutableStateFlow<ShizukuState>(ShizukuState.NotInstalled)
    val shizukuState = _shizukuState.asStateFlow()

    var shizukuStateRaw: ShizukuState = ShizukuState.NotInstalled

    fun checkState() {

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            shizukuStateRaw = ShizukuState.PermissionNeeded
            _shizukuState.value = shizukuStateRaw
            return
        }

        shizukuStateRaw = ShizukuState.Ready
        _shizukuState.update { shizukuStateRaw }
    }

    fun requestPermission(requestCode: Int = 1001, onRationaleNeeded: () -> Unit = {}) {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            onRationaleNeeded()
        } else
            Shizuku.requestPermission(requestCode)
    }


    val elevatableState
        get() = if (rootAvailable()) ElevatableState.SU
        else when (shizukuStateRaw) {
            ShizukuState.NotInstalled -> ElevatableState.SHIZUKU_NOT_INSTALLED
            ShizukuState.NotRunning -> ElevatableState.SHIZUKU_NOT_RUNNING
            ShizukuState.PermissionNeeded -> ElevatableState.SHIZUKU_PERMISSION_NEEDED
            ShizukuState.Ready -> ElevatableState.SHIZUKU_RUNNING
        }

    fun updateState(state: ShizukuState) {
        shizukuStateRaw = state
        _shizukuState.update { shizukuStateRaw }
    }


}