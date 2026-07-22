// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

sealed interface AppClickAction {
    //data class Logcat(val appInfo: AppInfo): AppClickAction
    data class Launch(val appInfo: AppInfo) : AppClickAction
    data class Share(val appInfo: AppInfo) : AppClickAction
    data class Uninstall(val appInfo: AppInfo) : AppClickAction
    data class Reinstall(val appInfo: AppInfo) : AppClickAction
    data class Freeze(val appInfo: AppInfo) : AppClickAction
    data class UnFreeze(val appInfo: AppInfo) : AppClickAction
    data class Kill(val appInfo: AppInfo) : AppClickAction
    data class AppInfoSettings(val appInfo: AppInfo) : AppClickAction
    data object ReinstallAll : AppClickAction

    data class ClearCache(val appInfo: AppInfo) : AppClickAction
    data class ClearData(val appInfo: AppInfo) : AppClickAction
    data class Suspend(val appInfo: AppInfo) : AppClickAction
    data class UnSuspend(val appInfo: AppInfo) : AppClickAction
    data class ManagePermissions(val appInfo: AppInfo) : AppClickAction
    data class OpenDetails(val appInfo: AppInfo) : AppClickAction
    data class AddToHomeScreen(val appInfo: AppInfo) : AppClickAction
}