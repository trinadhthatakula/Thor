package com.valhalla.thor.presentation.appList

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ExportAppUseCase
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportBottomSheet(appInfo: AppInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exportUseCase = koinInject<ExportAppUseCase>()
    val preferenceRepository = koinInject<PreferenceRepository>()
    val scope = rememberCoroutineScope()

    var targetLabel by remember { mutableStateOf("Downloads/Thor") }
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(
                    R.string.export_title,
                    appInfo.appName ?: appInfo.packageName
                )
            )
            Text(stringResource(R.string.export_destination, targetLabel))
            TextButton(onClick = { picker.launch(null) }) {
                Text(stringResource(R.string.export_change_folder))
            }
            Button(
                enabled = !exporting,
                onClick = {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        runExport()
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (exporting) R.string.export_in_progress else R.string.action_export
                    )
                )
            }
        }
    }
}
