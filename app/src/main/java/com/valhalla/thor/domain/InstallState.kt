// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain

import com.valhalla.thor.domain.model.AppMetadata

/**
 * Represents the distinct states of the installation process.
 * We use a Sealed Interface for strict state management in the UI.\n */
sealed interface InstallState {
    data object Idle : InstallState
    data object Parsing : InstallState

    /**
     * @param oldVersion the installed app's version *name*, or null when nothing is installed.
     * @param oldVersionCode the installed app's version *code* — the value [isDowngrade] is
     *   actually decided on. Carried so the UI can show it: version names routinely disagree with
     *   version codes, and without the codes on screen a correct downgrade verdict is impossible
     *   for a user to distinguish from a bug.
     */
    data class ReadyToInstall(
        val meta: AppMetadata,
        val isUpdate: Boolean,
        val isDowngrade: Boolean = false,
        val oldVersion: String? = null,
        val oldVersionCode: Long? = null
    ) : InstallState {

        // getVersionInfo() lived here: dead code (@Suppress("unused"), no call sites) that
        // rendered the verdict from version NAMES only, and tested isUpdate before isDowngrade —
        // a downgrade is always also an update, so its downgrade branch was unreachable and it
        // reported "Update available: 1.2.5.1 (current: 1.2.4.7)" for precisely the case this
        // class now exists to explain. Deleted rather than fixed; nothing called it.

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