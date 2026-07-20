package com.valhalla.superuser.internal

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.FileObserver
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.UserHandle
import android.util.ArrayMap
import android.util.SparseArray
import com.valhalla.superuser.ipc.RootService
import com.valhalla.superuser.internal.UiThreadHandler
import java.io.File
import java.util.concurrent.Callable

internal class RootServiceServer private constructor(context: Context) : IRootServiceManager.Stub(), Runnable {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var mInstance: RootServiceServer? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): RootServiceServer {
            if (mInstance == null) {
                mInstance = RootServiceServer(context)
            }
            return mInstance!!
        }

        @JvmStatic
        fun getInstanceOrNull(): RootServiceServer? {
            return mInstance
        }
    }

    private val observer: FileObserver
    private val services: MutableMap<ComponentName, ServiceRecord> = ArrayMap()
    private val clients = SparseArray<ClientProcess>()
    private var isDaemon = false
    internal var authorizedUid: Int = -1

    init {
        com.valhalla.superuser.Shell.enableVerboseLogging = System.getenv(RootServiceManager.LOGGING_ENV) != null
        Utils.context = context

        // Wait for debugger to attach if needed
        if (System.getenv(RootServiceManager.DEBUG_ENV) != null) {
            HiddenAPIs.setAppName(context.packageName + ":root")
            Utils.log(RootServiceManager.TAG, "Waiting for debugger to be attached...")
            while (!Debug.isDebuggerConnected()) {
                runCatching { Thread.sleep(200) }
            }
            Utils.log(RootServiceManager.TAG, "Debugger attached!")
        }

        observer = AppObserver(File(context.packageCodePath))
        observer.startWatching()

        if (context is Callable<*>) {
            runCatching {
                val objs = context.call() as Array<*>
                authorizedUid = objs[0] as Int
                isDaemon = objs[1] as Boolean
                if (isDaemon) {
                    HiddenAPIs.addService(Constants.getServiceName(context.packageName), this)
                }
                broadcast(objs[0] as Int)
            }.onFailure { e ->
                throw RuntimeException(e)
            }
        } else {
            throw IllegalArgumentException("Expected Context to be Callable")
        }

        if (!isDaemon) {
            UiThreadHandler.handler.postDelayed(this, 10 * 1000)
        }
    }

    private fun enforceCaller() {
        val callingUid = getCallingUid()
        if (callingUid != 0 && callingUid != 1000 && callingUid != authorizedUid) {
            throw SecurityException("Access Denied: UID $callingUid is not authorized to invoke Odin.")
        }
    }

    override fun run() {
        if (clients.size() == 0) {
            exit("No active clients")
        }
    }

    override fun connect(binder: IBinder) {
        enforceCaller()
        val uid = getCallingUid()
        UiThreadHandler.run { connectInternal(uid, binder) }
    }

    private fun connectInternal(uid: Int, binder: IBinder) {
        if (clients.get(uid) != null) return
        runCatching {
            clients.put(uid, ClientProcess(binder, uid))
            UiThreadHandler.handler.removeCallbacks(this)
        }.onFailure { e ->
            Utils.err(RootServiceManager.TAG, e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun broadcast(uid: Int) {
        enforceCaller()
        val targetUid = if (getCallingUid() == 0) uid else getCallingUid()
        Utils.log(RootServiceManager.TAG, "broadcast to uid=$targetUid")
        val intent = RootServiceManager.getBroadcastIntent(this, isDaemon)
        if (Build.VERSION.SDK_INT >= 24) {
            val h = UserHandle.getUserHandleForUid(targetUid)
            Utils.context?.sendBroadcastAsUser(intent, h)
        } else {
            Utils.context?.sendBroadcast(intent)
        }
    }

    override fun bind(intent: Intent): IBinder? {
        enforceCaller()
        val b = arrayOfNulls<IBinder>(1)
        val uid = getCallingUid()
        UiThreadHandler.runAndWait {
            runCatching {
                b[0] = bindInternal(uid, intent)
            }.onFailure { e ->
                Utils.err(RootServiceManager.TAG, e)
            }
        }
        return b[0]
    }

    override fun unbind(name: ComponentName) {
        enforceCaller()
        val uid = getCallingUid()
        UiThreadHandler.run {
            Utils.log(RootServiceManager.TAG, name.className + " unbind")
            unbindService(uid, name)
        }
    }

    override fun stop(name: ComponentName, uid: Int) {
        enforceCaller()
        val clientUid = if (getCallingUid() == 0) uid else getCallingUid()
        UiThreadHandler.run {
            Utils.log(RootServiceManager.TAG, name.className + " stop")
            unbindService(-1, name)
            broadcast(clientUid)
        }
    }

    fun selfStop(name: ComponentName) {
        UiThreadHandler.run {
            Utils.log(RootServiceManager.TAG, name.className + " selfStop")
            unbindService(-1, name)
        }
    }

    fun register(service: RootService) {
        val s = ServiceRecord(service)
        services[service.getComponentName()] = s
    }

    private fun bindInternal(uid: Int, intent: Intent): IBinder? {
        val c = clients.get(uid) ?: return null
        val name = intent.component ?: return null
        var s = services[name]
        if (s == null) {
            val context = Utils.context ?: return null
            val clz = context.classLoader.loadClass(name.className)
            if (!RootService::class.java.isAssignableFrom(clz)) {
                throw IllegalArgumentException("Target class ${clz.name} does not extend RootService")
            }
            val ctor = clz.getDeclaredConstructor()
            ctor.isAccessible = true
            HiddenAPIs.attachBaseContext(ctor.newInstance(), context)

            s = services[name] ?: return null
        }

        val activeBinder = s.binder
        if (activeBinder != null) {
            Utils.log(RootServiceManager.TAG, name.className + " rebind")
            if (s.rebind) {
                s.service.onRebind(s.intent!!)
            }
        } else {
            Utils.log(RootServiceManager.TAG, name.className + " bind")
            s.binder = s.service.onBind(intent)
            s.intent = intent.cloneFilter()
        }
        s.users.add(uid)
        return s.binder
    }

    private fun unbindInternal(s: ServiceRecord, uid: Int, onDestroy: Runnable) {
        val hadUsers = s.users.isNotEmpty()
        s.users.remove(uid)
        if (uid < 0 || s.users.isEmpty()) {
            if (hadUsers) {
                s.rebind = s.service.onUnbind(s.intent!!)
            }
            if (uid < 0 || !isDaemon) {
                s.service.onDestroy()
                onDestroy.run()

                for (user in s.users) {
                    val c = clients.get(user) ?: continue
                    val msg = Message.obtain()
                    msg.what = RootServiceManager.MSG_STOP
                    msg.arg1 = if (isDaemon) 1 else 0
                    msg.obj = s.intent?.component
                    runCatching {
                        c.m.send(msg)
                    }.onFailure { e ->
                        Utils.err(RootServiceManager.TAG, e)
                    }
                    msg.recycle()
                }
            }
        }
        if (services.isEmpty()) {
            exit("No active services")
        }
    }

    private fun unbindService(uid: Int, name: ComponentName) {
        val s = services[name] ?: return
        unbindInternal(s, uid) { services.remove(name) }
    }

    private fun unbindServices(uid: Int) {
        val it = services.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            if (uid < 0) {
                s.users.clear()
            }
            unbindInternal(s, uid) { it.remove() }
        }
    }

    private fun exit(reason: String) {
        Utils.log(RootServiceManager.TAG, "Terminate process: $reason")
        System.exit(0)
    }

    private inner class AppObserver(path: File) : FileObserver(
        path.parent ?: "/",
        CREATE or DELETE or DELETE_SELF or MOVED_TO or MOVED_FROM
    ) {
        private val name: String = path.name

        init {
            Utils.log(RootServiceManager.TAG, "Start monitoring: " + path.parent)
        }

        override fun onEvent(event: Int, path: String?) {
            if (event == DELETE_SELF || name == path) {
                exit("Package updated")
            }
        }
    }

    private inner class ClientProcess(b: IBinder, val uid: Int) : BinderHolder(b) {
        val m: Messenger = Messenger(b)

        override fun onBinderDied() {
            Utils.log(RootServiceManager.TAG, "Client process terminated, uid=$uid")
            clients.remove(uid)
            unbindServices(uid)
        }
    }

    private class ServiceRecord(val service: RootService) {
        val users: MutableSet<Int> = HashSet()
        var intent: Intent? = null
        var binder: IBinder? = null
        var rebind = false
    }
}
