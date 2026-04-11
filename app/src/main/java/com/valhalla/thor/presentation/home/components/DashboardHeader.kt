package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroup
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroupItem
import com.valhalla.thor.presentation.theme.greenDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardHeader(
    isRoot: Boolean,
    isShizuku: Boolean,
    selectedType: AppListType,
    onTypeChanged: (AppListType) -> Unit,
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
                text = "Thor",
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
            // Work Mode Icon
            StatusIcon(
                isRoot = isRoot,
                isShizuku = isShizuku,
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
    onClick: () -> Unit
) {
    val (icon, color) = when {
        isRoot -> R.drawable.magisk_icon to MaterialTheme.colorScheme.primary
        isShizuku -> R.drawable.shizuku to MaterialTheme.colorScheme.primary
        else -> R.drawable.round_close to MaterialTheme.colorScheme.error
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = "Status",
            modifier = Modifier.size(20.dp),
            tint = color
        )
    }
}

