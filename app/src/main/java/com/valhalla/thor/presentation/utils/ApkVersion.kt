// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.utils

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

internal fun shortApkFingerprint(sha256: String?): String = "[${sha256.orEmpty().take(8)}]"

internal fun apkVersionCode(code: Long?): String? = code?.let { "($it)" }

internal fun apkVersion(name: String?, code: Long?): String =
    listOfNotNull(name?.takeIf { it.isNotBlank() }, apkVersionCode(code)).joinToString(" ")

// Android can otherwise break inside a release suffix such as "preview" even when it fits on a fresh line.
private val versionSuffixBoundary = Regex("(?<=[_+-])(?=\\S)")

/** Preview versions can copy the full checksum. In lists the parent row handles taps. */
@Composable
internal fun ApkVersionText(
    version: String?,
    versionCode: String?,
    sha256: String?,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    copyChecksum: Boolean = false,
    centered: Boolean = false,
) {
    val checksumToCopy = sha256.takeIf { copyChecksum }
    val copyModifier = if (checksumToCopy != null) {
        val clipboard = LocalClipboard.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val label = stringResource(R.string.info_base_apk_sha256)
        Modifier.clickable(onClickLabel = stringResource(R.string.cd_copy_field, label)) {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, checksumToCopy)))
                Toast.makeText(context, R.string.toast_copy_saved, Toast.LENGTH_SHORT).show()
            }
        }
    } else Modifier
    val textStyle = if (checksumToCopy != null) {
        style.copy(textDecoration = TextDecoration.Underline)
    } else style
    val tokenSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize)
    val alignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
    Column(modifier.then(copyModifier), horizontalAlignment = alignment) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, alignment)) {
            if (!version.isNullOrBlank()) {
                Text(
                    text = version.replace(versionSuffixBoundary, "\u200B"),
                    style = textStyle.copy(lineBreak = LineBreak.Heading),
                    color = color,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                )
            }
            versionCode?.let {
                // Move the entire code to the next line first. Scale it only if it exceeds that line.
                Text(
                    text = it,
                    style = textStyle,
                    color = color,
                    maxLines = 1,
                    autoSize = tokenSize,
                )
            }
        }
        Text(
            text = shortApkFingerprint(sha256),
            style = textStyle.copy(textDirection = TextDirection.Ltr),
            color = color,
            maxLines = 1,
            autoSize = tokenSize,
        )
    }
}
