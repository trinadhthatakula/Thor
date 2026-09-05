// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.valhalla.thor.ThorApplication
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/** Cancels only the durable privilege request named by the immutable notification action. */
class PrivilegeSweepCancelReceiver : BroadcastReceiver(), KoinComponent {
    private val cancellation: PrivilegeSweepCancellationCoordinator by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("io"))

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_PRIVILEGE_SWEEP) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        val pending = goAsync()
        val application = context.applicationContext as ThorApplication
        application.launchInApplicationScope(ioDispatcher) {
            try {
                cancellation.cancel(requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.e(TAG, "privilege sweep cancellation failed", failure)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_PRIVILEGE_SWEEP =
            "com.valhalla.thor.action.CANCEL_PRIVILEGE_SWEEP"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val TAG = "PrivilegeSweepCancelReceiver"

        fun intent(context: Context, requestId: UUID): Intent =
            Intent(context, PrivilegeSweepCancelReceiver::class.java)
                .setAction(ACTION_CANCEL_PRIVILEGE_SWEEP)
                .putExtra(EXTRA_REQUEST_ID, requestId.toString())
    }
}
