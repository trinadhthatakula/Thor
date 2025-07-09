package com.valhalla.thor.model.shizuku

// In a new file, e.g., ShizukuState.kt
sealed class ShizukuState {
    data object NotInstalled : ShizukuState()
    data object NotRunning : ShizukuState()
    data object PermissionNeeded : ShizukuState()
    data object Ready : ShizukuState() // Simplified: The manager holds the service instance.
}