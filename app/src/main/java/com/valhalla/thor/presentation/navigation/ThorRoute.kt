package com.valhalla.thor.presentation.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface ThorRoute : NavKey {

    @Serializable
    data object Home : ThorRoute

    @Serializable
    data object Apps : ThorRoute

    @Serializable
    data object Freezer : ThorRoute

    @Serializable
    data object Settings : ThorRoute

    @Serializable
    data class PermissionManager(val packageName: String, val appName: String) : ThorRoute

    @Serializable
    data class AppInfoDetails(val packageName: String, val appName: String) : ThorRoute

    @Serializable
    data object ExtensionManager : ThorRoute

    @Serializable
    data object ExtensionBrowse : ThorRoute
}
