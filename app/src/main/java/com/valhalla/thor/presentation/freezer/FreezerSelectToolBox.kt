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
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.MultiAppAction
import com.valhalla.thor.domain.model.isActive
import com.valhalla.thor.domain.model.isFrozen

@Composable
fun FreezerSelectToolBox(
    modifier: Modifier = Modifier,
    selected: List<AppInfo>,
    isRoot: Boolean = false,
    isShizuku: Boolean = false,
    isDhizuku: Boolean = false,
    onCancel: () -> Unit = {},
    onRemoveFromFreezer: () -> Unit = {},
    onMultiAppAction: (MultiAppAction) -> Unit = {},
    freezerMode: FreezerMode = FreezerMode.FREEZE
) {
    // "Frozen" = disabled OR suspended; "active" = freezable (enabled & not suspended). GH#239.
    val hasFrozen = remember(selected) { selected.any { it.isFrozen } }
    val hasUnFrozen = remember(selected) { selected.any { it.isActive } }

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
            FreezerToolItem(icon = R.drawable.round_close, label = stringResource(R.string.close), onClick = onCancel)

            if (isRoot || isShizuku || isDhizuku) {
                if (hasUnFrozen) {
                    FreezerToolItem(
                        icon = R.drawable.frozen,
                        label = stringResource(R.string.action_freeze),
                        onClick = {
                            onMultiAppAction(
                                MultiAppAction.Freeze(selected, useSuspend = freezerMode == FreezerMode.SUSPEND)
                            )
                        }
                    )
                }
                if (hasFrozen) {
                    FreezerToolItem(
                        icon = R.drawable.unfreeze,
                        label = stringResource(R.string.action_unfreeze),
                        onClick = { onMultiAppAction(MultiAppAction.UnFreeze(selected)) }
                    )
                }
            }

            FreezerToolItem(
                icon = R.drawable.delete,
                label = stringResource(R.string.action_remove),
                onClick = onRemoveFromFreezer
            )

            FreezerToolItem(
                icon = R.drawable.share,
                label = stringResource(R.string.action_share),
                onClick = { onMultiAppAction(MultiAppAction.Share(selected)) }
            )

            FreezerToolItem(
                icon = R.drawable.delete_forever,
                label = stringResource(R.string.action_uninstall),
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
            .width(72.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
