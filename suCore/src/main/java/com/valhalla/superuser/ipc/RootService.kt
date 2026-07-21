package com.valhalla.superuser.ipc

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.MainThread
import com.valhalla.superuser.internal.RootServiceManager
import com.valhalla.superuser.internal.RootServiceServer
import com.valhalla.superuser.internal.UiThreadHandler
import com.valhalla.superuser.internal.Utils
import com.valhalla.superuser.Shell
import java.io.IOException
import java.util.concurrent.Executor

abstract class RootService : ContextWrapper(null) {

    companion object {
        const val CATEGORY_DAEMON_MODE = "com.valhalla.superuser.DAEMON_MODE"

        @MainThread
        @JvmStatic
        fun bind(intent: Intent, executor: Executor, conn: ServiceConnection) {
            if (Utils.isRootImpossible()) return
            val task = bindOrTask(intent, executor, conn)
            if (task != null) {
                Shell.EXECUTOR.execute(asRunnable(task))
            }
        }

        @MainThread
        @JvmStatic
        fun bind(intent: Intent, conn: ServiceConnection) {
            bind(intent, UiThreadHandler.executor, conn)
        }

        @MainThread
        @JvmStatic
        fun bindOrTask(intent: Intent, executor: Executor, conn: ServiceConnection): Shell.Task? {
            return RootServiceManager.getInstance().createBindTask(intent, executor, conn)
        }

        @MainThread
        @JvmStatic
        fun unbind(conn: ServiceConnection) {
            RootServiceManager.getInstance().unbind(conn)
        }

        @MainThread
        @JvmStatic
        fun stop(intent: Intent) {
            if (Utils.isRootImpossible()) return
            val task = stopOrTask(intent)
            if (task != null) {
                Shell.EXECUTOR.execute(asRunnable(task))
            }
        }

        @MainThread
        @JvmStatic
        fun stopOrTask(intent: Intent): Shell.Task? {
            return RootServiceManager.getInstance().createStopTask(intent)
        }

        private fun asRunnable(task: Shell.Task): Runnable {
            return Runnable {
                runCatching {
                    val shell = Shell.shell
                    if (shell.isRoot) {
                        shell.execTask(task)
                    }
                }.onFailure { e ->
                    if (e is IOException) {
                        Utils.err(e)
                    } else {
                        throw RuntimeException(e)
                    }
                }
            }
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(onAttach(Utils.getContextImpl(base)))
        RootServiceServer.getInstance(base).register(this)
        onCreate()
    }

    protected open fun onAttach(base: Context): Context {
        return base
    }

    open fun getComponentName(): ComponentName {
        return ComponentName(this, javaClass)
    }

    override fun getApplicationContext(): Context? {
        return Utils.context
    }

    abstract fun onBind(intent: Intent): IBinder?

    open fun onCreate() {}

    open fun onUnbind(intent: Intent): Boolean {
        return false
    }

    open fun onRebind(intent: Intent) {}

    open fun onDestroy() {}

    fun stopSelf() {
        RootServiceServer.getInstance(this).selfStop(getComponentName())
    }

    /**
     * Enforce that the current Binder transaction comes from an authorized caller: root (0),
     * the system server (1000), or the UID that started this RootService. Call from inside your
     * AIDL stub methods. Throws [SecurityException] otherwise.
     */
    protected fun enforceCaller() {
        val callingUid = android.os.Binder.getCallingUid()
        val authorizedUid =
            com.valhalla.superuser.internal.RootServiceServer.getInstanceOrNull()?.authorizedUid ?: -1
        if (callingUid != 0 && callingUid != 1000 && callingUid != authorizedUid) {
            throw SecurityException("Access denied: UID $callingUid is not authorized.")
        }
    }
}
