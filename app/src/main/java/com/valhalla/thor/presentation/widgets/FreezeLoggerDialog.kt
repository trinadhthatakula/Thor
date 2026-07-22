package com.valhalla.thor.presentation.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.valhalla.thor.R
import kotlinx.coroutines.delay

/**
 * Compact, count-only progress for bulk freeze / unfreeze. Shows a live
 * `processed / total` count while running and a one-line summary when done — it
 * never lists app names. On a fully-successful run it auto-dismisses after a short
 * delay; if any app failed it stays open with a Close button so the result is read.
 */
@Composable
fun FreezeLoggerDialog(
    isFreeze: Boolean,
    total: Int,
    processed: Int,
    failed: Int,
    isComplete: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMillis: Long = 2000L
) {
    val succeeded = processed - failed
    val hasFailures = failed > 0

    // Keyed on completion state (NOT onDismiss, which would reset the delay each
    // recomposition); rememberUpdatedState keeps the latest onDismiss without restarting.
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    // Auto-dismiss shortly after a fully-successful run.
    LaunchedEffect(isComplete, hasFailures) {
        if (isComplete && !hasFailures) {
            delay(autoDismissMillis)
            currentOnDismiss()
        }
    }

    Dialog(
        onDismissRequest = { if (isComplete) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = isComplete,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 220.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    !isComplete -> AnimateLottieRaw(
                        resId = R.raw.rearrange,
                        shouldLoop = true,
                        modifier = Modifier.size(56.dp),
                        contentScale = ContentScale.Crop
                    )

                    hasFailures -> Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )

                    else -> Icon(
                        painter = painterResource(R.drawable.check_circle),
                        contentDescription = stringResource(R.string.cd_selected),
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (!isComplete) {
                    Text(
                        text = stringResource(
                            if (isFreeze) R.string.log_freezing_batch else R.string.log_unfreezing_batch
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$processed / $total",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = if (hasFailures) {
                            stringResource(
                                if (isFreeze) R.string.tile_freeze_partial_failure
                                else R.string.tile_unfreeze_partial_failure,
                                succeeded, total, failed
                            )
                        } else {
                            pluralStringResource(
                                if (isFreeze) R.plurals.tile_freeze_success
                                else R.plurals.unfrozen_count_success,
                                succeeded,
                                succeeded
                            )
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (hasFailures) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
    }
}
