// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.valhalla.thor.R

/**
 * Fired by the system (via the [android.content.IntentSender] passed to
 * `ShortcutManagerCompat.requestPinShortcut`) ONLY when a shortcut is successfully pinned to the
 * launcher. Android provides no cancel/failure callback, so this confirms success only.
 */
class FreezerShortcutPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL)
        val message = if (!label.isNullOrEmpty()) {
            context.getString(R.string.shortcut_added_named, label)
        } else {
            context.getString(R.string.shortcut_added)
        }
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_LABEL = "com.valhalla.thor.extra.SHORTCUT_LABEL"
    }
}
