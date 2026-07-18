package com.valhalla.superuser.internal

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Callable

internal class RootServerMain(args: Array<String>) : ContextWrapper(null), Callable<Array<Any>> {

    private val uid: Int
    private var isDaemon: Boolean = false

    init {
        val name = ComponentName.unflattenFromString(args[0])!!
        uid = args[1].toInt()
        val action = args[2]
        var stop = false

        when (action) {
            Constants.CMDLINE_STOP_SERVICE -> {
                stop = true
                isDaemon = true
            }
            Constants.CMDLINE_START_DAEMON -> {
                isDaemon = true
            }
            Constants.CMDLINE_START_SERVICE -> {
                isDaemon = false
            }
            else -> throw IllegalArgumentException("Unknown action")
        }

        if (isDaemon) {
            try {
                // Get existing daemon process
                val binder = HiddenAPIs.getService(Constants.getServiceName(name.packageName))
                val m = IRootServiceManager.Stub.asInterface(binder)
                if (m != null) {
                    if (stop) {
                        m.stop(name, uid)
                    } else {
                        m.broadcast(uid)
                        // Terminate process if broadcast went through without exception
                        System.exit(0)
                    }
                }
            } catch (ignored: RemoteException) {
            } finally {
                if (stop) {
                    System.exit(0)
                }
            }
        }

        // Override LG system resources to prevent crashing
        try {
            Class.forName("com.lge.systemservice.core.integrity.IntegrityManager")
            val systemRes = Resources.getSystem()
            val wrapper = ResourcesWrapper(systemRes)
            val systemResField = Resources::class.java.getDeclaredField("mSystem")
            systemResField.isAccessible = true
            systemResField.set(null, wrapper)
        } catch (ignored: ReflectiveOperationException) {
        }

        val systemContext = getSystemContext()
        var context: Context? = null
        val userId = uid / 100000
        val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
        try {
            val userHandle: UserHandle = try {
                UserHandle::class.java.getDeclaredMethod("of", Int::class.javaPrimitiveType)
                    .invoke(null, userId) as UserHandle
            } catch (e: NoSuchMethodException) {
                UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .newInstance(userId)
            }
            context = systemContext.javaClass.getDeclaredMethod(
                "createPackageContextAsUser",
                String::class.java,
                Int::class.javaPrimitiveType,
                UserHandle::class.java
            ).invoke(systemContext, name.packageName, flags, userHandle) as Context
        } catch (e: Throwable) {
            Log.w("IPC", "Failed to create package context as user: $userId", e)
            context = systemContext.createPackageContext(name.packageName, flags)
        }
        attachBaseContext(context)

        val cl = context!!.classLoader
        val clz = cl.loadClass(name.className)
        val ctor = clz.getDeclaredConstructor()
        ctor.isAccessible = true
        HiddenAPIs.attachBaseContext(ctor.newInstance(), this)
    }

    override fun call(): Array<Any> {
        return arrayOf(uid, isDaemon)
    }

    companion object {
        private const val TAG = "IPC"

        @SuppressLint("PrivateApi")
        fun getSystemContext(): Context {
            return try {
                val atClazz = Class.forName("android.app.ActivityThread")
                val systemMain = atClazz.getMethod("systemMain")
                val activityThread = systemMain.invoke(null)
                val getSystemContext = atClazz.getMethod("getSystemContext")
                getSystemContext.invoke(activityThread) as Context
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }

        @JvmStatic
        fun main(args: Array<String>) {
            System.out.close()
            System.err.close()
            if (args.size < 3) {
                System.exit(1)
            }

            Looper.prepareMainLooper()

            try {
                RootServerMain(args)
            } catch (e: Exception) {
                Log.e(TAG, "Error in IPCMain", e)
                System.exit(1)
            }

            Looper.loop()
            System.exit(1)
        }
    }

    internal class ResourcesWrapper(res: Resources) : Resources(res.assets, res.displayMetrics, res.configuration) {
        init {
            val getImpl = Resources::class.java.getDeclaredMethod("getImpl")
            getImpl.isAccessible = true
            val setImpl = Resources::class.java.getDeclaredMethod("setImpl", getImpl.returnType)
            setImpl.isAccessible = true
            val impl = getImpl.invoke(res)
            setImpl.invoke(this, impl)
        }

        override fun getBoolean(id: Int): Boolean {
            return try {
                super.getBoolean(id)
            } catch (e: NotFoundException) {
                false
            }
        }
    }
}
