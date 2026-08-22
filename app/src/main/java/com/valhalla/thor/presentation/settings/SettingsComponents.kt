// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.R

/**
 * The row vocabulary shared by the settings index and the eight category screens.
 *
 * Pulled out of `SettingsScreen` when that screen split in two, so the index and a category cannot
 * drift into two slightly different-looking rows. Everything here is `internal`: these are Thor's
 * settings rows, not a general component set — Asgard owns that role, and its `AsgardSettingRow`
 * does not forward `subtitleMaxLines` to the `AsgardListRow` underneath it, which is the one thing
 * these rows exist to get right.
 *
 * ## Two lines of subtitle, and no marquee
 *
 * Every subtitle here is `maxLines = 2`. The previous switch row was `maxLines = 1` with an opt-in
 * `basicMarquee` that started on a tap *on the subtitle text* — a nested clickable inside a row
 * whose whole surface already toggles the switch, so the gesture that revealed the description was
 * a tap that looked exactly like the one that changed the setting. Nine call sites opted in; the
 * other rows just truncated. Two lines fit every description Thor ships, in all five locales, so
 * the scrolling text had nothing left to reveal.
 */

/** The tinted circle that carries a row's glyph. */
@Composable
internal fun SettingsIconBox(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    /** Error colours, for a row that *does* something destructive rather than storing a preference. */
    destructive: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (destructive) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (destructive) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * The container colour every row draws on, lifted while the row is the one search sent you to.
 *
 * Animated rather than switched, because the highlight is released a couple of seconds after
 * arrival (see `SettingsCategoryScreen`) and a colour that simply vanishes reads as a glitch.
 */
@Composable
private fun rowContainerColor(highlighted: Boolean) = animateColorAsState(
    targetValue = if (highlighted) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceContainerLow,
    label = "settingsRowContainer"
).value

/** A row that opens something, or does something. */
@Composable
internal fun SettingsClickRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    destructive: Boolean = false,
    /** The `›` affordance. On for a row that navigates, off for one that acts in place. */
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(rowContainerColor(highlighted))
            .clickable(enabled = enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsIconBox(icon, destructive = destructive)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            RowTitle(title)
            RowSubtitle(subtitle)
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.chevron_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A row that stores a boolean. */
@Composable
internal fun SettingsSwitchRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(rowContainerColor(highlighted))
            // `toggleable`, not `clickable`: the row is the whole control, so it has to carry the
            // control's semantics. Under `clickable` it announced as a button with no state at all
            // — the Switch beside it had its own semantics cleared to stop the setting being
            // offered twice, so between them nothing said on or off, and a screen reader user could
            // change the setting but never hear what it was set to.
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            SettingsIconBox(icon)
            Spacer(Modifier.width(16.dp))
            Column {
                RowTitle(title)
                RowSubtitle(subtitle)
            }
        }
        Spacer(Modifier.width(8.dp))
        // The row is the tap target; the Switch is a picture of the state. A null handler is what
        // says so — Material3 only applies its own toggleable semantics when one is present, so
        // this contributes no second announcement of the same setting and needs no
        // `clearAndSetSemantics` to suppress one. It also stops consuming the pointer, which is
        // what lets a tap landing on the thumb reach the row's toggleable above.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
    }
}

/**
 * A row whose value is a short closed set: title and description above, a segmented control below.
 *
 * The shape Theme and Grid Density already had, extracted so that the five places using it stop
 * being five hand-written copies — one of which had drifted into duplicating its own section header
 * as the header *above* itself.
 */
@Composable
internal fun SettingsPickerRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    items: List<ConnectedButtonGroupItem>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(rowContainerColor(highlighted))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIconBox(icon)
            Spacer(Modifier.width(16.dp))
            Column {
                RowTitle(title)
                RowSubtitle(subtitle)
            }
        }
        Spacer(Modifier.height(16.dp))
        ConnectedButtonGroup(
            items = items,
            selectedIndex = selectedIndex,
            onItemSelected = onItemSelected,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** One line, always. A wrapped title stops the row scanning as a list. */
@Composable
private fun RowTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Two lines. See this file's header for why that number and not one. */
@Composable
private fun RowSubtitle(subtitle: String) {
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * A category screen's top bar.
 *
 * The settings root has none — it carries a display-size "Settings" heading instead — so this is
 * the first back affordance in the section. An arrow rather than the `round_close` the extension
 * screens use: those are modal-feeling destinations reached from one place, a category is a step
 * into a hierarchy you walk back out of.
 */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.arrow_back),
                contentDescription = stringResource(R.string.cd_back),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
