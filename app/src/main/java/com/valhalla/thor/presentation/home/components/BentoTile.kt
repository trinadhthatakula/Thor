// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R

/**
 * Compact, size-adaptive action tile for the Home bento grid. Renders correctly both at
 * Modifier.weight(1f) (half-width, paired) and fillMaxWidth() (full-width, solo). Icon chip on
 * top, then title + subtitle. Uniform min height so paired tiles align. Mirrors the color logic
 * of the former ActionCard (isPrimary/isWarning/neutral).
 *
 * When [showSubtitle] is false the tile is *compact*: the subtitle is dropped from the layout and
 * the title is allowed to wrap to two lines instead. A half-width tile has no room for both, and
 * clipping the title is worse than hiding a description the long-press sheet can show in full.
 * The subtitle stays in the accessibility tree either way — see the Spacer below.
 */
@Composable
fun BentoTile(
    title: String,
    subtitle: String,
    icon: Int,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    isWarning: Boolean = false,
    showSubtitle: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClose: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val containerColor = when {
        isPrimary -> MaterialTheme.colorScheme.primaryContainer
        isWarning -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        isPrimary -> MaterialTheme.colorScheme.onPrimaryContainer
        isWarning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(containerColor)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .defaultMinSize(minHeight = 112.dp)
            .padding(18.dp)
    ) {
        if (onClose != null) {
            // Not an IconButton: its own clickable would consume the down event before the tile's
            // long-press timer could start, leaving a 48 dp corner where holding dismisses the
            // card instead of opening the sheet. Carrying the same onLongClick here keeps the
            // gesture uniform across the whole tile.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .combinedClickable(
                        role = Role.Button,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick,
                        onClick = onClose,
                    )
                    .size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.round_close),
                    contentDescription = stringResource(R.string.dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (isPrimary) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
                    )
                    .padding(12.dp)
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (isPrimary) MaterialTheme.colorScheme.primaryContainer else contentColor
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = contentColor,
                    maxLines = if (showSubtitle) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (showSubtitle) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isPrimary) contentColor.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // The tile root merges its descendants, so deleting the subtitle Text would
                    // also delete the string from what TalkBack reads out. A zero-size node right
                    // after the title keeps both the content and its reading order.
                    Spacer(Modifier.semantics { contentDescription = subtitle })
                }
            }
        }
    }
}
