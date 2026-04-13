package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroup
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroupItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardHeader(
    isRoot: Boolean,
    isShizuku: Boolean,
    isDhizuku: Boolean,
    activeMode: PrivilegeMode?,
    selectedType: AppListType,
    onTypeChanged: (AppListType) -> Unit,
    onPrivilegeChanged: (PrivilegeMode) -> Unit,
    onRestrictedStatusClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // LEFT: Brand Block
        // ... (existing code)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.thor_mono),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = (-1).sp
            )
        }

        // RIGHT: Controls (Status + Type Switcher)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Work Mode Icon/Selector
            StatusIcon(
                isRoot = isRoot,
                isShizuku = isShizuku,
                isDhizuku = isDhizuku,
                activeMode = activeMode,
                onModeSelected = onPrivilegeChanged,
                onClick = onRestrictedStatusClick
            )

            // App Type Switcher
            ConnectedButtonGroup(
                items = AppListType.entries.map { type ->
                    ConnectedButtonGroupItem.Icon(
                        iconRes = if (type == AppListType.USER) R.drawable.apps else R.drawable.android,
                        contentDescription = type.name
                    )
                },
                selectedIndex = AppListType.entries.indexOf(selectedType),
                onItemSelected = { onTypeChanged(AppListType.entries[it]) }
            )
        }
    }
}

@Composable
private fun StatusIcon(
    isRoot: Boolean,
    isShizuku: Boolean,
    isDhizuku: Boolean,
    activeMode: PrivilegeMode?,
    onModeSelected: (PrivilegeMode) -> Unit,
    onClick: () -> Unit
) {
    val availableModes = buildList {
        if (isRoot) add(PrivilegeMode.ROOT)
        if (isShizuku) add(PrivilegeMode.SHIZUKU)
        if (isDhizuku) add(PrivilegeMode.DHIZUKU)
    }

    val (icon, color) = when (activeMode) {
        PrivilegeMode.ROOT -> R.drawable.magisk_icon to MaterialTheme.colorScheme.primary
        PrivilegeMode.SHIZUKU -> R.drawable.shizuku to MaterialTheme.colorScheme.primary
        PrivilegeMode.DHIZUKU -> R.drawable.dhizuku to MaterialTheme.colorScheme.primary
        else -> R.drawable.round_close to MaterialTheme.colorScheme.error
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable {
                if (availableModes.size > 1) {
                    // Cycle through available modes
                    val currentIndex = availableModes.indexOf(activeMode)
                    val nextIndex = (currentIndex + 1) % availableModes.size
                    onModeSelected(availableModes[nextIndex])
                } else if (availableModes.isEmpty()) {
                    onClick()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(R.string.privilege_check),
            modifier = Modifier.size(20.dp),
            tint = color
        )
    }
}

