package com.valhalla.thor.presentation.corepatch

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valhalla.thor.R
import com.valhalla.thor.data.corepatch.CorePatchAuditEntry
import com.valhalla.thor.presentation.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only viewer for the CorePatch audit trail (spec §6.3 — "viewable/exportable").
 *
 * Entries are shown newest-first via [SettingsViewModel.corePatchAuditEntries]. The export action
 * shares the whole log as plain text through [Intent.ACTION_SEND]. This screen surfaces existing
 * state only — no new capabilities (YAGNI); the kill-switch lives in the Settings danger zone.
 */
@Composable
fun CorePatchAuditScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val context = LocalContext.current

    // The audit is an in-memory ring buffer with no reactive stream; a snapshot on entry is enough
    // for a viewer (new entries only appear after an install flow, which leaves this screen).
    val entries = remember { viewModel.corePatchAuditEntries() }

    val timeFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    val exportSubject = stringResource(R.string.core_patch_audit_export_subject)
    val exportEmpty = stringResource(R.string.core_patch_audit_empty)

    Scaffold(
        topBar = {
            CorePatchAuditTopAppBar(
                onBack = onBack,
                exportEnabled = entries.isNotEmpty(),
                onExport = {
                    val text = buildExportText(entries, timeFormatter, exportSubject, exportEmpty)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, exportSubject)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, exportSubject))
                }
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        if (entries.isEmpty()) {
            EmptyAuditState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 24.dp,
                    vertical = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries) { entry ->
                    AuditEntryCard(entry = entry, formattedTime = timeFormatter.format(Date(entry.timestampMillis)))
                }
            }
        }
    }
}

@Composable
private fun CorePatchAuditTopAppBar(
    onBack: () -> Unit,
    exportEnabled: Boolean,
    onExport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.round_close),
                contentDescription = stringResource(R.string.cd_close),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.core_patch_audit_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onExport, enabled = exportEnabled) {
            Icon(
                painter = painterResource(R.drawable.share),
                contentDescription = stringResource(R.string.core_patch_audit_export),
                tint = if (exportEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
        }
    }
}

@Composable
private fun AuditEntryCard(
    entry: CorePatchAuditEntry,
    formattedTime: String
) {
    val signerNone = stringResource(R.string.core_patch_confirm_signer_none)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = entry.pkg,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = entry.result,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        AuditDetailRow(
            label = stringResource(R.string.core_patch_confirm_capability_label),
            value = entry.capability
        )
        AuditDetailRow(
            label = stringResource(R.string.core_patch_audit_signer_label),
            value = stringResource(
                R.string.core_patch_audit_signer_flow,
                entry.oldSigner ?: signerNone,
                entry.newSigner
            )
        )
        if (entry.downgrade) {
            AuditDetailRow(
                label = stringResource(R.string.core_patch_confirm_downgrade_label),
                value = stringResource(R.string.core_patch_audit_downgrade_yes)
            )
        }
        AuditDetailRow(
            label = stringResource(R.string.core_patch_audit_time_label),
            value = formattedTime
        )
    }
}

@Composable
private fun AuditDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
private fun EmptyAuditState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.list),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.core_patch_audit_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Renders the whole audit trail as plain text for [Intent.ACTION_SEND]. One block per entry,
 * newest first (the list is already sorted). Kept side-effect-free so it's trivially testable.
 */
private fun buildExportText(
    entries: List<CorePatchAuditEntry>,
    formatter: SimpleDateFormat,
    header: String,
    emptyText: String
): String {
    if (entries.isEmpty()) return emptyText
    return buildString {
        appendLine(header)
        appendLine()
        entries.forEach { e ->
            appendLine("• ${e.pkg}")
            appendLine("  time:       ${formatter.format(Date(e.timestampMillis))}")
            appendLine("  capability: ${e.capability}")
            appendLine("  signer:     ${e.oldSigner ?: "—"} -> ${e.newSigner}")
            appendLine("  downgrade:  ${e.downgrade}")
            appendLine("  result:     ${e.result}")
            appendLine()
        }
    }.trimEnd()
}
