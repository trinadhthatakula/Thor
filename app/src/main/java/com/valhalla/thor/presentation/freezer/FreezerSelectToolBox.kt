package com.valhalla.thor.presentation.freezer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.MultiAppAction

@Composable
fun FreezerSelectToolBox(
    modifier: Modifier = Modifier,
    selected: List<AppInfo>,
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    isDhizuku: Boolean = false,
    onCancel: () -> Unit = {},
    onRemoveFromFreezer: () -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {}
) {
    var hasFrozen by remember { mutableStateOf(selected.any { !it.enabled }) }
    var hasUnFrozen by remember { mutableStateOf(selected.any { it.enabled }) }

    LaunchedEffect(selected) {
        hasFrozen = selected.any { !it.enabled }
        hasUnFrozen = selected.any { it.enabled }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FreezerToolItem(icon = R.drawable.round_close, label = "Close", onClick = onCancel)

            if (isRoot || isShizuku || isDhizuku) {
                if (hasUnFrozen) {
                    FreezerToolItem(
                        icon = R.drawable.frozen,
                        label = "Freeze",
                        onClick = { onMultiAppAction(MultiAppAction.Freeze(selected)) }
                    )
                }
                if (hasFrozen) {
                    FreezerToolItem(
                        icon = R.drawable.unfreeze,
                        label = "Unfreeze",
                        onClick = { onMultiAppAction(MultiAppAction.UnFreeze(selected)) }
                    )
                }
            }

            FreezerToolItem(
                icon = R.drawable.delete,
                label = "Remove",
                onClick = onRemoveFromFreezer
            )

            FreezerToolItem(
                icon = R.drawable.share,
                label = "Share",
                onClick = { onMultiAppAction(MultiAppAction.Share(selected)) }
            )

            FreezerToolItem(
                icon = R.drawable.delete_forever,
                label = "Uninstall",
                onClick = { onMultiAppAction(MultiAppAction.Uninstall(selected)) }
            )
        }
    }
}

@Composable
private fun FreezerToolItem(icon: Int, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}
