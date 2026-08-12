// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.data.manager.UsageAccessManager
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.DefaultTab
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.usecase.ObserveInterruptedRestoreUseCase
import com.valhalla.thor.presentation.main.toDestination
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.thor.util.AppLanguage
import com.valhalla.thor.util.displayedLanguage
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToExtensionManager: () -> Unit,
    // Not defaulted, so the one call site has to supply it. A default would leave a Restore row that
    // navigates nowhere, and nothing would fail to compile.
    onNavigateToRestore: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val hasPrivilege = state.isRootAvailable || state.isShizukuAvailable || state.isDhizukuAvailable
    val context = LocalContext.current
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showUnfreezeConfirmation by remember { mutableStateOf(false) }
    var showSupportSheet by remember { mutableStateOf(false) }

    // The any-file switch is backed by PackageManager, not DataStore, so nothing pushes a change at
    // us: `pm enable`, a ROM app manager, or a wipe of Thor's component state all move it silently.
    // Re-read on every resume but the first, which the VM's init already covered.
    var firstResume by rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else viewModel.refreshAnyFileOpener()
        onPauseOrDispose { }
    }

    // The language the row and the picker's checkmark are allowed to name.
    //
    // `LocalConfiguration.current` is the Configuration this composition is being drawn with —
    // `AndroidCompositionLocals` seeds it from `view.context.resources.configuration`, and that
    // context is the Activity, whose base `AppLocale.wrap` overrode. So this is not what Thor
    // believes it applied; it is what the screen is rendering in, read back off the screen. If the
    // override ever fails to take, this reports the locale the user is actually looking at and the
    // row says so, which is precisely what the old `when (prefs.language)` could not do — it read
    // the persisted preference and therefore confirmed every change, applied or not.
    //
    // Recomposition is automatic on both paths: a locale change recreates the activity (the
    // platform's on 33+, `AppLocale.recreateOnChange` below it), which rebuilds this composition
    // with the new Configuration.
    val shownLanguage = displayedLanguage(
        persistedTag = prefs.language,
        appliedTag = LocalConfiguration.current.locales
            .takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
    )

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
            // Default tab. Shaped like the Theme row in APPEARANCE — a titled row with a segmented
            // control underneath — rather than a picker sheet, because four options fit.
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(R.drawable.home)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.default_tab),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.default_tab_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Icons, not labels, and the nav bar's own glyphs: four equal slots across a phone
                // leave roughly 34 dp of text apiece, which turns "App List" into two characters and
                // its longer translations into fewer. The tab name still travels — as each button's
                // content description, which is what a screen reader announces either way.
                ConnectedButtonGroup(
                    items = DefaultTab.entries.map { tab ->
                        val destination = tab.toDestination()
                        ConnectedButtonGroupItem.Icon(
                            icon = ImageVector.vectorResource(destination.selectedIcon),
                            contentDescription = stringResource(destination.label)
                        )
                    },
                    selectedIndex = DefaultTab.entries.indexOf(prefs.defaultTab),
                    onItemSelected = { viewModel.setDefaultTab(DefaultTab.entries[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsSwitchRow(
                icon = R.drawable.apk_install,
                title = stringResource(R.string.show_reinstall_card),
                subtitle = stringResource(R.string.show_reinstall_card_desc),
                checked = prefs.showReinstallAllCard,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setReinstallAllCardVisibility(it) }
            )

            SettingsSwitchRow(
                icon = R.drawable.apk_install,
                title = stringResource(R.string.show_installer_tile),
                subtitle = stringResource(R.string.show_installer_tile_desc),
                checked = prefs.showInstallerTile,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setInstallerTileVisibility(it) }
            )

            // Reads its state from `uiState`, not `prefs`: this switch is backed by PackageManager
            // component state rather than DataStore. See AnyFileOpenerController.
            SettingsSwitchRow(
                icon = R.drawable.apk_install,
                title = stringResource(R.string.any_file_opener),
                subtitle = stringResource(R.string.any_file_opener_desc),
                checked = state.anyFileOpenerEnabled,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setAnyFileOpenerEnabled(it) }
            )

            SettingsSwitchRow(
                icon = R.drawable.round_extension,
                title = stringResource(R.string.show_extensions_tile),
                subtitle = stringResource(R.string.show_extensions_tile_desc),
                checked = prefs.showExtensionsTile,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setExtensionsTileVisibility(it) }
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

            // Grid density. Same titled-row-plus-segmented-control shape as Theme above it, and it
            // lives here rather than in GENERAL because it changes how the app looks, not what it
            // does. One control for every grid Thor draws — the Apps tab, the Freezer, and the two
            // pickers in the Freezer's sheets — because a per-screen density is a setting the user
            // has to find four times.
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(R.drawable.grid_view)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            stringResource(R.string.grid_density),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.grid_density_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                ConnectedButtonGroup(
                    items = AppGridDensity.entries.map {
                        val label = when (it) {
                            AppGridDensity.COMPACT -> stringResource(R.string.grid_density_compact)
                            AppGridDensity.DEFAULT -> stringResource(R.string.grid_density_default)
                            AppGridDensity.LARGE -> stringResource(R.string.grid_density_large)
                        }
                        ConnectedButtonGroupItem.Label(label)
                    },
                    selectedIndex = AppGridDensity.entries.indexOf(prefs.appGridDensity),
                    onItemSelected = { viewModel.setAppGridDensity(AppGridDensity.entries[it]) },
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
                subtitle = stringResource(shownLanguage.labelRes),
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
            // Deliberately left enabled when the device cannot authenticate. A disabled row
            // swallows the tap (`clickable(enabled = false)`), so a user whose device has nothing
            // enrolled got a greyed-out switch and silence — the subtitle was the only clue, and it
            // is a line of body text under a control that no longer responds. Tappable, the refusal
            // in `setBiometricLock` can answer with a toast that names what is missing. `checked`
            // stays bound to the preference, so a refused tap settles straight back.
            SettingsSwitchRow(
                icon = R.drawable.round_key,
                title = stringResource(R.string.biometric_lock),
                subtitle = if (state.canUseBiometric) {
                    stringResource(R.string.biometric_lock_desc)
                } else {
                    stringResource(R.string.biometric_not_available)
                },
                checked = prefs.biometricLockEnabled,
                onCheckedChange = { viewModel.setBiometricLock(it) }
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── PERMISSIONS ─────────────────────────────────────────────────────
        SettingsSectionLabel(stringResource(R.string.permissions))

        val usageAccessManager = koinInject<UsageAccessManager>()
        val lifecycleOwner = LocalLifecycleOwner.current
        var usageGranted by remember { mutableStateOf(usageAccessManager.isGranted()) }
        var notificationsGranted by remember {
            mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
        }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    usageGranted = usageAccessManager.isGranted()
                    notificationsGranted =
                        NotificationManagerCompat.from(context).areNotificationsEnabled()
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
            // The row itself is unconditional, because what it reports —
            // areNotificationsEnabled(), the exact thing BulkResultNotifier checks — is
            // meaningful and user-toggleable all the way down to minSdk 28. Only the *way* it is
            // granted differs: a runtime permission on 33+, an app-level toggle in system
            // settings below that. Gating the whole row on 33 left users on 28-32 with silently
            // dropped bulk-result notifications and nothing in-app explaining why.
            //
            // Registering the launcher inside the version check is safe: SDK_INT is constant for
            // the process, so the conditional group is stable across recompositions. It also
            // keeps every POST_NOTIFICATIONS reference inside the check, which is what lint's
            // InlinedApi wants.
            val requestNotificationPermission: (() -> Unit)? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val activity = remember(context) { context.findActivity() }
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { _ ->
                        // Re-read instead of trusting `granted`. The switch reflects
                        // areNotificationsEnabled(), which is also false when the permission is
                        // held but the user muted the app's notifications; trusting `granted`
                        // flipped the switch on and the next ON_RESUME flipped it back off.
                        val enabled =
                            NotificationManagerCompat.from(context).areNotificationsEnabled()
                        notificationsGranted = enabled
                        // RequestPermission returns immediately without showing a dialog once
                        // the user has denied twice, which would leave this row a permanent
                        // dead end. shouldShowRequestPermissionRationale distinguishes that
                        // (and the "granted but muted" case, both false) from a plain first
                        // denial (true, where re-tapping the row still shows the dialog).
                        // Where the dialog can no longer help, deep-link to system settings
                        // like the usage-access row above. Thor never self-grants this.
                        val canAskAgain = activity?.let {
                            ActivityCompat.shouldShowRequestPermissionRationale(
                                it,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        } ?: false
                        if (!enabled && !canAskAgain) {
                            runCatching {
                                context.startActivity(appNotificationSettingsIntent(context))
                            }
                        }
                    }
                    val request = {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                    request
                } else {
                    null
                }

            SettingsSwitchRow(
                icon = R.drawable.frozen,
                title = stringResource(R.string.notification_access),
                subtitle = if (notificationsGranted) {
                    stringResource(R.string.notification_access_granted_subtitle)
                } else {
                    stringResource(R.string.notification_access_needed_subtitle)
                },
                checked = notificationsGranted,
                onCheckedChange = {
                    if (!notificationsGranted) {
                        // 33+: only the system dialog can grant this. Thor never self-grants it
                        // even when it holds root/Shizuku — the dialog grants the identical
                        // capability, and Dhizuku cannot self-grant at all.
                        // 28-32: there is no runtime permission to request, so the only lever
                        // is the app-level toggle; deep-link to it, as the usage-access row does.
                        if (requestNotificationPermission != null) {
                            requestNotificationPermission()
                        } else {
                            runCatching {
                                context.startActivity(appNotificationSettingsIntent(context))
                            }
                        }
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
                icon = R.drawable.danger,
                title = stringResource(R.string.skip_routine_freeze_confirmation),
                subtitle = if (hasPrivilege) stringResource(R.string.skip_routine_freeze_confirmation_desc) else stringResource(
                    R.string.privilege_required_warning
                ),
                checked = prefs.skipRoutineFreezeConfirmation,
                enabled = hasPrivilege,
                enableMarqueeOnClick = true,
                onCheckedChange = { viewModel.setSkipRoutineFreezeConfirmation(it) }
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

        // ── BACKUP & RESTORE ────────────────────────────────────────────────
        // Root/Shizuku/Dhizuku only: there is no unprivileged path to another app's data, so an
        // unprivileged user offered this would reach a screen whose only content is a refusal.
        if (hasPrivilege) {
            Spacer(Modifier.height(32.dp))

            SettingsSectionLabel(stringResource(R.string.backup_and_restore))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(8.dp)
            ) {
                // §8.5's notice, where a user who is not looking for it will still see it. Injected
                // here rather than threaded through SettingsViewModel: it is one flow that no other
                // part of Settings needs, and putting it in the view model that owns every preference
                // would make a backup concern part of that class's surface.
                //
                // The use case, not `ArchiveBreadcrumbStore.observe()` directly. A live observation of
                // the raw breadcrumb is *wrong* on this surface: the breadcrumb is written at the start
                // of the destructive phase, `ArchiveRestore` is registered as a detail pane, and on an
                // expanded window this section is composed beside it — so a raw observe() renders "did
                // not finish … restore it again" next to a progress bar reporting normal progress. The
                // use case holds the notice back while a restore for that app is live, and still takes
                // it down the moment "Got it" over there clears the breadcrumb. Both of its inputs read
                // off the main thread.
                val observeInterrupted = koinInject<ObserveInterruptedRestoreUseCase>()
                val interrupted by remember(observeInterrupted) { observeInterrupted() }
                    .collectAsStateWithLifecycle(initialValue = null)
                // Not dismissible here on purpose: the row beneath it leads to the screen that can
                // clear it, and a dismiss in two places is two chances to lose the notice.
                interrupted?.let { crumb ->
                    Text(
                        text = stringResource(R.string.restore_interrupted, crumb.appLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
                SettingsClickRow(
                    icon = R.drawable.settings_backup_restore,
                    title = stringResource(R.string.restore_title),
                    subtitle = stringResource(R.string.restore_settings_desc),
                    onClick = onNavigateToRestore
                )

                // §5.4. The only place "remember it on this device" — offered as a checkbox in the
                // backup sheet — can be undone, and the only place a stored passphrase can be replaced
                // without making a backup to do it.
                var showPassphrase by remember { mutableStateOf(false) }

                SettingsClickRow(
                    icon = R.drawable.round_key,
                    title = stringResource(R.string.passphrase_settings_title),
                    subtitle = stringResource(R.string.passphrase_settings_desc),
                    onClick = { showPassphrase = true }
                )

                // Hosted at its row, which is the exception in this composable and not the
                // convention. The three other overlays here — the unfreeze-all AlertDialog, the
                // language sheet and the support sheet — all sit at the screen root with only their
                // trigger down among the rows: the dialog before this scrolling Column opens, the two
                // sheets after it closes. This one is the first that keeps its state and its host
                // beside the row they belong to.
                //
                // (Written without line numbers on purpose. The first draft of this comment cited
                // three, and editing the comment itself moved one of them — and asserted a shared
                // "after the Column closes" that was only ever true of two of the three.)
                //
                // Still correct where it is: a ModalBottomSheet draws in its own window over the
                // whole screen no matter where it is composed, so being inside this Column costs it
                // nothing.
                if (showPassphrase) {
                    PassphraseSettingsSheet(onDismiss = { showPassphrase = false })
                }
            }
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
            // The same value the row shows, for the same reason: a checkmark against Français on a
            // screen rendering English is the identical lie in a smaller font.
            selectedLanguage = shownLanguage,
            onLanguageSelected = { language ->
                viewModel.setLanguage(language.tag)
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

/**
 * The label for each entry, the only Android-side half of [AppLanguage].
 *
 * Split out so the tag list and the label list cannot drift apart: they used to be two hand-written
 * `when`/`listOf` blocks — one in the row, one in this sheet — and a language added to one but not
 * the other showed up as a row reading "System default" over a checkmark next to its own name.
 */
private val AppLanguage.labelRes: Int
    @StringRes get() = when (this) {
        AppLanguage.SystemDefault -> R.string.system_default
        AppLanguage.English -> R.string.english
        AppLanguage.Chinese -> R.string.chinese
        AppLanguage.French -> R.string.french
        AppLanguage.Spanish -> R.string.spanish
        AppLanguage.Arabic -> R.string.arabic
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageBottomSheet(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
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

            AppLanguage.PICKER_ORDER.forEach { language ->
                val isSelected = selectedLanguage == language
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onLanguageSelected(language) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(language.labelRes),
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

/**
 * The system's per-app notification settings screen — the only place a user can undo a
 * permanent POST_NOTIFICATIONS denial or re-enable app-wide notifications.
 */
private fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/**
 * Unwrap [LocalContext] to the hosting Activity. Compose hands out a ContextWrapper in some
 * hosts, and `shouldShowRequestPermissionRationale` needs the real Activity.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
