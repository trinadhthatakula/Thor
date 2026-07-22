// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

sealed interface MultiAppAction {
    data class ReInstall(val appList: List<AppInfo>) : MultiAppAction
    data class Uninstall(val appList: List<AppInfo>) : MultiAppAction
    data class Freeze(val appList: List<AppInfo>, val useSuspend: Boolean = false) : MultiAppAction
    data class UnFreeze(val appList: List<AppInfo>) : MultiAppAction
    data class Share(val appList: List<AppInfo>) : MultiAppAction
    data class Kill(val appList: List<AppInfo>) : MultiAppAction

    data class ClearCache(val appList: List<AppInfo>) : MultiAppAction
    data class ClearData(val appList: List<AppInfo>) : MultiAppAction
    data class Suspend(val appList: List<AppInfo>) : MultiAppAction
    data class UnSuspend(val appList: List<AppInfo>) : MultiAppAction
}