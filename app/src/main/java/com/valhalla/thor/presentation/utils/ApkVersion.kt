// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.utils

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.valhalla.thor.R
import com.valhalla.thor.data.repository.baseApkSha256
import com.valhalla.thor.domain.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A flung picker can compose several APKs at once.
private val visibleApkIo = Dispatchers.IO.limitedParallelism(2)

// Drop the previous value immediately when the selection or installed APK changes.
@Composable
internal fun rememberBaseApkSha256(app: AppInfo): String? =
    key(app.packageName, app.sourceDir, app.lastUpdateTime, app.versionCode) {
        produceState<String?>(null) {
            value = withContext(visibleApkIo) { baseApkSha256(app.sourceDir) }
        }.value
    }

internal fun String.withApkFingerprint(sha256: String?): String =
    "$this [${sha256.orEmpty().take(8)}]"

internal fun apkVersion(name: String?, code: Long?): String =
    listOfNotNull(name?.takeIf { it.isNotBlank() }, code?.let { "($it)" }).joinToString(" ")

/** Tap a version in a package preview to copy the full base APK checksum. */
@Composable
internal fun ApkVersionText(
    version: String,
    sha256: String?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.info_base_apk_sha256)
    val copyLabel = stringResource(R.string.cd_copy_field, label)
    Text(
        text = version.withApkFingerprint(sha256),
        style = style,
        color = color,
        fontWeight = fontWeight,
        textDecoration = if (sha256 == null) null else TextDecoration.Underline,
        modifier = if (sha256 == null) modifier else modifier.clickable(onClickLabel = copyLabel) {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, sha256)))
                Toast.makeText(context, R.string.toast_copy_saved, Toast.LENGTH_SHORT).show()
            }
        },
    )
}
