// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.valhalla.thor.R
import com.valhalla.thor.data.backup.job.ThorJobNotifications
import com.valhalla.thor.domain.model.PrivilegeSweepOperation
import com.valhalla.thor.domain.model.PrivilegeSweepSource
import com.valhalla.thor.domain.model.SWEEP_REQUEST_ID_KEY
import com.valhalla.thor.domain.model.THOR_SWEEP_CHAIN
import com.valhalla.thor.domain.model.ThorJobKind
import com.valhalla.thor.domain.model.ThorJobProgress
import com.valhalla.thor.domain.model.ThorJobStage
import com.valhalla.thor.domain.repository.NewPrivilegeSweepSnapshot
import com.valhalla.thor.domain.repository.PrivilegeSweepStore
import com.valhalla.thor.domain.repository.StoredSweepTerminal
import com.valhalla.thor.domain.repository.SweepCreateResult
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class SweepQueueCancelReceiverTest {

    @Suppress("DEPRECATION")
    @Test
    fun sweepNotificationActionCancelsDurableQueueAndReceiverIsNotExported() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, SweepQueueCancelReceiver::class.java)
        val receiverInfo = context.packageManager.getReceiverInfo(
            component,
            PackageManager.GET_META_DATA,
        )
        assertFalse(receiverInfo.exported)

        val store = GlobalContext.get().get<PrivilegeSweepStore>()
        val gate = GlobalContext.get().get<PrivilegeSweepProcessGate>()
        val workManager = WorkManager.getInstance(context)
        val requestId = UUID.randomUUID()
        val target = "test.${requestId.toString().replace('-', '.')}"
        val work = OneTimeWorkRequestBuilder<PrivilegeSweepWorker>()
            .setId(UUID.randomUUID())
            .setInputData(workDataOf(SWEEP_REQUEST_ID_KEY to requestId.toString()))
            .setInitialDelay(Duration.ofDays(1))
            .build()

        try {
            val created = gate.serialized {
                val result = store.createOrFindEquivalent(
                    NewPrivilegeSweepSnapshot(
                        requestId = requestId,
                        workId = work.id,
                        operation = PrivilegeSweepOperation.CLEAR_CACHE,
                        freezerMode = null,
                        userId = 0,
                        source = PrivilegeSweepSource.SETTINGS,
                        createdAtEpochMs = System.currentTimeMillis(),
                        targets = listOf(target),
                    )
                )
                withContext(Dispatchers.IO) {
                    workManager.beginUniqueWork(
                        THOR_SWEEP_CHAIN,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        work,
                    ).enqueue().result.get()
                }
                result
            }
            assertTrue(created is SweepCreateResult.Created)

            val notification = ThorJobNotifications(context).foregroundInfo(
                kind = ThorJobKind.PRIVILEGE_SWEEP,
                progress = ThorJobProgress(ThorJobStage.ACTING, "Applying app actions"),
                jobId = work.id,
            ).notification
            val action = notification.actions.single()
            assertEquals(context.getString(R.string.cancel_sweep_queue), action.title.toString())
            action.actionIntent.send()

            val cancelled = withTimeout(10.seconds) {
                store.observe(requestId)
                    .filterNotNull()
                    .first { it.terminalState == StoredSweepTerminal.CANCELLED }
            }
            assertEquals(1, cancelled.unresolved)

            val cancelledWork = withTimeout(10.seconds) {
                workManager.getWorkInfoByIdFlow(work.id)
                    .filterNotNull()
                    .first { it.state == WorkInfo.State.CANCELLED }
            }
            assertEquals(WorkInfo.State.CANCELLED, cancelledWork.state)

            // Synchronize with receiver completion before cleanup. The JVM Operation test separately
            // proves that this gate cannot be released while WorkManager's future is still pending.
            gate.serialized { }
        } finally {
            gate.serialized {
                withContext(Dispatchers.IO) {
                    workManager.cancelWorkById(work.id).result.get()
                }
                store.delete(requestId)
            }
        }
    }
}
