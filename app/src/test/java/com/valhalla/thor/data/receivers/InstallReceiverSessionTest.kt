// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.receivers

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import com.valhalla.thor.data.ACTION_INSTALL_STATUS
import com.valhalla.thor.data.manager.PendingInstallIntent
import com.valhalla.thor.domain.InstallSessionCompletion
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.InstallerEventBus
import com.valhalla.thor.util.UiText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class InstallReceiverSessionTest {
    @Test
    fun `success callback settles its own session`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)

        emitInstallSessionStatus(callback(completion, PackageInstaller.STATUS_SUCCESS), bus, PendingInstallIntent())

        assertSame(InstallState.Success, completion.await())
        assertSame(InstallState.Success, bus.latest)
    }

    @Test
    fun `every documented platform failure is terminal including platform timeout`() = runTest {
        val bus = InstallerEventBus()
        for (status in 1..8) {
            val completion = bus.registerSession(41)
            val intent = callback(completion, status).putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "Rejected")

            emitInstallSessionStatus(intent, bus, PendingInstallIntent())

            assertEquals(
                InstallState.Error(UiText.DynamicString("Install Failed ($status): Rejected")),
                completion.await(),
            )
        }
    }

    @Test
    fun `user confirmation publishes its intent without settling the session`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)
        val pending = PendingInstallIntent()
        val confirm = Intent("test.confirm")
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        try {
            emitInstallSessionStatus(
                callback(completion, PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, confirm),
                bus, pending,
            )
            runCurrent()
            assertFalse(waiter.isCompleted)
            assertSame(InstallState.UserConfirmationRequired, bus.latest)
            assertEquals(confirm.action, pending.consume()?.action)
            assertNull(pending.consume())

            emitInstallSessionStatus(callback(completion, PackageInstaller.STATUS_SUCCESS), bus, pending)
            assertSame(InstallState.Success, waiter.await())
        } finally {
            waiter.cancelAndJoin()
            bus.unregisterSession(completion)
        }
    }

    @Test
    @Config(sdk = [28])
    fun `legacy parcelable confirmation keeps the same pending behavior`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)
        val pending = PendingInstallIntent()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        try {
            emitInstallSessionStatus(
                callback(completion, PackageInstaller.STATUS_PENDING_USER_ACTION)
                    .putExtra(Intent.EXTRA_INTENT, Intent("test.confirm.legacy")),
                bus, pending,
            )
            runCurrent()
            assertFalse(waiter.isCompleted)
            assertEquals("test.confirm.legacy", pending.consume()?.action)
        } finally {
            waiter.cancelAndJoin()
            bus.unregisterSession(completion)
        }
    }

    @Test
    fun `missing confirmation streaming and unknown statuses cannot settle ownership`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)
        val pending = PendingInstallIntent()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        try {
            for (status in listOf(PackageInstaller.STATUS_PENDING_USER_ACTION, -2, -100, 9, Int.MAX_VALUE)) {
                emitInstallSessionStatus(callback(completion, status), bus, pending)
                runCurrent()
                assertFalse("Status $status cannot prove termination", waiter.isCompleted)
            }
            val missingStatus = callback(completion, PackageInstaller.STATUS_SUCCESS).apply {
                removeExtra(PackageInstaller.EXTRA_STATUS)
            }
            emitInstallSessionStatus(missingStatus, bus, pending)
            emitInstallSessionStatus(
                callback(completion, 0).putExtra(PackageInstaller.EXTRA_STATUS, "0"), bus, pending,
            )
            runCurrent()
            assertFalse(waiter.isCompleted)
            assertNull(pending.consume())
        } finally {
            waiter.cancelAndJoin()
            bus.unregisterSession(completion)
        }
    }

    @Test
    fun `terminal callbacks with missing or mismatched correlation only update presentation`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        try {
            val invalid = listOf(
                callback(completion, 0).apply { removeExtra(PackageInstaller.EXTRA_SESSION_ID) },
                callback(completion, 0).apply { removeExtra(InstallReceiver.EXTRA_INSTALL_TOKEN) },
                callback(completion, 0).putExtra(PackageInstaller.EXTRA_SESSION_ID, 42),
                callback(completion, 0).putExtra(InstallReceiver.EXTRA_INSTALL_TOKEN, "unrelated"),
                callback(completion, 0).putExtra(InstallReceiver.EXTRA_INSTALL_TOKEN, 42),
            )
            for (intent in invalid) {
                emitInstallSessionStatus(intent, bus, PendingInstallIntent())
                runCurrent()
                assertFalse(waiter.isCompleted)
                assertSame(InstallState.Success, bus.latest)
            }
        } finally {
            waiter.cancelAndJoin()
            bus.unregisterSession(completion)
        }
    }

    @Test
    fun `unrelated broadcast action cannot settle or publish a session result`() = runTest {
        val bus = InstallerEventBus()
        val completion = bus.registerSession(41)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { completion.await() }
        try {
            emitInstallSessionStatus(
                callback(completion, 0).setAction("test.unrelated"), bus, PendingInstallIntent(),
            )
            runCurrent()
            assertFalse(waiter.isCompleted)
            assertNull(bus.latest)
        } finally {
            waiter.cancelAndJoin()
            bus.unregisterSession(completion)
        }
    }

    private fun callback(completion: InstallSessionCompletion, status: Int) = Intent(ACTION_INSTALL_STATUS)
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, completion.sessionId)
        .putExtra(InstallReceiver.EXTRA_INSTALL_TOKEN, completion.token)
        .putExtra(PackageInstaller.EXTRA_STATUS, status)
}
