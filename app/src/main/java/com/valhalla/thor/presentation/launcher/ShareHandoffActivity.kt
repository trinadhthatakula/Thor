// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.valhalla.thor.presentation.share.ReadyShareAccessLock
import com.valhalla.thor.presentation.share.ShareIntentFactory
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named

private const val TAG = "ShareHandoffActivity"
private const val REFUSAL_LOG = "prepared share handoff refused"

/** Revalidates durable ready outputs and launches their chooser without exposing file paths. */
@SuppressLint("CustomSplashScreen")
class ShareHandoffActivity : Activity() {
    private val accessLock: ReadyShareAccessLock by inject()
    private val intentFactory: ShareIntentFactory by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("io"))
    private val mainDispatcher: CoroutineDispatcher by inject(named("main"))
    private var handoffScope: CoroutineScope? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        handoffScope = scope
        scope.launch {
            try {
                val taskId = canonicalTaskId(intent?.getStringExtra(EXTRA_TASK_ID))
                if (taskId == null) {
                    Logger.e(TAG, REFUSAL_LOG)
                    return@launch
                }
                accessLock.withLock {
                    val nowMs = System.currentTimeMillis()
                    val shareIntent = withContext(ioDispatcher) {
                        intentFactory.create(taskId, nowMs)
                    }
                    if (shareIntent == null) {
                        Logger.e(TAG, REFUSAL_LOG)
                    } else {
                        startActivity(Intent.createChooser(shareIntent, null))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Logger.e(TAG, REFUSAL_LOG)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        handoffScope?.cancel()
        handoffScope = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TASK_ID = TaskQueueLaunchActivity.EXTRA_TASK_ID

        /** The task UUID is the handoff's only caller-controlled extra. */
        fun intent(context: Context, taskId: java.util.UUID): Intent =
            Intent(context, ShareHandoffActivity::class.java)
                .putExtra(EXTRA_TASK_ID, taskId.toString())
    }
}
