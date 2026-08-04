// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.valhalla.thor.R
import com.valhalla.thor.util.AppLocale

/**
 * Fired by the system (via the [android.content.IntentSender] passed to
 * `ShortcutManagerCompat.requestPinShortcut`) ONLY when a shortcut is successfully pinned to the
 * launcher. Android provides no cancel/failure callback, so this confirms success only.
 *
 * The `context` is built by the framework, not handed over by Thor: `ActivityThread.handleReceiver`
 * derives it from the Application's **base** context, so it is neither the wrapped
 * `ThorApplication` nor reached by that class's `getResources()` override, and on API 28–32 it
 * resolves `shortcut_added` in the device's language rather than the app's. [AppLocale.wrap] is
 * applied here for the same reason every other Thor component applies it in `attachBaseContext`; a
 * `BroadcastReceiver` simply has no such hook to put it in.
 */
class FreezerShortcutPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val localised = AppLocale.wrap(context)
        val label = intent.getStringExtra(EXTRA_LABEL)
        val message = if (!label.isNullOrEmpty()) {
            localised.getString(R.string.shortcut_added_named, label)
        } else {
            localised.getString(R.string.shortcut_added)
        }
        // The Toast still goes through the application context: a receiver context is dead as soon
        // as onReceive returns, and the wrapped one above exists only to resolve the string.
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_LABEL = "com.valhalla.thor.extra.SHORTCUT_LABEL"
    }
}
