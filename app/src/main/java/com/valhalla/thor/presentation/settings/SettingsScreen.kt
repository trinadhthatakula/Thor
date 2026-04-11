package com.valhalla.thor.presentation.settings

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.PrivilegeMode
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroup
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroupItem
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val context = LocalContext.current

    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        }.getOrDefault("—")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 64.dp, bottom = 120.dp)
    ) {

        // Header Section
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp
        )
        Text(
            text = "Configuration Engine • v$versionName",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(48.dp))

        // ── APPEARANCE ──────────────────────────────────────────────────────
        SettingsSectionLabel("APPEARANCE")
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Theme Row
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(R.drawable.theme_panel)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Visual interface style", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                ConnectedButtonGroup(
                    items = ThemeMode.entries.map { ConnectedButtonGroupItem.Label(it.label()) },
                    selectedIndex = ThemeMode.entries.indexOf(prefs.themeMode),
                    onItemSelected = { viewModel.setThemeMode(ThemeMode.entries[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsSwitchRow(
                icon = R.drawable.theme_panel,
                title = "AMOLED Mode",
                subtitle = "Pure black background",
                checked = prefs.useAmoled,
                onCheckedChange = { viewModel.setAmoledMode(it) }
            )

            SettingsSwitchRow(
                icon = R.drawable.shield_with_heart,
                title = "Dynamic Colors",
                subtitle = "Material You integration",
                checked = prefs.useDynamicColor,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onCheckedChange = { viewModel.setDynamicColor(it) }
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── SECURITY ────────────────────────────────────────────────────────
        SettingsSectionLabel("SECURITY")
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(8.dp)
        ) {
            SettingsSwitchRow(
                icon = R.drawable.round_key,
                title = "Biometric Lock",
                subtitle = "Require auth on launch",
                checked = prefs.biometricLockEnabled,
                onCheckedChange = { viewModel.setBiometricLock(it) }
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── WORK MODE ───────────────────────────────────────────────────────
        val availableModes = buildList {
            if (state.isRootAvailable) add(PrivilegeMode.ROOT)
            if (state.isShizukuAvailable) add(PrivilegeMode.SHIZUKU)
            if (state.isDhizukuAvailable) add(PrivilegeMode.DHIZUKU)
        }

        if (availableModes.size > 1) {
            SettingsSectionLabel("WORK MODE")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val activeMode = prefs.preferredPrivilegeMode ?: availableModes.first()
                    val icon = when (activeMode) {
                        PrivilegeMode.ROOT -> R.drawable.magisk_icon
                        PrivilegeMode.SHIZUKU -> R.drawable.shizuku
                        PrivilegeMode.DHIZUKU -> R.drawable.dhizuku
                    }
                    IconBox(icon)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Active Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Switch between available providers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                ConnectedButtonGroup(
                    items = availableModes.map { mode ->
                        ConnectedButtonGroupItem.Label(mode.name)
                    },
                    selectedIndex = availableModes.indexOf(prefs.preferredPrivilegeMode ?: availableModes.first()),
                    onItemSelected = { viewModel.setPrivilegeMode(availableModes[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        // ── ABOUT ───────────────────────────────────────────────────────────
        SettingsSectionLabel("ABOUT")
        
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Version Tile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBox(R.drawable.thor_mono)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Release candidate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(versionName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AboutTile(
                    title = "GitHub",
                    subtitle = "SOURCE CODE",
                    icon = R.drawable.brand_github,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/trinadhthatakula/Thor".toUri()))
                    }
                )
                AboutTile(
                    title = "Telegram",
                    subtitle = "COMMUNITY",
                    icon = R.drawable.brand_telegram,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://t.me/thorAppDev".toUri()))
                    }
                )
            }
        }

        // Technical Stats Footer
        Spacer(Modifier.height(48.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Text("KERNEL_STATUS: OPTIMIZED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "BUILT WITH PRECISION FOR POWER USERS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                letterSpacing = 4.sp
            )
        }
        
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
        letterSpacing = 2.sp
    )
}

@Composable
private fun IconBox(icon: Int) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            IconBox(icon)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun AboutTile(
    title: String,
    subtitle: String,
    icon: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { onClick() }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
        }
    }
}
