// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.valhalla.thor.ThorApplication
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/** Receives the notification action that cancels every request on the durable sweep chain. */
class SweepQueueCancelReceiver : BroadcastReceiver(), KoinComponent {
    private val canceller: SweepQueueCanceller by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("io"))

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_SWEEP_QUEUE) return

        val application = context.applicationContext as ThorApplication
        val pendingResult = goAsync()
        application.launchInApplicationScope(ioDispatcher) {
            try {
                canceller.cancelQueue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "sweep queue cancellation failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_SWEEP_QUEUE =
            "com.valhalla.thor.action.CANCEL_SWEEP_QUEUE"

        private const val TAG = "SweepQueueCancelReceiver"
    }
}
