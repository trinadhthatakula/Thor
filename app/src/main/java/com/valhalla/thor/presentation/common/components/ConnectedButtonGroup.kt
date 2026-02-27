package com.valhalla.thor.presentation.common.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.animateWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/**
 * A single-select Connected Button Group built on top of [ButtonGroup] + [ToggleButton].
 *
 * Shape logic, press animation, and overflow menu wiring are handled internally.
 * Callers only describe *what* each button shows via [ConnectedButtonGroupItem] and
 * respond to selection changes.
 *
 * The expressive press animation (buttons physically expand and compress their
 * neighbours) is activated automatically via [Modifier.animateWidth].
 *
 * ---
 *
 * **Icon-only** (DashboardHeader, AppListScreen):
 * ```kotlin
 * ConnectedButtonGroup(
 *     items = AppListType.entries.map { type ->
 *         ConnectedButtonGroupItem.Icon(
 *             iconRes = if (type == AppListType.USER) R.drawable.apps else R.drawable.android,
 *             contentDescription = type.name
 *         )
 *     },
 *     selectedIndex = AppListType.entries.indexOf(selectedType),
 *     onItemSelected = { onTypeChanged(AppListType.entries[it]) }
 * )
 * ```
 *
 * **Text labels** (SettingsScreen ThemeMode, AppFilterSheet tabs):
 * ```kotlin
 * ConnectedButtonGroup(
 *     items = ThemeMode.entries.map { ConnectedButtonGroupItem.Label(it.label()) },
 *     selectedIndex = ThemeMode.entries.indexOf(prefs.themeMode),
 *     onItemSelected = { viewModel.setThemeMode(ThemeMode.entries[it]) }
 * )
 * ```
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectedButtonGroup(
    items: List<ConnectedButtonGroupItem>,
    selectedIndex: Int,
    onItemSelected: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    require(items.isNotEmpty()) { "ConnectedButtonGroup requires at least one item" }

    val lastIndex = items.lastIndex

    androidx.compose.material3.ButtonGroup(
        overflowIndicator = {},
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        items.forEachIndexed { index, item ->
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    ToggleButton(
                        checked = index == selectedIndex,
                        onCheckedChange = { checked -> if (checked) onItemSelected(index) },
                        modifier = Modifier.animateWidth(interactionSource),
                        shapes = connectedShapesFor(index, lastIndex),
                        interactionSource = interactionSource,
                    ) {
                        ItemContent(item)
                    }
                },
                menuContent = { menuState ->
                    // Overflow fallback — never visible for fixed-count groups,
                    // but required by the ButtonGroup API contract.
                    DropdownMenuItem(
                        text = { Text(item.menuLabel) },
                        leadingIcon = item.menuIcon?.let { res ->
                            { Icon(painterResource(res), contentDescription = null) }
                        },
                        onClick = {
                            onItemSelected(index)
                            menuState.dismiss()
                        }
                    )
                }
            )
        }
    }
}

// ─── Item descriptor ──────────────────────────────────────────────────────────

/**
 * Sealed hierarchy that describes the visual content of a single button.
 * Keeps the reusable composable generic without requiring caller-side lambdas.
 */
sealed interface ConnectedButtonGroupItem {

    /** Label shown in the overflow [DropdownMenuItem]. */
    val menuLabel: String

    /** Optional icon shown in the overflow [DropdownMenuItem]. */
    val menuIcon: Int? get() = null

    // ── Concrete variants ─────────────────────────────────────────────────────

    /** Button shows only an icon (e.g. User / System app-type switcher). */
    data class Icon(
        val iconRes: Int,
        val contentDescription: String,
    ) : ConnectedButtonGroupItem {
        override val menuLabel: String get() = contentDescription
        override val menuIcon: Int get() = iconRes
    }

    /** Button shows only a text label (e.g. ThemeMode picker, tab switcher). */
    data class Label(val text: String) : ConnectedButtonGroupItem {
        override val menuLabel: String get() = text
    }

    /** Button shows an icon followed by a text label. */
    data class IconWithLabel(
        val iconRes: Int,
        val contentDescription: String,
        val text: String,
    ) : ConnectedButtonGroupItem {
        override val menuLabel: String get() = text
        override val menuIcon: Int get() = iconRes
    }
}

// ─── Private helpers ──────────────────────────────────────────────────────────

/** Renders the correct inner content for each [ConnectedButtonGroupItem] variant. */
@Composable
private fun ItemContent(item: ConnectedButtonGroupItem) {
    when (item) {
        is ConnectedButtonGroupItem.Icon ->
            Icon(
                painter = painterResource(item.iconRes),
                contentDescription = item.contentDescription
            )

        is ConnectedButtonGroupItem.Label ->
            Text(item.text, maxLines = 1)

        is ConnectedButtonGroupItem.IconWithLabel ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing)
            ) {
                Icon(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.contentDescription
                )
                Text(item.text, maxLines = 1)
            }
    }
}

/**
 * Maps a button's position within the group to the correct [ToggleButtonShapes],
 * following the Connected Button Group spec:
 *
 * - `index == 0`             → pill-left, small inner-right  *(leading)*
 * - `0 < index < lastIndex`  → small corners on all sides    *(middle)*
 * - `index == lastIndex`     → small inner-left, pill-right  *(trailing)*
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun connectedShapesFor(index: Int, lastIndex: Int): ToggleButtonShapes = when {
    index == 0                   -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    index == lastIndex           -> ButtonGroupDefaults.connectedTrailingButtonShapes()
    else                         -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}
