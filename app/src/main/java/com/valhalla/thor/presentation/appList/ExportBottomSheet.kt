package com.valhalla.thor.presentation.appList

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import com.valhalla.thor.presentation.utils.AppIconModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Destination picker + explainer for exporting an installed app's bundle. Self-contained
 * (hosts its own SAF picker and Koin dependencies); shown from the App Info surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(appInfo: AppInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exportUseCase = koinInject<ExportAppUseCase>()
    val preferenceRepository = koinInject<PreferenceRepository>()
    val scope = rememberCoroutineScope()

    val isSplit = appInfo.splitPublicSourceDirs.isNotEmpty()

    var targetLabel by remember { mutableStateOf(context.getString(R.string.export_dest_downloads)) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { targetLabel = exportUseCase.currentTargetLabel() }

    val runExport = {
        exporting = true
        scope.launch {
            val result = exportUseCase(appInfo)
            exporting = false
            result
                .onSuccess {
                    Toast.makeText(
                        context,
                        context.getString(R.string.export_saved, it),
                        Toast.LENGTH_LONG
                    ).show()
                    onDismiss()
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.export_failed,
                            it.message ?: context.getString(R.string.export_failed_unknown)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    // On API <= 28, writing to the public Downloads directory needs WRITE_EXTERNAL_STORAGE
    // granted at runtime. Run the export regardless of the grant result: SAF export still
    // works when denied, and the export itself surfaces success/failure.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { runExport() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            scope.launch {
                preferenceRepository.setExportDirUri(uri.toString())
                targetLabel = exportUseCase.currentTargetLabel()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section header
            Text(
                text = stringResource(R.string.action_export).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            // App identity card + format badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = AppIconModel(appInfo.packageName),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appInfo.appName ?: appInfo.packageName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = appInfo.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (isSplit) ".apks" else ".apk",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            // Plain-language explanation (adapts to the export format)
            Text(
                text = stringResource(
                    if (isSplit) R.string.export_explain_apks else R.string.export_explain_apk
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Destination
            Text(
                text = stringResource(R.string.export_save_to).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                    .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.storage),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = targetLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(
                    onClick = { picker.launch(null) },
                    enabled = !exporting
                ) {
                    Icon(
                        painter = painterResource(R.drawable.open_in),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.export_change))
                }
            }

            // Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !exporting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    enabled = !exporting,
                    onClick = {
                        // A custom SAF folder writes via DocumentFile and needs no
                        // WRITE_EXTERNAL_STORAGE — only the legacy Downloads path (API <= 28) does.
                        val usingCustomFolder =
                            targetLabel != context.getString(R.string.export_dest_downloads)
                        if (!usingCustomFolder &&
                            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            runExport()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    if (exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_in_progress))
                    } else {
                        Text(stringResource(R.string.action_export))
                    }
                }
            }
        }
    }
}
