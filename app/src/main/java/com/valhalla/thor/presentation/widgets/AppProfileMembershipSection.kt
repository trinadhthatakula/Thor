// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.FreezeProfile

/** Informational membership: wrapping names remain readable without offering profile actions. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun AppProfileMembershipSection(
    profiles: List<FreezeProfile>,
    isInFreezer: Boolean,
    modifier: Modifier = Modifier,
) {
    if (profiles.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.app_info_profiles_count, profiles.size),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(
                if (isInFreezer) R.string.app_info_freezer_and_profiles
                else R.string.app_info_profiles_only,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A large collection cannot push every action/tab off-screen. Text wraps without ellipsis,
        // and this bounded area remains scrollable for large font sizes or many memberships.
        FlowRow(
            modifier = Modifier.fillMaxWidth().heightIn(max = 128.dp)
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            profiles.forEach { profile ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = profile.name,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
