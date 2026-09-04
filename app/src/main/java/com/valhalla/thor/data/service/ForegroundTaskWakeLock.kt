// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import android.os.PowerManager

enum class ForegroundTaskOwner(val wakeLockTag: String) {
    DATA_SYNC("Thor:DataSync"),
    PRIVILEGE_SWEEP("Thor:PrivilegeSweep"),
}

/**
 * Owns the bounded partial wake-lock lease around one already-claimed foreground task.
 *
 * Call [withClaimedExecution] only after the queue claim commits and immediately before invoking the
 * runner. A caller may renew the lease only after its progress checkpoint has durably committed.
 */
class ForegroundTaskWakeLock internal constructor(
    owner: ForegroundTaskOwner,
    factory: ForegroundWakeLockFactory,
) {
    constructor(powerManager: PowerManager, owner: ForegroundTaskOwner) : this(
        owner = owner,
        factory = AndroidForegroundWakeLockFactory(powerManager),
    )

    private val wakeLock = factory.create(PowerManager.PARTIAL_WAKE_LOCK, owner.wakeLockTag).apply {
        setReferenceCounted(false)
    }

    suspend fun <T> withClaimedExecution(
        runner: suspend ForegroundTaskWakeLock.() -> T,
    ): T {
        check(!wakeLock.isHeld) { "A foreground task wake lock is already held" }
        return try {
            wakeLock.acquire(LEASE_MILLIS)
            runner()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    fun renewAfterPersistedCheckpoint() {
        check(wakeLock.isHeld) {
            "A wake-lock lease can renew only during active claimed execution"
        }
        // A timed, non-reference-counted WakeLock does not reset its existing timeout when acquired
        // again while held. Release that lease before starting the next verified ten-minute window.
        wakeLock.release()
        wakeLock.acquire(LEASE_MILLIS)
    }

    companion object {
        const val LEASE_MILLIS = 600_000L
    }
}

internal fun interface ForegroundWakeLockFactory {
    fun create(levelAndFlags: Int, tag: String): ForegroundWakeLock
}

internal interface ForegroundWakeLock {
    val isHeld: Boolean

    fun setReferenceCounted(value: Boolean)

    fun acquire(timeoutMillis: Long)

    fun release()
}

private class AndroidForegroundWakeLockFactory(
    private val powerManager: PowerManager,
) : ForegroundWakeLockFactory {
    override fun create(levelAndFlags: Int, tag: String): ForegroundWakeLock =
        AndroidForegroundWakeLock(powerManager.newWakeLock(levelAndFlags, tag))
}

private class AndroidForegroundWakeLock(
    private val wakeLock: PowerManager.WakeLock,
) : ForegroundWakeLock {
    override val isHeld: Boolean
        get() = wakeLock.isHeld

    override fun setReferenceCounted(value: Boolean) {
        wakeLock.setReferenceCounted(value)
    }

    override fun acquire(timeoutMillis: Long) {
        wakeLock.acquire(timeoutMillis)
    }

    override fun release() {
        wakeLock.release()
    }
}
