package com.valhalla.superuser.internal

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.ArrayMap
import android.util.Log
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ipc.RootService
import com.valhalla.superuser.internal.UiThreadHandler
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executor

internal class RootServiceManager private constructor() : Handler.Callback {

    companion object {
        const val TAG = "IPC"
        const val LOGGING_ENV = "LIBSU_VERBOSE_LOGGING"
        const val DEBUG_ENV = "LIBSU_DEBUGGER"
        const val MSG_STOP = 1

        private const val BUNDLE_BINDER_KEY = "binder"
        private const val INTENT_BUNDLE_KEY = "extra.bundle"
        private const val INTENT_DAEMON_KEY = "extra.daemon"
        private const val RECEIVER_BROADCAST = "com.valhalla.superuser.RECEIVER_BROADCAST"

        private const val API_27_DEBUG = "-Xrunjdwp:transport=dt_android_adb,suspend=n,server=y -Xcompiler-option --debuggable"
        private const val API_28_DEBUG = "-XjdwpProvider:adbconnection -XjdwpOptions:suspend=n,server=y -Xcompiler-option --debuggable"

        private const val JVMTI_ERROR = """ 
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
! Warning: JVMTI agent is enabled. Please enable the !
! 'Always install with package manager' option in    !
! Android Studio. For more details and information,  !
! check out RootService's Javadoc.                   !
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
"""

        private const val REMOTE_EN_ROUTE = 1 shl 0
        private const val DAEMON_EN_ROUTE = 1 shl 1
        private const val RECEIVER_REGISTERED = 1 shl 2

        @SuppressLint("StaticFieldLeak")
        private var mInstance: RootServiceManager? = null

        @JvmStatic
        @Synchronized
        fun getInstance(): RootServiceManager {
            if (mInstance == null) {
                mInstance = RootServiceManager()
            }
            return mInstance!!
        }

        @SuppressLint("WrongConstant")
        @JvmStatic
        fun getBroadcastIntent(binder: IBinder, isDaemon: Boolean): Intent {
            val bundle = Bundle().apply {
                putBinder(BUNDLE_BINDER_KEY, binder)
            }
            return Intent(RECEIVER_BROADCAST)
                .setPackage(Utils.getContext().packageName)
                .addFlags(HiddenAPIs.FLAG_RECEIVER_FROM_SHELL)
                .putExtra(INTENT_DAEMON_KEY, isDaemon)
                .putExtra(INTENT_BUNDLE_KEY, bundle)
        }

        private fun enforceMainThread() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw IllegalStateException("This method can only be called on the main thread")
            }
        }

        private fun parseIntent(intent: Intent): ServiceKey {
            val name = intent.component ?: throw IllegalArgumentException("The intent does not have a component set")
            if (name.packageName != Utils.getContext().packageName) {
                throw IllegalArgumentException("RootServices outside of the app are not supported")
            }
            return ServiceKey(name, intent.hasCategory(RootService.CATEGORY_DAEMON_MODE))
        }
    }

    private var mRemote: RemoteProcess? = null
    private var mDaemon: RemoteProcess? = null
    private var flags = 0

    private val pendingTasks: MutableList<BindTask> = ArrayList()
    private val services: MutableMap<ServiceKey, RemoteServiceRecord> = ArrayMap()
    private val connections: MutableMap<ServiceConnection, ConnectionRecord> = ArrayMap()

    @SuppressLint("InlinedApi")
    private fun startRootProcess(name: ComponentName, action: String): Shell.Task {
        val context = Utils.getContext()

        if ((flags and RECEIVER_REGISTERED) == 0) {
            val filter = IntentFilter(RECEIVER_BROADCAST)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    ServiceReceiver(), filter,
                    Manifest.permission.BROADCAST_PACKAGE_REMOVED, null,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(
                    ServiceReceiver(), filter,
                    Manifest.permission.BROADCAST_PACKAGE_REMOVED, null
                )
            }
            flags = flags or RECEIVER_REGISTERED
        }

        return object : Shell.Task {
            override fun run(stdin: java.io.OutputStream, stdout: java.io.InputStream, stderr: java.io.InputStream) {
                if (Utils.hasStartupAgents(context)) {
                    Log.e(TAG, JVMTI_ERROR)
                }

                 val ctx = Utils.getDeContext()

                 var env = ""
                 var params = ""

                 if (Utils.vLog()) {
                     env = "$LOGGING_ENV=1 "
                 }

                 if (Build.VERSION.SDK_INT >= 27 && Debug.isDebuggerConnected()) {
                     env += "$DEBUG_ENV=1 "
                     params = if (Build.VERSION.SDK_INT == 27) API_27_DEBUG else API_28_DEBUG
                 }

                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                     params += " -Xnoimage-dex2oat"
                 }

                 val niceNameCmd = when (action) {
                     Constants.CMDLINE_START_SERVICE -> String.format(
                         Locale.ROOT, "--nice-name=%s:root:%d",
                         ctx.packageName, Process.myUid() / 100000
                     )
                     Constants.CMDLINE_START_DAEMON -> "--nice-name=${ctx.packageName}:root:daemon"
                     else -> ""
                 }

                 var appProcess = "/system/bin/app_process"
                 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                     appProcess += if (Utils.isProcess64Bit()) "64" else "32"
                 }

                 val cmd = String.format(
                     Locale.ROOT,
                     "(%s CLASSPATH=%s %s %s /system/bin %s com.valhalla.superuser.internal.RootServerMain '%s' %d %s >/dev/null 2>&1)&",
                     env, ctx.packageCodePath, appProcess, params, niceNameCmd,
                     name.flattenToString(),
                     Process.myUid(),
                     action
                 )

                 Utils.log(TAG, cmd)
                 val bytes = cmd.toByteArray(StandardCharsets.UTF_8)
                 stdin.write(bytes)
                 stdin.write('\n'.code)
                 stdin.flush()
            }
        }
    }

    private fun bindInternal(intent: Intent, executor: Executor, conn: ServiceConnection): ServiceKey? {
        enforceMainThread()

        val key = parseIntent(intent)
        var s = services[key]
        if (s != null) {
            connections[conn] = ConnectionRecord(s, executor)
            s.refCount++
            val binder = s.binder
            executor.execute { conn.onServiceConnected(key.name, binder) }
            return null
        }

        val p = if (key.isDaemon) mDaemon else mRemote
        if (p == null) return key

        try {
            val binder = p.mgr.bind(intent)
            if (binder != null) {
                s = RemoteServiceRecord(key, binder, p)
                connections[conn] = ConnectionRecord(s, executor)
                services[key] = s
                executor.execute { conn.onServiceConnected(key.name, binder) }
            } else if (Build.VERSION.SDK_INT >= 28) {
                executor.execute { conn.onNullBinding(key.name) }
            }
        } catch (e: RemoteException) {
            Utils.err(TAG, e)
            p.binderDied()
            return key
        }

        return null
    }

    fun createBindTask(intent: Intent, executor: Executor, conn: ServiceConnection): Shell.Task? {
        val key = bindInternal(intent, executor, conn)
        if (key != null) {
            pendingTasks.add(BindTask { bindInternal(intent, executor, conn) == null })
            val mask = if (key.isDaemon) DAEMON_EN_ROUTE else REMOTE_EN_ROUTE
            if ((flags and mask) == 0) {
                flags = flags or mask
                val action = if (key.isDaemon) Constants.CMDLINE_START_DAEMON else Constants.CMDLINE_START_SERVICE
                return startRootProcess(key.name, action)
            }
        }
        return null
    }

    fun unbind(conn: ServiceConnection) {
        enforceMainThread()

        val r = connections.remove(conn)
        if (r != null) {
            val s = r.service
            s.refCount--
            if (s.refCount == 0) {
                services.remove(s.key)
                try {
                    s.host.mgr.unbind(s.key.name)
                } catch (e: RemoteException) {
                    Utils.err(TAG, e)
                }
            }
            r.disconnect(conn)
        }
    }

    private fun dropConnections(predicate: (RemoteServiceRecord) -> Boolean) {
        val it = connections.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val r = e.value
            if (predicate(r.service)) {
                r.disconnect(e.key)
                it.remove()
            }
        }
    }

    private fun onServiceStopped(key: ServiceKey) {
        val s = services.remove(key)
        if (s != null) {
            dropConnections { s == it }
        }
    }

    fun createStopTask(intent: Intent): Shell.Task? {
        enforceMainThread()

        val key = parseIntent(intent)
        val p = if (key.isDaemon) mDaemon else mRemote
        if (p == null) {
            if (key.isDaemon) {
                return startRootProcess(key.name, Constants.CMDLINE_STOP_SERVICE)
            }
            return null
        }

        try {
            p.mgr.stop(key.name, -1)
        } catch (e: RemoteException) {
            Utils.err(TAG, e)
        }

        onServiceStopped(key)
        return null
    }

    override fun handleMessage(msg: Message): Boolean {
        if (msg.what == MSG_STOP) {
            onServiceStopped(ServiceKey(msg.obj as ComponentName, msg.arg1 != 0))
        }
        return false
    }

    private data class ServiceKey(val name: ComponentName, val isDaemon: Boolean)

    private class ConnectionRecord(val service: RemoteServiceRecord, val executor: Executor) {
        fun disconnect(conn: ServiceConnection) {
            executor.execute { conn.onServiceDisconnected(service.key.name) }
        }
    }

    private inner class RemoteProcess(s: IRootServiceManager) : BinderHolder(s.asBinder()) {
        val mgr: IRootServiceManager = s

        override fun onBinderDied() {
            if (mRemote === this) mRemote = null
            if (mDaemon === this) mDaemon = null

            val sit = services.values.iterator()
            while (sit.hasNext()) {
                if (sit.next().host === this) {
                    sit.remove()
                }
            }
            dropConnections { it.host === this }
        }
    }

    private class RemoteServiceRecord(
        val key: ServiceKey,
        val binder: IBinder,
        val host: RemoteProcess
    ) {
        var refCount = 1
    }

    private inner class ServiceReceiver : BroadcastReceiver() {
        private val m: Messenger

        init {
            val h = Handler(Looper.getMainLooper(), this@RootServiceManager)
            m = Messenger(h)
        }

        override fun onReceive(context: Context, intent: Intent) {
            val bundle = intent.getBundleExtra(INTENT_BUNDLE_KEY) ?: return
            val binder = bundle.getBinder(BUNDLE_BINDER_KEY) ?: return

            val mgr = IRootServiceManager.Stub.asInterface(binder)
            try {
                mgr.connect(m.binder)
                val p = RemoteProcess(mgr)
                if (intent.getBooleanExtra(INTENT_DAEMON_KEY, false)) {
                    mDaemon = p
                    flags = flags and DAEMON_EN_ROUTE.inv()
                } else {
                    mRemote = p
                    flags = flags and REMOTE_EN_ROUTE.inv()
                }
                for (i in pendingTasks.indices.reversed()) {
                    if (pendingTasks[i].run()) {
                        pendingTasks.removeAt(i)
                    }
                }
            } catch (e: RemoteException) {
                Utils.err(TAG, e)
            }
        }
    }

    private fun interface BindTask {
        fun run(): Boolean
    }
}
