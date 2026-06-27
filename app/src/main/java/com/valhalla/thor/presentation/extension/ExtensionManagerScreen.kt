package com.valhalla.thor.presentation.extension

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.presentation.settings.SettingsViewModel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import com.valhalla.thor.presentation.theme.firaMonoFontFamily
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExtensionManagerScreen(
    onBack: () -> Unit,
    viewModel: ExtensionManagerViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val prefs by settingsViewModel.preferences.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            ExtensionTopAppBar(onBack = onBack)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            } else if (state.extensions.isEmpty()) {
                EmptyExtensionState(
                    onGetExtensions = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                "https://github.com/trinadhthatakula/Thor".toUri()
                            )
                        )
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.extensions),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.manage_extensions_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    items(state.extensions, key = { it.packageName }) { item ->
                        ExtensionCard(item = item, prefs = prefs)
                    }

                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionTopAppBar(
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.round_close),
                contentDescription = stringResource(R.string.cd_close),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.extension_manager),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptyExtensionState(
    onGetExtensions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.round_extension),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.no_extensions_installed),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_extensions_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onGetExtensions,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Icon(
                painter = painterResource(R.drawable.brand_github),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.get_extensions),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ExtensionCard(
    item: ExtensionUiItem,
    prefs: UserPreferences
) {
    val ext = item.extension
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    val launchIntent = remember(item.packageName) {
        val intent = Intent("com.valhalla.thor.intent.action.LAUNCH_EXTENSION").apply {
            setPackage(item.packageName)
        }
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        if (activities.isNotEmpty()) intent else null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.round_extension),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ext.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.extension_by_author, ext.author),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.extension_version_prefix, ext.version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = ext.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )

        if (launchIntent != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    val isDark = when (prefs.themeMode) {
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                        ThemeMode.SYSTEM -> systemDark
                    }
                    val intent = Intent(launchIntent).apply {
                        putExtra("theme_mode", if (isDark) "dark" else "light")
                        putExtra("amoled_mode", prefs.useAmoled)
                        putExtra("dynamic_colors", prefs.useDynamicColor)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Run Extension")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = firaMonoFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))

            val (badgeBg, badgeText, badgeLabel) = when {
                item.isVerified -> Triple(
                    Color(0xFFC8E6C9),
                    Color(0xFF1B5E20),
                    stringResource(R.string.extension_security_verified)
                )
                BuildConfig.DEBUG -> Triple(
                    Color(0xFFFFE0B2),
                    Color(0xFFE65100),
                    stringResource(R.string.extension_security_debug)
                )
                else -> Triple(
                    Color(0xFFFFCDD2),
                    Color(0xFFB71C1C),
                    stringResource(R.string.extension_security_untrusted)
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(badgeBg)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeText,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
