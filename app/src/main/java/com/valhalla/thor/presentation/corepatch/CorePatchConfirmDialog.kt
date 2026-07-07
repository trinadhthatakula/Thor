package com.valhalla.thor.presentation.corepatch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.presentation.security.BiometricPromptHandler

/**
 * Per-operation confirm for a CorePatch (Xposed signature-bypass) install (Task 11).
 *
 * Deliberately blunt: it puts the installed vs incoming SHA-256 signer **side by side** (highlighted
 * when they differ), spells out the capability being granted and whether this is a downgrade, and
 * gates the affirmative action behind a device biometric when [biometricLockEnabled].
 *
 * On confirm it hands back the user's Play-Protect toggle; the caller (InstallerViewModel) arms
 * [com.valhalla.thor.domain.model.CorePatchAuthorization] from [state] verbatim — the shown package
 * is the armed package, never re-derived.
 */
@Composable
fun CorePatchConfirmDialog(
    state: CorePatchConfirmState,
    biometricLockEnabled: Boolean,
    onConfirm: (disablePlayProtect: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val biometricHandler = remember(context) { BiometricPromptHandler(context) }
    val biometricTitle = stringResource(R.string.core_patch_confirm_biometric_title)
    val biometricSubtitle = stringResource(R.string.core_patch_confirm_biometric_subtitle)

    // Bypassing signature checks almost always needs Play Protect off to succeed, so it defaults on;
    // the user can still uncheck it to keep the verifier engaged for this install.
    var disablePlayProtect by remember { mutableStateOf(true) }

    val signersDiffer = state.capability.equals("sig", ignoreCase = true)

    fun affirm() {
        if (biometricLockEnabled) {
            biometricHandler.authenticate(
                title = biometricTitle,
                subtitle = biometricSubtitle,
                onAuthenticated = { onConfirm(disablePlayProtect) },
                onError = { /* leave the dialog up; the user can retry or cancel */ }
            )
        } else {
            onConfirm(disablePlayProtect)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(stringResource(R.string.core_patch_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = state.pkg,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                LabeledValue(
                    label = stringResource(R.string.core_patch_confirm_capability_label),
                    value = when {
                        signersDiffer -> stringResource(R.string.core_patch_confirm_cap_sig)
                        else -> stringResource(R.string.core_patch_confirm_cap_digest)
                    }
                )

                // Installed vs new signer, side by side. Highlighted (error) when they differ.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SignerColumn(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.core_patch_confirm_installed_signer),
                        sha = state.installedSignerSha256
                            ?: stringResource(R.string.core_patch_confirm_signer_none),
                        highlight = signersDiffer
                    )
                    SignerColumn(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.core_patch_confirm_new_signer),
                        sha = state.newSignerSha256,
                        highlight = signersDiffer
                    )
                }

                Text(
                    text = if (signersDiffer)
                        stringResource(R.string.core_patch_confirm_signers_differ)
                    else stringResource(R.string.core_patch_confirm_signers_match),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (signersDiffer) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )

                LabeledValue(
                    label = stringResource(R.string.core_patch_confirm_downgrade_label),
                    value = if (state.isDowngrade) stringResource(R.string.yes)
                    else stringResource(R.string.no),
                    valueColor = if (state.isDowngrade) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.core_patch_confirm_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { disablePlayProtect = !disablePlayProtect }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Checkbox(
                        checked = disablePlayProtect,
                        onCheckedChange = { disablePlayProtect = it }
                    )
                    Text(
                        text = stringResource(R.string.core_patch_confirm_disable_play_protect),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { affirm() }) {
                Text(
                    text = stringResource(R.string.core_patch_confirm_install),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun LabeledValue(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun SignerColumn(
    modifier: Modifier = Modifier,
    label: String,
    sha: String,
    highlight: Boolean,
) {
    val border = if (highlight) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (highlight) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = sha,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (highlight) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
    }
}
