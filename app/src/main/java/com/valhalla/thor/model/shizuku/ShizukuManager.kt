package com.valhalla.thor.model.shizuku

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

val shizukuManager = ShizukuManager.getInstance()

class ShizukuManager {

    private val _shizukuState = MutableStateFlow<ShizukuState>(ShizukuState.NotInstalled)
    val shizukuState = _shizukuState.asStateFlow()

    var shizukuStateRaw: ShizukuState = ShizukuState.NotInstalled

    init {
        // Add listeners to react to Shizuku state changes automatically
        Shizuku.addBinderReceivedListener(::checkState)
        Shizuku.addBinderDeadListener(::checkState)
        Shizuku.addRequestPermissionResultListener { _, _ -> checkState() }
        checkState()
    }

    fun checkState() {
        if (!Shizuku.pingBinder()) {
            shizukuStateRaw = if (Shizuku.isPreV11()) ShizukuState.NotRunning else ShizukuState.NotInstalled
            _shizukuState.value = shizukuStateRaw
            return
        }

        if (Shizuku.checkSelfPermission() != 0) {
            shizukuStateRaw = ShizukuState.PermissionNeeded
            _shizukuState.value = shizukuStateRaw
            return
        }
        shizukuStateRaw = ShizukuState.Ready
        _shizukuState.value = shizukuStateRaw
    }

    fun requestPermission(onRationaleNeeded: () -> Unit = {}) {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            onRationaleNeeded()
        }
        Shizuku.requestPermission(REQUEST_CODE_SHIZUKU_PERMISSION)
    }

    companion object {
        const val REQUEST_CODE_SHIZUKU_PERMISSION = 1001

        @Volatile
        var INSTANCE: ShizukuManager? = null

        fun getInstance(): ShizukuManager = INSTANCE ?: synchronized(this) {
            val instance = ShizukuManager()
            INSTANCE = instance
            instance
        }

    }
}