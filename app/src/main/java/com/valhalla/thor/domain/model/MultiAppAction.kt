package com.valhalla.thor.domain.model

sealed interface MultiAppAction {
    data class ReInstall(val appList: List<AppInfo>) : MultiAppAction
    data class Uninstall(val appList: List<AppInfo>) : MultiAppAction
    data class Freeze(val appList: List<AppInfo>) : MultiAppAction
    data class UnFreeze(val appList: List<AppInfo>) : MultiAppAction
    data class Share(val appList: List<AppInfo>) : MultiAppAction
    data class Kill(val appList: List<AppInfo>) : MultiAppAction

    data class ClearCache(val appList: List<AppInfo>) : MultiAppAction
}