package com.valhalla.thor.domain

import android.content.Intent
import com.valhalla.thor.domain.model.AppMetadata

/**
 * Represents the distinct states of the installation process.
 * We use a Sealed Interface for strict state management in the UI.
 */
sealed interface InstallState {
    data object Idle : InstallState
    data object Parsing : InstallState

    data class ReadyToInstall(val meta: AppMetadata, val isUpdate: Boolean) : InstallState
    data class Installing(val progress: Float) : InstallState // 0.0 to 1.0
    data object Success : InstallState
    data class Error(val message: String) : InstallState

    // Critical: The OS has paused the session to ask the user for permission.
    data class UserConfirmationRequired(val intent: Intent) : InstallState
}