package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

// You can define a simple data class for links
data class SocialLink(
    val url: String,
    val icon: Int, // Resource ID
    val description: String
)

@Composable
fun SocialLinksRow(
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

    // Define your links here
    val links = listOf(
        SocialLink("https://github.com/trinadhthatakula", R.drawable.brand_github, "GitHub"),
        SocialLink("https://patreon.com/trinadh", R.drawable.brand_patreon, "Patreon"),
        SocialLink("https://t.me/thorAppDev", R.drawable.brand_telegram, "Telegram")
        // Add more as needed
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        links.forEach { link ->
            IconButton(
                onClick = { uriHandler.openUri(link.url) },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                // If you don't have these drawables yet, replace with Icons.Default.Link temporarily
                Icon(
                    painter = painterResource(link.icon),
                    contentDescription = link.description,
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}