// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later
package com.valhalla.thor.presentation.installer

import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.core.content.IntentCompat

/** Home picks and external documents share the same retained installer Activity and routing. */
internal fun createPortableInstallerIntent(context: Context, uri: Uri): Intent =
    Intent(context, PortableInstallerActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = uri
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/** Accept one shared document or local file; intent extras never select slots or dispatch. */
internal fun portableInstallerUri(intent: Intent): Uri? = runCatching {
    val uri = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> {
            val clip = intent.clipData
            if (clip != null && clip.itemCount != 1) return null
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: clip?.getItemAt(0)?.uri
        }
        else -> null
    }
    uri?.takeIf { it.scheme == "content" || it.scheme == "file" }
}.getOrNull()
