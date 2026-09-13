// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.valhalla.asgard.components.ConnectedButtonGroupItem
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FontPreset
import com.valhalla.thor.presentation.settings.SettingsPickerRow
import com.valhalla.thor.presentation.settings.labelRes

/** The selector and preview follow the saved preference and the app's live typography. */
@Composable
internal fun FontPresetPickerRow(
    selectedPreset: FontPreset,
    onPresetSelected: (FontPreset) -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    SettingsPickerRow(
        icon = R.drawable.font_style,
        title = stringResource(R.string.customization_fonts),
        subtitle = stringResource(R.string.customization_fonts_desc),
        items = FontPreset.entries.map { preset ->
            ConnectedButtonGroupItem.Label(stringResource(preset.labelRes))
        },
        selectedIndex = FontPreset.entries.indexOf(selectedPreset),
        onItemSelected = { onPresetSelected(FontPreset.entries[it]) },
        modifier = modifier,
        highlighted = highlighted,
        wrapText = true,
    ) {
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.font_preview_title),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.font_preview_heading),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.font_preview_body),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A label sample, not a pretend action: all three typography roles remain visible
            // without a button that invites a tap but cannot do anything.
            Text(
                text = stringResource(R.string.font_preview_numbers),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
