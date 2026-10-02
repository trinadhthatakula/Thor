// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import android.annotation.SuppressLint
import android.content.pm.IPackageDataObserver
import android.os.Binder
import android.os.IBinder

/** Resolve and allocate the real observer before the ledger marks the invocation boundary. */
// Called only in Odin's unspecialized root process, with the same hidden API contract as ThorRootService.
@SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
internal fun prepareAndroidRootDataClear(
    packageName: String,
    userId: Int,
    callback: RootDataClearCallback,
): PreparedRootDataClear {
    val binder = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java).invoke(null, "activity") as IBinder
    val am = Class.forName("android.app.IActivityManager\$Stub")
        .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    val method = Class.forName("android.app.IActivityManager").getDeclaredMethod(
        "clearApplicationUserData",
        String::class.java,
        Boolean::class.javaPrimitiveType,
        Class.forName("android.content.pm.IPackageDataObserver"),
        Int::class.javaPrimitiveType,
    )
    val observer = object : IPackageDataObserver.Stub() {
        override fun onRemoveCompleted(reportedPackage: String?, succeeded: Boolean) {
            callback.onCompleted(reportedPackage, succeeded, Binder.getCallingUid())
        }
    }
    return PreparedRootDataClear {
        // keepState=false matches Android's ordinary user-requested clear. An exception after
        // entering this invocation is uncertain, even if an observer callback also arrived.
        method.invoke(am, packageName, false, observer, userId) as Boolean
    }
}
