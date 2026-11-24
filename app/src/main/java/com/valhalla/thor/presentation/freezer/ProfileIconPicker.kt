// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FreezeProfile
import com.valhalla.thor.domain.model.FreezeProfileIcon

@Composable
internal fun ProfileIconTile(icon: FreezeProfileIcon, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(icon.drawable),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(30)).background(colorResource(icon.background)).padding(5.dp),
    )
}

@Composable
internal fun ProfileIconPicker(
    selected: FreezeProfileIcon,
    onSelect: (FreezeProfileIcon) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.profile_icon), style = MaterialTheme.typography.titleSmall)
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            val labelStyle = MaterialTheme.typography.labelSmall
            val labels = FreezeProfileIcon.entries.map { stringResource(it.label) }
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val cellWidth = remember(labels, labelStyle, measurer, density) {
                val widest = labels.maxOf { measurer.measure(it, style = labelStyle, softWrap = false).size.width }
                with(density) { (widest.toDp() + 12.dp).coerceAtLeast(76.dp) }
            }
            val gap = 6.dp
            val columns = ((maxWidth + gap) / (cellWidth + gap)).toInt().coerceIn(1, 4)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FreezeProfileIcon.entries.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { icon ->
                            ProfileIconOption(
                                icon = icon,
                                selected = icon == selected,
                                enabled = enabled,
                                onClick = { onSelect(icon) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileIconOption(
    icon: FreezeProfileIcon,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val border = if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant)
    Box(
        modifier.clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
            .border(border, shape)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 76.dp)
            .padding(4.dp),
    ) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProfileIconTile(icon, Modifier.size(36.dp))
            Text(stringResource(icon.label), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).size(13.dp),
                tint = colors.primary,
            )
        }
    }
}

@Composable
internal fun ProfileShortcutIconDialog(
    profile: FreezeProfile,
    alreadyPinned: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (FreezeProfileIcon) -> Unit,
) {
    var selected by rememberSaveable(profile.id, profile.icon) { mutableStateOf(profile.icon) }
    val updating = alreadyPinned && selected != profile.icon
    val action = stringResource(if (updating) R.string.profile_update_shortcut else R.string.add_to_home_screen)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileIconTile(selected, Modifier.size(48.dp))
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                }
                ProfileIconPicker(selected, onSelect = { selected = it })
                Text(stringResource(if (updating) R.string.profile_update_icon_hint else R.string.profile_pin_icon_hint),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = profile.size > 0) {
                Text(action)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
