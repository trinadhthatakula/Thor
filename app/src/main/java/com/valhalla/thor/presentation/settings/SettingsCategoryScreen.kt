// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.R
import com.valhalla.thor.data.manager.UsageAccessManager
import com.valhalla.thor.domain.model.AnimationIntensity
import com.valhalla.thor.domain.model.AppGridDensity
import com.valhalla.thor.domain.model.DefaultTab
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.domain.usecase.ObserveInterruptedRestoreUseCase
import com.valhalla.thor.presentation.common.rememberNotificationPermissionRequest
import com.valhalla.thor.presentation.main.toDestination
import com.valhalla.thor.presentation.utils.ObserveAsEvents
import com.valhalla.thor.util.AppLanguage
import com.valhalla.thor.util.displayedLanguage
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** How long a row search sent the user to stays lit before settling back to an ordinary row. */
private const val FOCUS_HIGHLIGHT_MILLIS = 2_600L

/**
 * One of the eight doors, opened.
 *
 * Every setting Thor has is drawn from exactly one branch of the `when` in here, over
 * [SettingsRowId]. That is the gate the old screen never had: a row registered in the catalogue with
 * no renderer is a non-exhaustive `when` on an enum subject, which Kotlin has rejected outright since
 * 1.7 — so "I added the setting but forgot to draw it" fails the **build**, not a device walk.
 *
 * The `when` covers all twenty-five rows rather than only this category's, because it is one function
 * serving eight screens. Moving a row between categories is therefore a one-line change to
 * [SettingsRowId] and nothing else.
 *
 * @param focus the row search matched, or null. It is scrolled to and lit for
 *   [FOCUS_HIGHLIGHT_MILLIS]; a search that lands you on a screen of six near-identical rows and
 *   leaves you to find the seventh yourself has only moved the problem.
 */
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    focus: SettingsRowId?,
    onBack: () -> Unit,
    onOpenRestore: () -> Unit,
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
    var showPassphrase by remember { mutableStateOf(false) }

    // The any-file switch is backed by PackageManager, not DataStore, so nothing pushes a change at
    // us: `pm enable`, a ROM app manager, or a wipe of Thor's component state all move it silently.
    // Re-read on every resume but the first, which the VM's init already covered.
    var firstResume by rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else viewModel.refreshAnyFileOpener()
        onPauseOrDispose { }
    }

    // Collected here and not on the index, because every action that can raise one lives behind a
    // door: the biometric refusal, the dropped language write, the unfreeze-all result. On a phone
    // the index is not composed while a category is open, so a collector there would be the wrong
    // half of the section.
    ObserveAsEvents(viewModel.events) { event ->
        android.widget.Toast.makeText(
            context,
            event.asString(context),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    val shownLanguage = displayedLanguage(
        persistedTag = prefs.language,
        appliedTag = LocalConfiguration.current.locales
            .takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
    )

    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        }.getOrDefault("—")
    }

    // Permission state, hoisted rather than composed only for the Security category. Both reads are
    // local AppOps/NotificationManager calls and the observer is one lifecycle listener, which is
    // cheaper than the conditional composition it would take to skip them — and a conditional
    // `remember` here would be keyed on a parameter, which is exactly the shape that breaks the day
    // something recomposes this screen with a different category.
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

    // The whole flow — the launcher, the re-read that must not trust `granted`, the resume observer,
    // and the two-denials dead end — now lives in one composable shared with the job sheets, which is
    // the second caller it was extracted for. `deepLinkWhenBlocked = true` because the user tapped
    // this row: doing nothing here would leave a switch that snaps back with no explanation.
    val notifications = rememberNotificationPermissionRequest(deepLinkWhenBlocked = true)

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

    val rows = remember(category) { SettingsRowId.rowsIn(category) }
    val listState = rememberLazyListState()

    // Held in state rather than read straight off the parameter, so the highlight can be released
    // without popping the route: `focus` is part of the back stack entry and survives as long as the
    // screen does. A row that stays lit forever stops meaning "this is the one you searched for".
    var highlighted by remember(focus) { mutableStateOf(focus) }
    LaunchedEffect(focus, rows) {
        val target = focus ?: return@LaunchedEffect
        val index = rows.indexOf(target)
        if (index >= 0) listState.animateScrollToItem(index)
        delay(FOCUS_HIGHLIGHT_MILLIS)
        highlighted = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsTopBar(title = stringResource(category.title), onBack = onBack)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rows, key = { it.name }) { row ->
                val lit = highlighted == row
                // The gate. Every SettingsRowId has to be drawn somewhere in here or this stops
                // compiling — see the KDoc on this file's composable.
                when (row) {
                    // ── Appearance ──────────────────────────────────────────────────────────────
                    SettingsRowId.THEME -> SettingsPickerRow(
                        icon = R.drawable.theme_panel,
                        title = stringResource(R.string.theme),
                        subtitle = stringResource(R.string.theme_desc),
                        items = ThemeMode.entries.map {
                            ConnectedButtonGroupItem.Label(stringResource(it.labelRes))
                        },
                        selectedIndex = ThemeMode.entries.indexOf(prefs.themeMode),
                        onItemSelected = { viewModel.setThemeMode(ThemeMode.entries[it]) },
                        highlighted = lit
                    )

                    SettingsRowId.GRID_DENSITY -> SettingsPickerRow(
                        icon = R.drawable.grid_view,
                        title = stringResource(R.string.grid_density),
                        subtitle = stringResource(R.string.grid_density_desc),
                        items = AppGridDensity.entries.map {
                            ConnectedButtonGroupItem.Label(stringResource(it.labelRes))
                        },
                        selectedIndex = AppGridDensity.entries.indexOf(prefs.appGridDensity),
                        onItemSelected = { viewModel.setAppGridDensity(AppGridDensity.entries[it]) },
                        highlighted = lit
                    )

                    // Was its own top-level section, whose all-caps header repeated the title of the
                    // single row underneath it. As one row in Appearance, it says its name once.
                    SettingsRowId.ANIMATION_INTENSITY -> SettingsPickerRow(
                        icon = R.drawable.bolt,
                        title = stringResource(R.string.animation_intensity),
                        subtitle = stringResource(R.string.animation_intensity_desc),
                        items = AnimationIntensity.entries.map {
                            ConnectedButtonGroupItem.Label(stringResource(it.labelRes))
                        },
                        selectedIndex = AnimationIntensity.entries.indexOf(prefs.animationIntensity),
                        onItemSelected = {
                            viewModel.setAnimationIntensity(AnimationIntensity.entries[it])
                        },
                        highlighted = lit
                    )

                    SettingsRowId.AMOLED -> SettingsSwitchRow(
                        icon = R.drawable.theme_panel,
                        title = stringResource(R.string.amoled_mode),
                        subtitle = stringResource(R.string.amoled_desc),
                        checked = prefs.useAmoled,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setAmoledMode(it) }
                    )

                    SettingsRowId.DYNAMIC_COLORS -> SettingsSwitchRow(
                        icon = R.drawable.shield_with_heart,
                        title = stringResource(R.string.dynamic_colors),
                        subtitle = stringResource(R.string.dynamic_colors_desc),
                        checked = prefs.useDynamicColor,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setDynamicColor(it) }
                    )

                    // Subtitle is the value, not the description: which language Thor is speaking is
                    // the one thing you cannot read off this screen if it is speaking the wrong one.
                    // Search still finds the row by its description — see SettingsRowId.keywords.
                    SettingsRowId.APP_LANGUAGE -> SettingsClickRow(
                        icon = R.drawable.settings_backup_restore,
                        title = stringResource(R.string.app_language),
                        subtitle = stringResource(shownLanguage.labelRes),
                        showChevron = true,
                        highlighted = lit,
                        onClick = { showLanguageSheet = true }
                    )

                    // ── Home screen ─────────────────────────────────────────────────────────────
                    // Icons, not labels, and the nav bar's own glyphs: four equal slots across a
                    // phone leave roughly 34 dp of text apiece, which turns "App List" into two
                    // characters and its longer translations into fewer. The tab name still travels
                    // — as each button's content description, which is what a screen reader
                    // announces either way.
                    SettingsRowId.DEFAULT_TAB -> SettingsPickerRow(
                        icon = R.drawable.home,
                        title = stringResource(R.string.default_tab),
                        subtitle = stringResource(R.string.default_tab_desc),
                        items = DefaultTab.entries.map { tab ->
                            val destination = tab.toDestination()
                            ConnectedButtonGroupItem.Icon(
                                icon = ImageVector.vectorResource(destination.selectedIcon),
                                contentDescription = stringResource(destination.label)
                            )
                        },
                        selectedIndex = DefaultTab.entries.indexOf(prefs.defaultTab),
                        onItemSelected = { viewModel.setDefaultTab(DefaultTab.entries[it]) },
                        highlighted = lit
                    )

                    SettingsRowId.SHOW_REINSTALL_CARD -> SettingsSwitchRow(
                        icon = R.drawable.apk_install,
                        title = stringResource(R.string.show_reinstall_card),
                        subtitle = stringResource(R.string.show_reinstall_card_desc),
                        checked = prefs.showReinstallAllCard,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setReinstallAllCardVisibility(it) }
                    )

                    SettingsRowId.SHOW_INSTALLER_TILE -> SettingsSwitchRow(
                        icon = R.drawable.apk_install,
                        title = stringResource(R.string.show_installer_tile),
                        subtitle = stringResource(R.string.show_installer_tile_desc),
                        checked = prefs.showInstallerTile,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setInstallerTileVisibility(it) }
                    )

                    SettingsRowId.SHOW_EXTENSIONS_TILE -> SettingsSwitchRow(
                        icon = R.drawable.round_extension,
                        title = stringResource(R.string.show_extensions_tile),
                        subtitle = stringResource(R.string.show_extensions_tile_desc),
                        checked = prefs.showExtensionsTile,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setExtensionsTileVisibility(it) }
                    )

                    // ── Freezer ─────────────────────────────────────────────────────────────────
                    SettingsRowId.AUTO_FREEZE -> SettingsSwitchRow(
                        icon = R.drawable.frozen,
                        title = stringResource(R.string.auto_freeze),
                        subtitle = privilegeAwareSubtitle(hasPrivilege, R.string.auto_freeze_desc),
                        checked = prefs.autoFreezeEnabled,
                        enabled = hasPrivilege,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setAutoFreezeEnabled(it) }
                    )

                    SettingsRowId.SUSPEND_INSTEAD_OF_FREEZE -> SettingsSwitchRow(
                        icon = R.drawable.frozen,
                        title = stringResource(R.string.suspend_instead_of_freeze),
                        subtitle = privilegeAwareSubtitle(
                            hasPrivilege,
                            R.string.suspend_instead_of_freeze_desc
                        ),
                        checked = prefs.freezerMode == FreezerMode.SUSPEND,
                        enabled = hasPrivilege,
                        highlighted = lit,
                        onCheckedChange = {
                            viewModel.setFreezerMode(
                                if (it) FreezerMode.SUSPEND else FreezerMode.FREEZE
                            )
                        }
                    )

                    SettingsRowId.SKIP_ROUTINE_FREEZE_CONFIRMATION -> SettingsSwitchRow(
                        icon = R.drawable.danger,
                        title = stringResource(R.string.skip_routine_freeze_confirmation),
                        subtitle = privilegeAwareSubtitle(
                            hasPrivilege,
                            R.string.skip_routine_freeze_confirmation_desc
                        ),
                        checked = prefs.skipRoutineFreezeConfirmation,
                        enabled = hasPrivilege,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setSkipRoutineFreezeConfirmation(it) }
                    )

                    SettingsRowId.ADD_FREEZER_TO_LAUNCHER -> SettingsSwitchRow(
                        icon = R.drawable.frozen,
                        title = stringResource(R.string.add_freezer_to_launcher),
                        subtitle = privilegeAwareSubtitle(
                            hasPrivilege,
                            R.string.add_freezer_to_launcher_desc
                        ),
                        checked = prefs.addFreezerToLauncher,
                        enabled = hasPrivilege,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setAddFreezerToLauncher(it) }
                    )

                    // The one row in Settings that *does* something the moment you confirm it, and
                    // it used to look exactly like App Language — same container, same chevron-less
                    // click row. Error colours and no chevron: this leads nowhere, it acts.
                    SettingsRowId.UNFREEZE_ALL -> SettingsClickRow(
                        icon = R.drawable.unfreeze,
                        title = stringResource(R.string.unfreeze_all_apps),
                        subtitle = privilegeAwareSubtitle(
                            hasPrivilege,
                            R.string.unfreeze_all_apps_desc
                        ),
                        enabled = hasPrivilege,
                        destructive = true,
                        highlighted = lit,
                        onClick = { showUnfreezeConfirmation = true }
                    )

                    // ── Installing & sharing ────────────────────────────────────────────────────
                    SettingsRowId.AUTO_REINSTALL -> SettingsSwitchRow(
                        icon = R.drawable.settings_backup_restore,
                        title = stringResource(R.string.auto_reinstall),
                        subtitle = stringResource(R.string.auto_reinstall_desc),
                        checked = prefs.autoReinstallEnabled,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setAutoReinstallEnabled(it) }
                    )

                    // Reads its state from `uiState`, not `prefs`: this switch is backed by
                    // PackageManager component state rather than DataStore. See AnyFileOpenerController.
                    SettingsRowId.ANY_FILE_OPENER -> SettingsSwitchRow(
                        icon = R.drawable.apk_install,
                        title = stringResource(R.string.any_file_opener),
                        subtitle = stringResource(R.string.any_file_opener_desc),
                        checked = state.anyFileOpenerEnabled,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setAnyFileOpenerEnabled(it) }
                    )

                    // ── Security ────────────────────────────────────────────────────────────────
                    // Deliberately left enabled when the device cannot authenticate. A disabled row
                    // swallows the tap (`clickable(enabled = false)`), so a user whose device has
                    // nothing enrolled got a greyed-out switch and silence — the subtitle was the
                    // only clue, and it is a line of body text under a control that no longer
                    // responds. Tappable, the refusal in `setBiometricLock` can answer with a toast
                    // that names what is missing. `checked` stays bound to the preference, so a
                    // refused tap settles straight back.
                    SettingsRowId.BIOMETRIC_LOCK -> SettingsSwitchRow(
                        icon = R.drawable.round_key,
                        title = stringResource(R.string.biometric_lock),
                        subtitle = if (state.canUseBiometric) {
                            stringResource(R.string.biometric_lock_desc)
                        } else {
                            stringResource(R.string.biometric_not_available)
                        },
                        checked = prefs.biometricLockEnabled,
                        highlighted = lit,
                        onCheckedChange = { viewModel.setBiometricLock(it) }
                    )

                    SettingsRowId.USAGE_ACCESS -> SettingsSwitchRow(
                        icon = R.drawable.shield,
                        title = stringResource(R.string.usage_access),
                        subtitle = if (usageGranted) {
                            stringResource(R.string.usage_access_granted_subtitle)
                        } else {
                            stringResource(R.string.usage_access_needed_subtitle)
                        },
                        checked = usageGranted,
                        highlighted = lit,
                        onCheckedChange = {
                            // This op can't be toggled in-app; deep-link to system settings.
                            // Unconditionally, in both directions: the screen that grants usage
                            // access is the same screen that revokes it. Acting only on the
                            // off→on tap left the row dead once granted — the switch bounced
                            // back and nothing happened, with no way in-app to reach the
                            // revoke toggle the row is a picture of.
                            runCatching {
                                context.startActivity(usageAccessManager.usageAccessIntent())
                            }
                        }
                    )

                    // The row itself is unconditional, because what it reports —
                    // areNotificationsEnabled(), the exact thing BulkResultNotifier checks — is
                    // meaningful and user-toggleable all the way down to minSdk 28. Only the *way*
                    // it is granted differs: a runtime permission on 33+, an app-level toggle in
                    // system settings below that. Gating the whole row on 33 left users on 28-32
                    // with silently dropped bulk-result notifications and nothing in-app explaining
                    // why.
                    SettingsRowId.NOTIFICATION_ACCESS -> SettingsSwitchRow(
                        icon = R.drawable.frozen,
                        title = stringResource(R.string.notification_access),
                        subtitle = if (notifications.isEnabled) {
                            stringResource(R.string.notification_access_granted_subtitle)
                        } else {
                            stringResource(R.string.notification_access_needed_subtitle)
                        },
                        checked = notifications.isEnabled,
                        highlighted = lit,
                        onCheckedChange = {
                            if (!notifications.isEnabled && notifications.canRequest) {
                                // 33+: the system dialog. A privileged user will usually never see
                                // this row switched off — `SelfPermissionGranter` grants
                                // POST_NOTIFICATIONS as soon as root or Shizuku is live — but the
                                // dialog is still the route for everyone else, and for a privileged
                                // user who has muted Thor app-wide, which no `pm grant` can undo.
                                notifications.request()
                            } else {
                                // 28-32 there is no runtime permission to request, and on 33+ there
                                // is no dialog that *withdraws* one — requesting again while granted
                                // returns granted without showing anything. Either way the app-level
                                // toggle is the only lever, so the on→off tap deep-links to it
                                // instead of doing nothing and letting the switch snap back.
                                notifications.openNotificationSettings()
                            }
                        }
                    )

                    // ── Backup & restore ────────────────────────────────────────────────────────
                    SettingsRowId.RESTORE -> Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // The same notice the index carries, kept beside the row that acts on it.
                        // Collected in this branch rather than at the top of the screen so the
                        // breadcrumb observer and its WorkManager watcher only run on the one
                        // category that can do anything about them.
                        val observeInterrupted = koinInject<ObserveInterruptedRestoreUseCase>()
                        val interrupted by remember(observeInterrupted) { observeInterrupted() }
                            .collectAsStateWithLifecycle(initialValue = null)
                        // Not dismissible here on purpose: the row beneath it leads to the sheet that
                        // can clear it, and a dismiss in two places is two chances to lose the notice.
                        interrupted?.let { crumb ->
                            Text(
                                text = stringResource(R.string.restore_interrupted, crumb.appLabel),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                        SettingsClickRow(
                            icon = R.drawable.settings_backup_restore,
                            title = stringResource(R.string.restore_title),
                            subtitle = stringResource(R.string.restore_settings_desc),
                            showChevron = true,
                            highlighted = lit,
                            onClick = onOpenRestore
                        )
                    }

                    // §5.4. The only place "remember it on this device" — offered as a checkbox in
                    // the backup sheet — can be undone, and the only place a stored passphrase can be
                    // replaced without making a backup to do it.
                    SettingsRowId.PASSPHRASE -> SettingsClickRow(
                        icon = R.drawable.round_key,
                        title = stringResource(R.string.passphrase_settings_title),
                        subtitle = stringResource(R.string.passphrase_settings_desc),
                        showChevron = true,
                        highlighted = lit,
                        onClick = { showPassphrase = true }
                    )

                    // ── Extensions ──────────────────────────────────────────────────────────────
                    // Entry into the manager itself is further gated by a one-time
                    // liability-consent sheet (see ExtensionManagerScreen).
                    SettingsRowId.MANAGE_EXTENSIONS -> SettingsClickRow(
                        icon = R.drawable.round_extension,
                        title = stringResource(R.string.manage_extensions),
                        subtitle = stringResource(R.string.manage_extensions_desc),
                        showChevron = true,
                        highlighted = lit,
                        onClick = onNavigateToExtensionManager
                    )

                    // ── About & support ─────────────────────────────────────────────────────────
                    SettingsRowId.SUPPORT_DEVELOPER -> SettingsClickRow(
                        icon = R.drawable.shield_with_heart,
                        title = stringResource(R.string.support_developer),
                        subtitle = stringResource(R.string.support_developer_desc),
                        showChevron = true,
                        highlighted = lit,
                        onClick = { showSupportSheet = true }
                    )

                    SettingsRowId.VERSION -> VersionRow(versionName = versionName)

                    SettingsRowId.LINKS -> Row(
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
                                    Intent(Intent.ACTION_VIEW, "https://t.me/thorAppDev".toUri())
                                )
                            }
                        )
                    }
                }
            }
        }
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
        SupportDeveloperHelper(onDismiss = { showSupportSheet = false })
    }

    if (showPassphrase) {
        PassphraseSettingsSheet(onDismiss = { showPassphrase = false })
    }
}

/**
 * The Freezer's five rows all say the same thing when Thor holds no privilege, and it is not their
 * own description.
 */
@Composable
private fun privilegeAwareSubtitle(hasPrivilege: Boolean, @StringRes descriptionRes: Int): String =
    if (hasPrivilege) stringResource(descriptionRes)
    else stringResource(R.string.privilege_required_warning)

@Composable
private fun VersionRow(versionName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            SettingsIconBox(R.drawable.thor_mono)
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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
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
            .clip(RoundedCornerShape(24.dp))
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
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
            enabledValues = setOf(SheetValue.Expanded, SheetValue.Hidden)
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
