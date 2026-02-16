package com.valhalla.thor.domain

import android.content.Intent
import com.valhalla.thor.domain.model.AppMetadata

/**
 * Represents the distinct states of the installation process.
 * We use a Sealed Interface for strict state management in the UI.\n */
sealed interface InstallState {
    data object Idle : InstallState
    data object Parsing : InstallState

    data class ReadyToInstall(
        val meta: AppMetadata,
        val isUpdate: Boolean,
        val isDowngrade: Boolean = false,
        val oldVersion: String? = null
    ) : InstallState{

        @Suppress("unused")
        fun getVersionInfo(): String {
            return if (isUpdate) {
                "Update available: ${meta.version} (current: $oldVersion)"
            } else if (isDowngrade) {
                "Downgrade detected: ${meta.version} (current: $oldVersion)"
            } else {
                "Ready to install version ${meta.version}"
            }
        }

        fun getActionButtonText(): String {
            return when {
                isDowngrade -> "Install Anyway"
                isUpdate -> "Update"
                else -> "Install"
            }
        }

        fun getWarningMessage(): String? {
            return when {
                isDowngrade -> "Warning: Installing an older version may cause issues."
                isUpdate -> null
                else -> null
            }
        }

        fun shouldShowWarning(): Boolean {
            return isDowngrade
        }

        fun getActionWord(): String {
            return when {
                isDowngrade -> "downgrade"
                isUpdate -> "update"
                else -> "install"
            }
        }

    }

    data class Installing(val progress: Float) : InstallState // 0.0 to 1.0
    data object Success : InstallState
    data class Error(val message: String) : InstallState

    // Critical: The OS has paused the session to ask the user for permission.
    data class UserConfirmationRequired(val intent: Intent) : InstallState
}