package com.valhalla.thor.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.valhalla.thor.R
import kotlinx.serialization.Serializable

@Serializable
enum class AppDestinations(
    val label: Int,
    val icon: Int,
    val selectedIcon: Int,
    val contentDescription: Int
) {
    HOME(R.string.home, R.drawable.home_outline, R.drawable.home, R.string.home_desc),
    APPS(R.string.apps, R.drawable.apps, R.drawable.apps, R.string.apps_desc),
    FREEZER(R.string.freezer, R.drawable.frozen, R.drawable.frozen, R.string.freezer_desc),
    // SETTINGS(R.string.settings, R.drawable.settings, R.drawable.settings, R.string.settings_desc)
}