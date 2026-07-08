package com.valhalla.thor.presentation.extension

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlin.random.Random
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.model.UserPreferences
import com.valhalla.thor.presentation.settings.SettingsViewModel
import org.koin.compose.koinInject
import com.valhalla.thor.data.manager.ExtensionManager
import com.valhalla.thor.presentation.theme.firaMonoFontFamily
import org.koin.androidx.compose.koinViewModel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.IconButtonDefaults

@Composable
fun ExtensionManagerScreen(
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    viewModel: ExtensionManagerViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val prefs by settingsViewModel.preferences.collectAsStateWithLifecycle()
    val extensionManager: ExtensionManager = koinInject()

    // Reload when returning to this screen (e.g. after installing from the store) so a freshly
    // installed extension appears with its post-install cert gate. Skip the first resume, which
    // would duplicate the VM's init-time load.
    var firstResume by rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else viewModel.loadExtensions()
        onPauseOrDispose { }
    }

    // First-open liability gate: the manager is a powerful, third-party-capable surface, so require
    // an explicit consent (disclaimer + a small math check) before it can be used. Persisted, so it
    // shows only once; dismissing without solving leaves the screen.
    if (!prefs.extensionConsentAccepted) {
        ExtensionConsentSheet(
            onConsent = { settingsViewModel.setExtensionConsentAccepted(true) },
            onDismiss = onBack,
        )
    }

    Scaffold(
        topBar = { ExtensionTopAppBar(onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
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
                        onGetExtensions = onBrowse
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
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onBrowse,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.round_extension),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.extension_browse_store),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        items(state.extensions, key = { it.packageName }) { item ->
                            ExtensionCard(
                                item = item,
                                prefs = prefs,
                                onConfigure = {
                                    // Launch the extension's OWN config Activity (its process),
                                    // passing Thor's theme so its UI can match (it can't read our prefs).
                                    extensionManager.getConfigLaunchIntent(item.packageName)?.let {
                                        it.putExtra(ExtensionManager.EXTRA_THEME_MODE, prefs.themeMode.name)
                                        it.putExtra(ExtensionManager.EXTRA_DYNAMIC_COLOR, prefs.useDynamicColor)
                                        it.putExtra(ExtensionManager.EXTRA_AMOLED, prefs.useAmoled)
                                        runCatching { context.startActivity(it) }
                                    }
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(32.dp))
                        }
                    }
                }
            }
        }
    }

/**
 * One-time liability-consent gate shown the first time the user opens the Extension Manager. Beyond
 * reading the disclaimer, the user must solve a small arithmetic problem — a deliberate,
 * un-tap-through-able confirmation that they've engaged with the warning before accepting the risk.
 * [onConsent] persists acceptance; [onDismiss] (swipe / scrim / back) leaves the manager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionConsentSheet(
    onConsent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val a = remember { Random.nextInt(3, 10) }
    val b = remember { Random.nextInt(2, 9) }
    var answer by rememberSaveable { mutableStateOf("") }
    val solved = answer.trim().toIntOrNull() == a + b
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.extension_consent_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.extension_consent_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.extension_consent_math_prompt, a, b),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedTextField(
                value = answer,
                onValueChange = { new -> answer = new.filter { it.isDigit() || it == '-' }.take(4) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary action gets the full width so its label never wraps.
                Button(
                    onClick = onConsent,
                    enabled = solved,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.extension_consent_accept),
                        maxLines = 1
                    )
                }
                // Decline = a compact close icon beside it.
                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.filledTonalIconButtonColors()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.round_close),
                        contentDescription = stringResource(R.string.cancel)
                    )
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
            .background(MaterialTheme.colorScheme.background)
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
    prefs: UserPreferences,
    onConfigure: () -> Unit
) {
    val ext = item.extension
    val isConfigurable = item.isConfigurable

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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

                if (isConfigurable) {
                    IconButton(
                        onClick = onConfigure,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.run_extension)
                        )
                    }
                }
            }

        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = ext.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )

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
