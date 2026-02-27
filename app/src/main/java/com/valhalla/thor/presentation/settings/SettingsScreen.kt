package com.valhalla.thor.presentation.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.ThemeMode
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroup
import com.valhalla.thor.presentation.common.components.ConnectedButtonGroupItem
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinViewModel()
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        }.getOrDefault("—")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {

        SettingsHeader()

        // ── APPEARANCE ──────────────────────────────────────────────────────
        SettingsSectionLabel("Appearance")

        SettingsRow(
            icon = R.drawable.shield,
            title = "Theme",
            subtitle = "Choose your preferred colour scheme"
        ) {
            ConnectedButtonGroup(
                items = ThemeMode.entries.map { ConnectedButtonGroupItem.Label(it.label()) },
                selectedIndex = ThemeMode.entries.indexOf(prefs.themeMode),
                onItemSelected = { viewModel.setThemeMode(ThemeMode.entries[it]) }
            )
        }

        SettingsDivider()

        SettingsSwitchRow(
            icon = R.drawable.shield_with_heart,
            title = "Dynamic Colour",
            subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                "Adapt the palette to your wallpaper"
            else
                "Requires Android 12 or above",
            checked = prefs.useDynamicColor,
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            onCheckedChange = { viewModel.setDynamicColor(it) }
        )

        // ── SECURITY ────────────────────────────────────────────────────────
        SettingsSectionLabel("Security")

        when {
            viewModel.canUseBiometric -> {
                SettingsSwitchRow(
                    icon = R.drawable.round_key,
                    title = "Biometric Lock",
                    subtitle = "Require biometric or device credential on launch",
                    checked = prefs.biometricLockEnabled,
                    onCheckedChange = { viewModel.setBiometricLock(it) }
                )
            }
            viewModel.hasBiometricHardware -> {
                SettingsSwitchRow(
                    icon = R.drawable.round_key,
                    title = "Biometric Lock",
                    subtitle = "No biometric or screen lock enrolled",
                    checked = false,
                    enabled = false,
                    onCheckedChange = {}
                )
                EnrollBiometricRow(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(
                                Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                                    putExtra(
                                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                                or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                    )
                                }
                            )
                        } else {
                            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                        }
                    }
                )
            }
            else -> Unit
        }

        // ── ABOUT ───────────────────────────────────────────────────────────
        SettingsSectionLabel("About")

        SettingsInfoRow(
            icon = R.drawable.thor_mono,
            title = "Version",
            value = versionName
        )

        SettingsDivider()

        SettingsLinkRow(
            icon = R.drawable.brand_github,
            title = "Source Code",
            subtitle = "github.com/trinadhthatakula/Thor",
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW,
                        "https://github.com/trinadhthatakula/Thor".toUri())
                )
            }
        )

        SettingsDivider()

        SettingsLinkRow(
            icon = R.drawable.brand_telegram,
            title = "Telegram Channel",
            subtitle = "Get updates and release notes",
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, "https://t.me/thorAppDev".toUri())
                )
            }
        )

        Spacer(Modifier.height(32.dp))
    }
}

// ─── Section chrome ───────────────────────────────────────────────────────────

@Composable
private fun SettingsHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.settings),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

// ─── Row building blocks ─────────────────────────────────────────────────────

/**
 * Base row: icon + title/subtitle on the left, arbitrary [content] on the right.
 */
@Composable
private fun SettingsRow(
    icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        content()
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
    SettingsRow(icon = icon, title = title, subtitle = subtitle) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsInfoRow(
    icon: Int,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsLinkRow(
    icon: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onClick) {
            Icon(
                painter = painterResource(R.drawable.open_in_new),
                contentDescription = "Open",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun EnrollBiometricRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        TextButton(onClick = onClick) {
            Text(
                text = "Set up in device settings →",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
