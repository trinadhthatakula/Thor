package com.valhalla.thor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.valhalla.thor.R
import com.valhalla.thor.model.AppInfo
import kotlinx.serialization.Serializable

sealed interface Screens{

    @Serializable
    object Home : Screens

    @Serializable
    data class AppList(
        val userApps: List<AppInfo>,
        val systemApps: List<AppInfo>,
        val icon: Int = R.drawable.thor_mono,
        val title: String = "App List",
        val customSelection: Boolean = false,

    ) : Screens

    @Serializable
    object AppDetails : Screens

}


@Composable
fun NavScreen(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(navController, startDestination = Screens.Home){
        composable < Screens.Home >{

        }
        composable < Screens.AppList >{

        }
    }
}