package com.valhalla.thor.domain

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
    ) : InstallState {

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

        fun getActionButtonText(): com.valhalla.thor.util.UiText {
            return when {
                isDowngrade -> com.valhalla.thor.util.UiText.StringResource(com.valhalla.thor.R.string.install_action_downgrade)
                isUpdate -> com.valhalla.thor.util.UiText.StringResource(com.valhalla.thor.R.string.install_action_update)
                else -> com.valhalla.thor.util.UiText.StringResource(com.valhalla.thor.R.string.install_action_install)
            }
        }

        fun getWarningMessage(): com.valhalla.thor.util.UiText? {
            return when {
                isDowngrade -> com.valhalla.thor.util.UiText.StringResource(com.valhalla.thor.R.string.install_warning_downgrade)
                else -> null
            }
        }

        fun shouldShowWarning(): Boolean {
            return isDowngrade
        }

        fun getActionWord(): com.valhalla.thor.util.UiText {
            return when {
                isDowngrade -> com.valhalla.thor.util.UiText.StringResource(com.valhalla.thor.R.string.install_word_downgrade)
                isUpdate -> com.valhalla.thor.util.UiText.StringResource(com.valhalla.thor.R.string.install_word_update)
                else -> com.valhalla.thor.util.UiText.StringResource(com.valhalla.thor.R.string.install_word_install)
            }
        }

    }

    data class Installing(val progress: Float) : InstallState // 0.0 to 1.0
    data object Success : InstallState
    data class Error(val message: com.valhalla.thor.util.UiText) : InstallState

    // Critical: The OS has paused the session to ask the user for permission.
    // The Android confirm Intent lives in the data layer (PendingInstallIntent) to keep
    // this domain state free of Android types; the presentation layer consumes it.
    data object UserConfirmationRequired : InstallState
}