package com.valhalla.superuser.internal

import android.os.IBinder
import android.os.RemoteException
import com.valhalla.superuser.internal.UiThreadHandler

internal abstract class BinderHolder @Throws(RemoteException::class) constructor(
    private val binder: IBinder
) : IBinder.DeathRecipient {

    init {
        binder.linkToDeath(this, 0)
    }

    override fun binderDied() {
        binder.unlinkToDeath(this, 0)
        UiThreadHandler.run { onBinderDied() }
    }

    protected abstract fun onBinderDied()
}
