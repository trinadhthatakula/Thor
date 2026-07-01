package com.valhalla.thor.presentation.home.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppListType
import com.valhalla.thor.domain.model.PrivilegeMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.valhalla.asgard.components.ConnectedButtonGroup
import com.valhalla.asgard.components.ConnectedButtonGroupItem

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
    modifier: Modifier = Modifier,
    extensionsUnlocked: Boolean = false,
    onUnlockExtensions: () -> Unit = {},
    onShowSupport: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // LEFT: Brand Block (hosts the hidden "unlock extensions" easter egg)
        BrandBlock(
            extensionsUnlocked = extensionsUnlocked,
            onUnlockExtensions = onUnlockExtensions,
            onShowSupport = onShowSupport
        )

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
                        icon = ImageVector.vectorResource(if (type == AppListType.USER) R.drawable.apps else R.drawable.android),
                        contentDescription = stringResource(
                            if (type == AppListType.USER) R.string.chip_user else R.string.chip_system
                        )
                    )
                },
                selectedIndex = AppListType.entries.indexOf(selectedType),
                onItemSelected = { onTypeChanged(AppListType.entries[it]) },
                modifier = Modifier.width(IntrinsicSize.Max)
            )
        }
    }
}

/**
 * The Thor logo + title. Tapping it runs a hidden easter egg that, after [EASTER_EGG_TARGET] taps,
 * unlocks the (still-unstable) Extensions feature in Settings. Once unlocked it's a plain brand block.
 */
@Composable
private fun BrandBlock(
    extensionsUnlocked: Boolean,
    onUnlockExtensions: () -> Unit,
    onShowSupport: () -> Unit
) {
    val context = LocalContext.current
    val title = stringResource(R.string.app_name)
    var tapCount by remember { mutableIntStateOf(0) }
    val interactionSource = remember { MutableInteractionSource() }

    // Sideways "no" shake of the logo, played once extensions are already unlocked.
    val shakeOffset = remember { Animatable(0f) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            for (target in listOf(-8f, 8f, -6f, 6f, -3f, 3f, 0f)) {
                shakeOffset.animateTo(target, tween(50))
            }
        }
    }

    // Reset the tap counter if the user stops tapping for 5s. Keyed on tapCount, so each tap
    // cancels and restarts the timer; the title falls back to normal when it elapses.
    LaunchedEffect(tapCount) {
        if (tapCount > 0) {
            delay(EASTER_EGG_RESET_MS)
            tapCount = 0
        }
    }

    val onTap: () -> Unit = if (extensionsUnlocked) {
        {
            // Already unlocked: 1st tap shakes the logo "no", 2nd tap opens Support Developer.
            if (tapCount == 0) {
                tapCount = 1
                shakeTrigger++
            } else {
                tapCount = 0
                onShowSupport()
            }
        }
    } else {
        {
            val next = tapCount + 1
            if (next >= EASTER_EGG_TARGET) {
                tapCount = 0
                onUnlockExtensions()
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.extensions_unlocked),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                tapCount = next
            }
        }
    }

    Row(
        // No ripple/indication — keep the easter egg unobtrusive.
        modifier = Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onTap
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.thor_mono),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .offset(x = shakeOffset.value.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = if (extensionsUnlocked) title else easterEggText(tapCount, title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = (-1).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val EASTER_EGG_TARGET = 20
private const val EASTER_EGG_RESET_MS = 5_000L

// Escalating gag messages shown for tap counts 5..19 (count appended). Intentionally English-only.
private val EASTER_EGG_MESSAGES = listOf(
    "Stop it",   // 5
    "Quit it",   // 6
    "Ow",        // 7
    "Rude",      // 8
    "Why?",      // 9
    "Ugh",       // 10
    "Hey!",      // 11
    "Fine.",     // 12
    "Warmer",    // 13
    "Closer",    // 14
    "Almost",    // 15
    "Keep on",   // 16
    "So close",  // 17
    "Nearly",    // 18
    "One more",  // 19
)

private fun easterEggText(tapCount: Int, title: String): String = when {
    tapCount < 3 -> title
    tapCount == 3 -> "Hi"
    tapCount == 4 -> "Stop"
    tapCount in 5..(EASTER_EGG_TARGET - 1) ->
        "${EASTER_EGG_MESSAGES.getOrElse(tapCount - 5) { EASTER_EGG_MESSAGES.last() }} ($tapCount)"
    else -> title
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

