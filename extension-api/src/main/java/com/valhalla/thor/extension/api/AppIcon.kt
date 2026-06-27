package com.valhalla.thor.extension.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.compose.AsyncImage

/**
 * A lightweight model key to fetch the icon of an installed application via Coil.
 */
data class AppIconModel(val packageName: String)

/**
 * A shared Composable for loading and displaying an application's icon via Coil.
 */
@Composable
fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    AsyncImage(
        model = AppIconModel(packageName),
        contentDescription = contentDescription,
        modifier = modifier
    )
}
