// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.valhalla.asgard.components.AsgardLabeledSlider
import com.valhalla.asgard.components.AsgardSettingToggleRow
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.asgard.components.ConnectedButtonGroupShapes
import com.valhalla.asgard.navigation.AsgardNavigationBar
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.*
import com.valhalla.thor.presentation.navigation.navigationConfiguration
import com.valhalla.thor.presentation.navigation.thorNavigationItems
import com.valhalla.thor.presentation.settings.SettingsTopBar
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private val NavigationDraftSaver = Saver<NavigationBarPreferences, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<NavigationBarPreferences>(it).normalized() }.getOrNull() },
)

/** A dedicated preview editor; changes remain local until Apply succeeds. */
@Composable
internal fun NavigationCustomizationScreen(
    preferences: NavigationBarPreferences,
    onBack: () -> Unit,
    onApply: suspend (NavigationBarPreferences) -> Boolean,
) {
    var draft by rememberSaveable(stateSaver = NavigationDraftSaver) { mutableStateOf(preferences.normalized()) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val currentApply by rememberUpdatedState(onApply)
    val currentBack by rememberUpdatedState(onBack)
    val leave = { if (!saving) currentBack() }
    BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SettingsTopBar(title = stringResource(R.string.customization_navigation), onBack = leave)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            val sideBySide = maxWidth >= 600.dp || (maxWidth >= 480.dp && maxHeight < 360.dp)
            val preview: @Composable () -> Unit = {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    AsgardNavigationBar(
                        items = thorNavigationItems(), selectedIndex = selected,
                        onSelect = { if (!saving) selected = it },
                        configuration = navigationConfiguration(draft, preview = true),
                    )
                }
            }
            val controls: @Composable () -> Unit = {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            stringResource(R.string.navigation_preview_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NavigationControls(draft, enabled = !saving, onDraftChange = { draft = it })
                    }
                }
            }
            if (sideBySide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { preview() }
                    Box(Modifier.weight(1f).fillMaxHeight()) { controls() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    preview()
                    Box(Modifier.fillMaxWidth().weight(1f)) { controls() }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column {
                if (failed) Text(
                    stringResource(R.string.navigation_save_failed),
                    Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(enabled = !saving, onClick = { draft = NavigationBarPreferences(); failed = false }) {
                        Text(stringResource(R.string.navigation_reset))
                    }
                    TextButton(enabled = !saving, onClick = leave) { Text(stringResource(R.string.cancel)) }
                    Button(enabled = !saving, onClick = {
                        saving = true
                        failed = false
                        scope.launch {
                            try {
                                if (currentApply(draft.normalized())) currentBack() else failed = true
                            } finally { saving = false }
                        }
                    }) { Text(stringResource(if (saving) R.string.navigation_saving else R.string.navigation_apply)) }
                }
            }
        }
    }
}

/** Edits the draft while preserving hidden icon-label choices and gating floating-only controls. */
@Composable
private fun NavigationControls(draft: NavigationBarPreferences, enabled: Boolean, onDraftChange: (NavigationBarPreferences) -> Unit) {
    NavigationChoice(R.string.navigation_placement, listOf(R.string.navigation_docked, R.string.navigation_floating), draft.placement.ordinal, enabled) {
        onDraftChange(draft.copy(placement = NavigationPlacement.entries[it]))
    }
    NavigationChoice(R.string.navigation_appearance, listOf(R.string.navigation_expanding, R.string.navigation_segmented, R.string.navigation_standard), draft.appearance.ordinal, enabled) {
        onDraftChange(draft.copy(appearance = NavigationAppearance.entries[it]))
    }
    NavigationChoice(R.string.navigation_labels, listOf(R.string.navigation_automatic, R.string.navigation_selected, R.string.navigation_all, R.string.navigation_hidden), draft.effectiveLabels.ordinal, enabled && draft.showIcons) {
        onDraftChange(draft.copy(labels = NavigationLabels.entries[it]))
    }
    AsgardSettingToggleRow(
        title = stringResource(R.string.navigation_icons), checked = draft.showIcons,
        onCheckedChange = { onDraftChange(draft.copy(showIcons = it).normalized()) },
        modifier = Modifier.fillMaxWidth(), enabled = enabled,
    )
    if (!draft.showIcons) Text(stringResource(R.string.navigation_text_only_hint), style = MaterialTheme.typography.bodySmall)
    val shapes = listOf(R.string.navigation_default, R.string.navigation_round, R.string.navigation_soft, R.string.navigation_square)
    NavigationChoice(R.string.navigation_container_shape, shapes, draft.containerShape.ordinal, enabled) { onDraftChange(draft.copy(containerShape = NavigationShape.entries[it])) }
    NavigationChoice(R.string.navigation_indicator_shape, shapes, draft.indicatorShape.ordinal, enabled) { onDraftChange(draft.copy(indicatorShape = NavigationShape.entries[it])) }
    NavigationSlider(R.string.navigation_spacing, draft.spacing, 0..16, enabled) { onDraftChange(draft.copy(spacing = it)) }
    NavigationSlider(R.string.navigation_padding, draft.contentPadding, 0..24, enabled) { onDraftChange(draft.copy(contentPadding = it)) }
    NavigationSlider(R.string.navigation_margin, draft.floatingMargin, 0..32, enabled && draft.placement == NavigationPlacement.FLOATING) { onDraftChange(draft.copy(floatingMargin = it)) }
    NavigationSlider(R.string.navigation_elevation, draft.elevation, 0..16, enabled && draft.placement == NavigationPlacement.FLOATING) { onDraftChange(draft.copy(elevation = it)) }
}

/** Presents a labeled single-choice group that scrolls horizontally when its options do not fit. */
@Composable
private fun NavigationChoice(@StringRes title: Int, options: List<Int>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val groupLabel = stringResource(title)
    Column(Modifier.semantics { contentDescription = groupLabel }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(groupLabel, style = MaterialTheme.typography.labelLarge)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val availableWidth = maxWidth
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ConnectedButtonGroup(
                    items = options.map { ConnectedButtonGroupItem.Label(stringResource(it)) },
                    selectedIndex = selected, onItemSelected = onSelect, enabled = enabled,
                    shapes = ConnectedButtonGroupShapes.FixedConnected,
                    modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = availableWidth),
                )
            }
        }
    }
}

/** Edits whole-dp values within an inclusive range and displays the localized current value. */
@Composable
private fun NavigationSlider(@StringRes label: Int, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    AsgardLabeledSlider(
        label = stringResource(label), value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
        valueLabel = stringResource(R.string.navigation_dp, value), enabled = enabled,
    )
}
