// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.valhalla.thor.R
import com.valhalla.thor.data.launcher.FreezerShortcutManager
import com.valhalla.thor.util.AppLocale
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicBoolean

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
class FreezerShortcutPinnedReceiver : BroadcastReceiver(), KoinComponent {
    private val shortcutManager: FreezerShortcutManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val localised = AppLocale.wrap(context)
        val label = intent.getStringExtra(EXTRA_LABEL)
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        // Shortcut-service IPC and the shared lock are blocking; cancelling their coroutine does
        // not interrupt them. Keep the broadcast deadline independent of reconciliation.
        val watchdog = CoroutineScope(Dispatchers.Default).launch {
            delay(8_000L)
            if (finished.compareAndSet(false, true)) {
                pendingResult.finish()
                Logger.w("FreezerShortcut", "Pinned shortcut reconciliation exceeded broadcast deadline")
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val allowed = shortcutManager.reconcilePinnedShortcutAfterPin()
                val message = if (!allowed) {
                    localised.getString(R.string.shortcuts_disabled_message)
                } else if (!label.isNullOrEmpty()) {
                    localised.getString(R.string.shortcut_added_named, label)
                } else {
                    localised.getString(R.string.shortcut_added)
                }
                // The Toast uses the application context; the wrapped one only resolves strings.
                withContext(Dispatchers.Main) {
                    if (!finished.get()) {
                        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("FreezerShortcut", "Pinned shortcut reconciliation failed", e)
            } finally {
                watchdog.cancel()
                if (finished.compareAndSet(false, true)) pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_LABEL = "com.valhalla.thor.extra.SHORTCUT_LABEL"
    }
}
