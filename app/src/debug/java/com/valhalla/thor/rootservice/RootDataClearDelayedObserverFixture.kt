// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.annotation.Keep
import com.valhalla.superuser.ipc.RootService
import com.valhalla.thor.BuildConfig
import java.util.concurrent.atomic.AtomicReference

/** Debug only. Holds delivery of a REAL Android verdict for one fixed disposable package. */
@Keep
class RootDataClearDelayedObserverFixture : RootService() {
    internal val held = AtomicReference<Runnable?>()
    internal val request = AtomicReference<String?>()
    internal val ledger = RootDataClearLedger(
        preparation = RootDataClearPreparation { target, user, callback ->
            prepareAndroidRootDataClear(target, user) { reported, succeeded, uid ->
                check(held.compareAndSet(null, Runnable { callback.onCompleted(reported, succeeded, uid) }))
            }
        },
        protectedPackageName = BuildConfig.APPLICATION_ID,
        observationTimeoutMillis = 1_000,
    )

    override fun onBind(intent: Intent): IBinder = object : IThorRootService.Stub() {
        override fun clearAppDataForUserWithResult(requestId: String, packageName: String, userId: Int): RootDataClearResult {
            enforceFixtureCaller(packageName)
            require(request.get() == requestId || request.compareAndSet(null, requestId))
            return ledger.clear(requestId, packageName, userId, Binder.getCallingUid()).toParcelable()
        }

        override fun getClearAppDataResult(requestId: String, packageName: String, userId: Int): RootDataClearResult {
            enforceFixtureCaller(packageName)
            return ledger.query(requestId, packageName, userId, Binder.getCallingUid()).toParcelable()
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != OBSERVED && code != RELEASE) return super.onTransact(code, data, reply, flags)
            this@RootDataClearDelayedObserverFixture.enforceCaller()
            data.enforceInterface(RootDataClearDelayedObserverFixture.DESCRIPTOR)
            val id = requireNotNull(data.readString())
            require(RootDataClearProtocol.isValidRequestId(id))
            val callback = if (id != request.get()) null else if (code == RELEASE) held.getAndSet(null) else held.get()
            if (code == RELEASE) callback?.run()
            if (code == RELEASE && callback != null) request.compareAndSet(id, null)
            requireNotNull(reply).apply { writeNoException(); writeInt(if (callback != null) 1 else 0) }
            return true
        }

        override fun setAppSuspended(packageName: String, suspended: Boolean): Boolean = unsupported()
        override fun clearAppData(packageName: String): Boolean = unsupported()
        override fun setAppSuspendedAs(packageName: String, suspended: Boolean, suspendingPackage: String?): Boolean = unsupported()
        override fun dumpPackage(packageName: String): String = unsupported()
        override fun clearAppDataForUser(packageName: String, userId: Int): Boolean = unsupported()
        override fun setAppSuspendedAsForUser(packageName: String, suspended: Boolean, suspendingPackage: String?, userId: Int): Boolean = unsupported()
        override fun getSuspensionStateForUser(packageName: String, userId: Int): SuspensionReadbackResult = unsupported()
    }

    internal fun enforceFixtureCaller(packageName: String) {
        enforceCaller()
        require(packageName == TARGET)
    }

    internal fun unsupported(): Nothing {
        enforceCaller()
        throw UnsupportedOperationException("This fixture only clears its disposable target")
    }

    companion object {
        const val TARGET = "com.valhalla.thor.audit.cleardata"
        // Extra fixture transactions share the descriptor of the AIDL Binder they extend.
        const val DESCRIPTOR = "com.valhalla.thor.rootservice.IThorRootService"
        const val OBSERVED = IBinder.FIRST_CALL_TRANSACTION + 100
        const val RELEASE = IBinder.FIRST_CALL_TRANSACTION + 101
    }
}
