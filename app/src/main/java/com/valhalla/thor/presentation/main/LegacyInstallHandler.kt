// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.main

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.util.UiText
import com.valhalla.thor.util.UiTextException

@Suppress("DEPRECATION")
// App installation is Thor's core function, and this intent only follows an explicit user action.
@SuppressLint("RequestInstallPackagesPolicy")
internal fun legacyFixStoreIntent(context: Context, uri: String): Intent {
    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        setDataAndType(uri.toUri(), "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_RETURN_RESULT, true)
        putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
    }
    val installer = context.packageManager.queryIntentActivities(
        intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY,
    ).firstOrNull { it.activityInfo.packageName != context.packageName }?.activityInfo
        ?: throw UiTextException(UiText.StringResource(R.string.legacy_fix_store_no_installer))
    intent.component = ComponentName(installer.packageName, installer.name)
    return intent
}

@Composable
fun LegacyInstallHandler(viewModel: MainViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val pending by viewModel.legacyInstallRequest.collectAsStateWithLifecycle()
    var launchedId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = launchedId
        launchedId = null
        if (id != null) {
            val outcome = when (result.resultCode) {
                Activity.RESULT_OK -> Result.success(true)
                Activity.RESULT_CANCELED -> Result.success(false)
                else -> Result.failure(UiTextException(UiText.StringResource(R.string.unknown_error_occurred)))
            }
            viewModel.onLegacyInstallResult(id, outcome)
        }
    }
    LaunchedEffect(pending?.id, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.onLegacyInstallHostResumed()
            val request = viewModel.legacyInstallRequest.value
            if (request != null && viewModel.claimLegacyInstallLaunch(request.id)) {
                launchedId = request.id
                try {
                    launcher.launch(legacyFixStoreIntent(context, request.uri))
                } catch (failure: Exception) {
                    launchedId = null
                    viewModel.onLegacyInstallResult(request.id, Result.failure(failure))
                }
            }
        }
    }
}
