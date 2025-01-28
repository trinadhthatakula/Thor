package com.valhalla.thor.model

sealed interface MultiAppAction {
    data class ReInstall(val appList: List<AppInfo>) : MultiAppAction
    data class Uninstall(val appList: List<AppInfo>) : MultiAppAction
    data class Share(val appList: List<AppInfo>) : MultiAppAction
}