// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.backup.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.valhalla.thor.ThorApplication
import com.valhalla.thor.data.backup.job.DataTaskCancellationCoordinator
import com.valhalla.thor.util.Logger
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/** Cancels one durable data task; it never cancels the retained WorkManager compatibility chain. */
class DataTaskCancelReceiver : BroadcastReceiver(), KoinComponent {
    private val cancellation: DataTaskCancellationCoordinator by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("io"))

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_DATA_TASK) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        val pendingResult = goAsync()
        val application = context.applicationContext as ThorApplication
        application.launchInApplicationScope(ioDispatcher) {
            try {
                cancellation.cancel(taskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.e(TAG, "data task cancellation failed", failure)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_DATA_TASK = "com.valhalla.thor.action.CANCEL_DATA_TASK"
        private const val EXTRA_TASK_ID = "task_id"
        private const val TAG = "DataTaskCancelReceiver"

        fun intent(context: Context, taskId: UUID): Intent =
            Intent(context, DataTaskCancelReceiver::class.java)
                .setAction(ACTION_CANCEL_DATA_TASK)
                .putExtra(EXTRA_TASK_ID, taskId.toString())
    }
}
