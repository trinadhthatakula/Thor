// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.data.manager.UsageAccessManager
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToExtensionManager: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val hasPrivilege = state.isRootAvailable || state.isShizukuAvailable || state.isDhizukuAvailable
    val context = LocalContext.current
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showUnfreezeConfirmation by remember { mutableStateOf(false) }
    var showSupportSheet by remember { mutableStateOf(false) }

    ObserveAsEvents(viewModel.events) { event ->
        android.widget.Toast.makeText(
            context,
            event.asString(context),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    if (showUnfreezeConfirmation) {
        AlertDialog(
            onDismissRequest = { showUnfreezeConfirmation = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.unfreeze_all_confirmation_title)) },
            text = { Text(stringResource(R.string.unfreeze_all_confirmation_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unfreezeAll()
                        showUnfreezeConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.proceed))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnfreezeConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        }.getOrDefault("—")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 64.dp, bottom = 120.dp)
    ) {

        // Header Section
        Text(
            text = stringResource(R.string.settings),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp
        )
        Text(
            text = stringResource(R.string.config_engine_v, versionName),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(48.dp))

        // ── GENERAL ─────────────────────────────────────────────────────────
        SettingsSectionLabel(stringResource(R.string.general))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SettingsSwitchRow(
                icon = R.drawable.apk_install,
                title = stringResource(R.string.show_reinstall_card),
                subtitle = stringResource(R.string.show_reinstall_card_desc),
                checked = prefs.showReinstallAllCard,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setReinstallAllCardVisibility(it) }
            )

            SettingsSwitchRow(
                icon = R.drawable.apps,
                title = stringResource(R.string.detailed_view),
                subtitle = stringResource(R.string.detailed_view_desc),
                checked = prefs.useDetailedView,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setDetailedViewEnabled(it) }
            )

            SettingsSwitchRow(
                icon = R.drawable.settings_backup_restore,
                title = stringResource(R.string.auto_reinstall),
                subtitle = stringResource(R.string.auto_reinstall_desc),
                checked = prefs.autoReinstallEnabled,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setAutoReinstallEnabled(it) }
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── APPEARANCE ──────────────────────────────────────────────────────
        SettingsSectionLabel(stringResource(R.string.appearance))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Theme Row
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(R.drawable.theme_panel)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.theme),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.theme_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                ConnectedButtonGroup(
                    items = ThemeMode.entries.map { ConnectedButtonGroupItem.Label(it.label()) },
                    selectedIndex = ThemeMode.entries.indexOf(prefs.themeMode),
                    onItemSelected = { viewModel.setThemeMode(ThemeMode.entries[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsSwitchRow(
                icon = R.drawable.theme_panel,
                title = stringResource(R.string.amoled_mode),
                subtitle = stringResource(R.string.amoled_desc),
                checked = prefs.useAmoled,
                onCheckedChange = { viewModel.setAmoledMode(it) }
            )

            SettingsSwitchRow(
                icon = R.drawable.shield_with_heart,
                title = stringResource(R.string.dynamic_colors),
                subtitle = stringResource(R.string.dynamic_colors_desc),
                checked = prefs.useDynamicColor,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = { viewModel.setDynamicColor(it) }
            )

            SettingsClickRow(
                icon = R.drawable.settings_backup_restore,
                title = stringResource(R.string.app_language),
                subtitle = when (prefs.language) {
                    "en" -> stringResource(R.string.english)
                    "zh" -> stringResource(R.string.chinese)
                    "fr" -> stringResource(R.string.french)
                    "es" -> stringResource(R.string.spanish)
                    "ar" -> stringResource(R.string.arabic)
                    else -> stringResource(R.string.system_default)
                },
                onClick = { showLanguageSheet = true }
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── ANIMATION INTENSITY ─────────────────────────────────────────────
        SettingsSectionLabel(stringResource(R.string.animation_intensity))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(R.drawable.bolt)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        stringResource(R.string.animation_intensity),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(R.string.animation_intensity_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ConnectedButtonGroup(
                items = AnimationIntensity.entries.map {
                    val label = when (it) {
                        AnimationIntensity.LOW -> stringResource(R.string.animation_intensity_low)
                        AnimationIntensity.MEDIUM -> stringResource(R.string.animation_intensity_medium)
                        AnimationIntensity.HIGH -> stringResource(R.string.animation_intensity_high)
                    }
                    ConnectedButtonGroupItem.Label(label)
                },
                selectedIndex = AnimationIntensity.entries.indexOf(prefs.animationIntensity),
                onItemSelected = { viewModel.setAnimationIntensity(AnimationIntensity.entries[it]) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── SECURITY ────────────────────────────────────────────────────────
        SettingsSectionLabel(stringResource(R.string.security))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp)
        ) {
            SettingsSwitchRow(
                icon = R.drawable.round_key,
                title = stringResource(R.string.biometric_lock),
                subtitle = if (state.canUseBiometric) {
                    stringResource(R.string.biometric_lock_desc)
                } else {
                    stringResource(R.string.biometric_not_available)
                },
                checked = prefs.biometricLockEnabled,
                enabled = state.canUseBiometric,
                onCheckedChange = { viewModel.setBiometricLock(it) }
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── PERMISSIONS ─────────────────────────────────────────────────────
        SettingsSectionLabel(stringResource(R.string.permissions))

        val usageAccessManager = koinInject<UsageAccessManager>()
        val lifecycleOwner = LocalLifecycleOwner.current
        var usageGranted by remember { mutableStateOf(usageAccessManager.isGranted()) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    usageGranted = usageAccessManager.isGranted()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp)
        ) {
            SettingsSwitchRow(
                icon = R.drawable.shield,
                title = stringResource(R.string.usage_access),
                subtitle = if (usageGranted) {
                    stringResource(R.string.usage_access_granted_subtitle)
                } else {
                    stringResource(R.string.usage_access_needed_subtitle)
                },
                checked = usageGranted,
                onCheckedChange = {
                    // This op can't be toggled in-app; deep-link to system settings.
                    if (!usageGranted) {
                        runCatching { context.startActivity(usageAccessManager.usageAccessIntent()) }
                    }
                }
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── FREEZER ─────────────────────────────────────────────────────────
        SettingsSectionLabel(stringResource(R.string.freezer))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SettingsSwitchRow(
                icon = R.drawable.frozen,
                title = stringResource(R.string.auto_freeze),
                subtitle = if (hasPrivilege) stringResource(R.string.auto_freeze_desc) else stringResource(
                    R.string.privilege_required_warning
                ),
                checked = prefs.autoFreezeEnabled,
                enabled = hasPrivilege,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setAutoFreezeEnabled(it) }
            )

            SettingsSwitchRow(
                icon = R.drawable.frozen,
                title = stringResource(R.string.suspend_instead_of_freeze),
                subtitle = if (hasPrivilege) stringResource(R.string.suspend_instead_of_freeze_desc) else stringResource(
                    R.string.privilege_required_warning
                ),
                checked = prefs.freezerMode == FreezerMode.SUSPEND,
                enabled = hasPrivilege,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setFreezerMode(if (it) FreezerMode.SUSPEND else FreezerMode.FREEZE) }
            )

            SettingsClickRow(
                icon = R.drawable.unfreeze,
                title = stringResource(R.string.unfreeze_all_apps),
                subtitle = if (hasPrivilege) stringResource(R.string.unfreeze_all_apps_desc) else stringResource(
                    R.string.privilege_required_warning
                ),
                enabled = hasPrivilege,
                onClick = { showUnfreezeConfirmation = true }
            )

            SettingsSwitchRow(
                icon = R.drawable.frozen,
                title = stringResource(R.string.add_freezer_to_launcher),
                subtitle = if (hasPrivilege) stringResource(R.string.add_freezer_to_launcher_desc) else stringResource(
                    R.string.privilege_required_warning
                ),
                checked = prefs.addFreezerToLauncher,
                enabled = hasPrivilege,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setAddFreezerToLauncher(it) }
            )
        }

        // ── EXTENSIONS ──────────────────────────────────────────────────────
        // Power-user surface: shown only when an elevated privilege (Root / Shizuku / Dhizuku) is
        // available, so normal users aren't offered it. Entry into the manager itself is further
        // gated by a one-time liability-consent sheet (see ExtensionManagerScreen).
        if (hasPrivilege) {
            Spacer(Modifier.height(32.dp))

            SettingsSectionLabel(stringResource(R.string.extensions))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(8.dp)
            ) {
                SettingsClickRow(
                    icon = R.drawable.round_extension,
                    title = stringResource(R.string.manage_extensions),
                    subtitle = stringResource(R.string.manage_extensions_desc),
                    onClick = onNavigateToExtensionManager
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // ── WORK MODE ───────────────────────────────────────────────────────
        val availableModes = buildList {
            if (state.isRootAvailable) add(PrivilegeMode.ROOT)
            if (state.isShizukuAvailable) add(PrivilegeMode.SHIZUKU)
            if (state.isDhizukuAvailable) add(PrivilegeMode.DHIZUKU)
        }

        if (availableModes.size > 1) {
            SettingsSectionLabel(stringResource(R.string.work_mode))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val activeMode = prefs.preferredPrivilegeMode ?: availableModes.first()
                    val icon = when (activeMode) {
                        PrivilegeMode.ROOT -> R.drawable.magisk_icon
                        PrivilegeMode.SHIZUKU -> R.drawable.shizuku
                        PrivilegeMode.DHIZUKU -> R.drawable.dhizuku
                        // Unreachable here: WORK MODE only renders when a real privilege mode is available.
                        PrivilegeMode.NONE -> R.drawable.shield
                    }
                    IconBox(icon)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.active_engine),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.active_engine_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                ConnectedButtonGroup(
                    items = availableModes.map { mode ->
                        ConnectedButtonGroupItem.Label(mode.name)
                    },
                    selectedIndex = availableModes.indexOf(
                        prefs.preferredPrivilegeMode ?: availableModes.first()
                    ),
                    onItemSelected = { viewModel.setPrivilegeMode(availableModes[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        // ── ABOUT ───────────────────────────────────────────────────────────
        SettingsSectionLabel(stringResource(R.string.about))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsClickRow(
                icon = R.drawable.shield_with_heart,
                title = stringResource(R.string.support_developer),
                subtitle = stringResource(R.string.support_developer_desc),
                onClick = { showSupportSheet = true }
            )
            // Version Tile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(R.drawable.thor_mono)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.version),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.release_candidate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        versionName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AboutTile(
                    title = stringResource(R.string.github),
                    subtitle = stringResource(R.string.source_code),
                    icon = R.drawable.brand_github,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                "https://github.com/trinadhthatakula/Thor".toUri()
                            )
                        )
                    }
                )
                AboutTile(
                    title = stringResource(R.string.telegram),
                    subtitle = stringResource(R.string.community),
                    icon = R.drawable.brand_telegram,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                "https://t.me/thorAppDev".toUri()
                            )
                        )
                    }
                )
            }
        }

        // Technical Stats Footer
        Spacer(Modifier.height(48.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Text(
                    stringResource(R.string.kernel_status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stringResource(R.string.built_with_precision),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                letterSpacing = 4.sp
            )
        }

        Spacer(Modifier.height(32.dp))
    }

    if (showLanguageSheet) {
        LanguageBottomSheet(
            selectedLanguage = prefs.language,
            onLanguageSelected = { lang ->
                viewModel.setLanguage(lang)
                showLanguageSheet = false
            },
            onDismiss = { showLanguageSheet = false }
        )
    }

    if (showSupportSheet) {
        SupportDeveloperHelper(
            onDismiss = { showSupportSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageBottomSheet(
    selectedLanguage: String?,
    onLanguageSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val languages = listOf(
        null to stringResource(R.string.system_default),
        "en" to stringResource(R.string.english),
        "zh" to stringResource(R.string.chinese),
        "fr" to stringResource(R.string.french),
        "es" to stringResource(R.string.spanish),
        "ar" to stringResource(R.string.arabic),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(
                SheetValue.Expanded, SheetValue.Hidden
            )
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp)
                .padding(horizontal = 24.dp)
        ) {
            Text(
                stringResource(R.string.select_language),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            languages.forEach { (code, label) ->
                val isSelected = selectedLanguage == code
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onLanguageSelected(code) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsClickRow(
    icon: Int,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(icon)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
        letterSpacing = 2.sp
    )
}

@Composable
private fun IconBox(icon: Int) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    enableMarqueeOnClick: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            IconBox(icon)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                var startMarquee by remember { mutableStateOf(false) }
                val textModifier = if (enableMarqueeOnClick && startMarquee) {
                    Modifier.basicMarquee()
                } else {
                    Modifier
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = if (enableMarqueeOnClick && startMarquee) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = textModifier.then(
                        if (enableMarqueeOnClick && enabled) {
                            Modifier.clickable { startMarquee = !startMarquee }
                        } else {
                            Modifier
                        }
                    )
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun AboutTile(
    title: String,
    subtitle: String,
    icon: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { onClick() }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
