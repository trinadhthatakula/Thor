// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.usecase.ObserveInterruptedRestoreUseCase
import com.valhalla.thor.presentation.main.toDestination
import com.valhalla.thor.util.displayedLanguage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * The settings index: eight doors, a search field, and the state that decides which door to open.
 *
 * This screen used to *be* Settings — one `Column(verticalScroll)` roughly 3000 dp tall holding 29
 * entries under ten hand-written section headers, growing by a section every feature. The index's
 * length is now fixed at eight and the number of settings is not; a new setting is an entry in
 * [SettingsRowId], and [SettingsCategoryScreen]'s exhaustive `when` will not compile until something
 * draws it.
 *
 * @param onOpenCategory push a category. The second argument is the row search matched, or null for
 *   an ordinary tap on a category row — see [SettingsCategoryScreen] for what it does with it.
 * @param selectedCategory the category showing in the detail pane beside this one, so its row can be
 *   marked as the active one. Null on a phone, where this screen and a category are never on screen
 *   together and a persistent highlight would only be a row that looks stuck. `MainScreen` decides
 *   which case applies from the pane directive, not from a width breakpoint.
 */
@Composable
fun SettingsScreen(
    onOpenCategory: (SettingsCategory, SettingsRowId?) -> Unit,
    selectedCategory: SettingsCategory? = null,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val hasPrivilege = state.isRootAvailable || state.isShizukuAvailable || state.isDhizukuAvailable
    val context = LocalContext.current

    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        }.getOrDefault("—")
    }

    // §8.5's notice, now at the index rather than inside the Backup card.
    //
    // It was under `if (hasPrivilege)`, which is the one condition under which the person who most
    // needs it cannot see it: a restore is interrupted precisely when something went wrong, and a
    // Shizuku service that died between the write and the reboot takes the notice down with it. The
    // door below stays privilege-gated — an unprivileged user cannot finish the restore — but the
    // statement that their app's data may be half-written is a fact, not an offer.
    //
    // The use case, not `ArchiveBreadcrumbStore.observe()` directly: the breadcrumb is written at the
    // start of the destructive phase, and the restore sheet is hosted by `MainScreen` over whatever
    // section is showing, so a raw observe() would render "did not finish" beneath a progress bar
    // reporting normal progress. The use case holds the notice back while a restore for that app is
    // live, and still takes it down the moment "Got it" clears the breadcrumb.
    val observeInterrupted = koinInject<ObserveInterruptedRestoreUseCase>()
    val interrupted by remember(observeInterrupted) { observeInterrupted() }
        .collectAsStateWithLifecycle(initialValue = null)

    // The language the Appearance summary is allowed to name.
    //
    // `LocalConfiguration.current` is the Configuration this composition is being drawn with, so
    // this is not what Thor believes it applied; it is what the screen is rendering in, read back
    // off the screen. See `displayedLanguage`.
    val shownLanguage = displayedLanguage(
        persistedTag = prefs.language,
        appliedTag = LocalConfiguration.current.locales
            .takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
    )

    val availableModes = buildList {
        if (state.isRootAvailable) add(PrivilegeMode.ROOT)
        if (state.isShizukuAvailable) add(PrivilegeMode.SHIZUKU)
        if (state.isDhizukuAvailable) add(PrivilegeMode.DHIZUKU)
    }

    // Backup and Extensions are privileged surfaces: there is no unprivileged path to another app's
    // data, and the extension manager is a third-party code host. An unprivileged user offered
    // either would reach a screen whose only content is a refusal.
    //
    // Backup has one exception, and it is the reason the notice above is not gated: a breadcrumb can
    // outlive the privilege that wrote it. Hiding the door while showing the notice would leave a
    // statement of damage with nothing to act on.
    val visibleCategories = SettingsCategory.entries.filter { category ->
        when (category) {
            SettingsCategory.BACKUP -> hasPrivilege || interrupted != null
            SettingsCategory.EXTENSIONS -> hasPrivilege
            else -> true
        }
    }

    var query by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 64.dp, bottom = 120.dp)
    ) {
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

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.round_search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            painter = painterResource(R.drawable.round_close),
                            contentDescription = stringResource(R.string.cd_settings_search_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        interrupted?.let { crumb ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.warning),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.restore_interrupted, crumb.appLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        if (query.isBlank()) {
            // The engine picker, unconditional where it used to need two engines to appear.
            //
            // `availableModes.size > 1` hid the one line that answers "how is Thor doing this?" from
            // every single-engine device — which is most of them. A one-button group is not a choice,
            // but it is an answer, and the icon beside it names the engine at a glance.
            if (availableModes.isNotEmpty()) {
                val activeMode = prefs.preferredPrivilegeMode?.takeIf { it in availableModes }
                    ?: availableModes.first()
                SettingsPickerRow(
                    icon = activeMode.iconRes(),
                    title = stringResource(R.string.active_engine),
                    subtitle = stringResource(R.string.active_engine_desc),
                    items = availableModes.map {
                        ConnectedButtonGroupItem.Label(stringResource(it.labelRes))
                    },
                    selectedIndex = availableModes.indexOf(activeMode),
                    onItemSelected = { viewModel.setPrivilegeMode(availableModes[it]) }
                )
                Spacer(Modifier.height(16.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleCategories.forEach { category ->
                    SettingsClickRow(
                        icon = category.icon,
                        title = stringResource(category.title),
                        subtitle = categorySummary(
                            category = category,
                            state = state,
                            hasPrivilege = hasPrivilege,
                            shownLanguageLabel = stringResource(shownLanguage.labelRes),
                            versionName = versionName,
                            restoreInterrupted = interrupted != null
                        ),
                        showChevron = true,
                        highlighted = selectedCategory == category,
                        onClick = { onOpenCategory(category, null) }
                    )
                }
            }
        } else {
            SettingsSearchResults(
                query = query,
                visibleCategories = visibleCategories,
                onOpenRow = { row -> onOpenCategory(row.category, row) }
            )
        }

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
    }
}

/**
 * What each door says about the room behind it.
 *
 * The point of the index is that eight rows replace 29, and eight rows that only say their own name
 * would make that a straight loss: the user would have to open a category to find out whether the
 * thing they came for is already on. Each summary names the state that is worth crossing a screen
 * boundary to learn.
 *
 * Deliberately short of exhaustive — Appearance has six settings and reports three. The line is a
 * reason to tap, not a substitute for tapping.
 */
@Composable
private fun categorySummary(
    category: SettingsCategory,
    state: SettingsViewModel.SettingsUiState,
    hasPrivilege: Boolean,
    shownLanguageLabel: String,
    versionName: String,
    restoreInterrupted: Boolean,
): String {
    val prefs = state.prefs
    val parts: List<String> = when (category) {
        SettingsCategory.APPEARANCE -> listOf(
            stringResource(prefs.themeMode.labelRes),
            stringResource(prefs.appGridDensity.labelRes),
            shownLanguageLabel
        )

        SettingsCategory.HOME -> listOf(
            stringResource(
                R.string.settings_summary_opens_on,
                stringResource(prefs.defaultTab.toDestination().label)
            )
        )

        SettingsCategory.FREEZER -> if (!hasPrivilege) {
            listOf(stringResource(R.string.settings_summary_unprivileged))
        } else {
            buildList {
                add(
                    stringResource(
                        if (prefs.autoFreezeEnabled) R.string.settings_summary_auto_freeze_on
                        else R.string.settings_summary_auto_freeze_off
                    )
                )
                if (prefs.freezerMode == FreezerMode.SUSPEND) {
                    add(stringResource(R.string.settings_summary_suspend_mode))
                }
            }
        }

        SettingsCategory.INSTALLING -> listOf(
            stringResource(
                if (prefs.autoReinstallEnabled) R.string.settings_summary_auto_reinstall_on
                else R.string.settings_summary_auto_reinstall_off
            )
        )

        SettingsCategory.SECURITY -> listOf(
            stringResource(
                if (prefs.biometricLockEnabled) R.string.settings_summary_app_lock_on
                else R.string.settings_summary_app_lock_off
            )
        )

        SettingsCategory.BACKUP -> listOf(
            stringResource(
                if (restoreInterrupted) R.string.settings_summary_restore_interrupted
                else R.string.settings_summary_backup
            )
        )

        SettingsCategory.EXTENSIONS -> listOf(stringResource(R.string.manage_extensions_desc))

        SettingsCategory.ABOUT -> listOf(versionName)
    }
    return parts.joinToString(" · ")
}

/**
 * Search, over the catalogue rather than over the screen.
 *
 * Matching is on [SettingsRowId.title] and [SettingsRowId.keywords], both static. Several rows swap
 * their subtitle at runtime — the language row names the current language, the permission rows say
 * granted or needed, the Freezer rows say "requires privilege" when there is none — and indexing
 * whichever sentence happens to be showing would make a row findable on one device and not another.
 *
 * Rows in a hidden category are not offered, because tapping one would push a door the index itself
 * has decided not to show.
 */
@Composable
private fun SettingsSearchResults(
    query: String,
    visibleCategories: List<SettingsCategory>,
    onOpenRow: (SettingsRowId) -> Unit,
) {
    val matches = visibleCategories
        .flatMap { SettingsRowId.rowsIn(it) }
        .map { row -> Triple(row, stringResource(row.title), stringResource(row.keywords)) }
        .filter { (_, title, keywords) ->
            title.contains(query, ignoreCase = true) || keywords.contains(query, ignoreCase = true)
        }

    if (matches.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_search_no_results, query),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp)
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        matches.forEach { (row, title, _) ->
            SettingsClickRow(
                icon = row.category.icon,
                title = title,
                subtitle = stringResource(
                    R.string.settings_search_result_in,
                    stringResource(row.category.title)
                ),
                showChevron = true,
                onClick = { onOpenRow(row) }
            )
        }
    }
}

/**
 * What the detail pane shows on a wide window before a category has been picked.
 *
 * Public because `MainScreen` hands it to `ListDetailSceneStrategy.listPane` as the placeholder, and
 * it belongs beside the screen it stands in for rather than among the navigation wiring.
 */
@Composable
fun SettingsDetailPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.thor_mono),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_select_category),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
