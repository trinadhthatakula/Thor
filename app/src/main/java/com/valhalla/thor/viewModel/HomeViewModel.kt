package com.valhalla.thor.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.model.rootAvailable
import com.valhalla.thor.model.shizuku.ElevatableState
import com.valhalla.thor.model.shizuku.ShizukuState
import com.valhalla.thor.model.shizuku.shizukuManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel: ViewModel() {

    val shizukuState: StateFlow<ShizukuState> = shizukuManager.shizukuState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ShizukuState.NotInstalled // Or whatever initial state you prefer
        )

    fun getElevatableState() = if(rootAvailable()) ElevatableState.SU else when(shizukuManager.shizukuStateRaw){
        ShizukuState.NotInstalled -> ElevatableState.SHIZUKU_NOT_INSTALLED
        ShizukuState.NotRunning -> ElevatableState.SHIZUKU_NOT_RUNNING
        ShizukuState.PermissionNeeded -> ElevatableState.SHIZUKU_PERMISSION_NEEDED
        ShizukuState.Ready -> ElevatableState.SHIZUKU_RUNNING
    }

}