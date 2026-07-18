package com.valhalla.superuser.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
internal object HiddenAPIs {
    private const val TAG = "HiddenAPIs"

    private var addService: Method? = null
    private var getService: Method? = null
    private var attachBaseContext: Method? = null
    private var setAppName: Method? = null

    val FLAG_RECEIVER_FROM_SHELL = if (Build.VERSION.SDK_INT >= 26) 0x00400000 else 0

    init {
        runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            getService = sm.getDeclaredMethod("getService", String::class.java)
            if (Build.VERSION.SDK_INT >= 28) {
                runCatching {
                    addService = sm.getDeclaredMethod(
                        "addService",
                        String::class.java,
                        IBinder::class.java,
                        Boolean::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                }
            }
            if (addService == null) {
                addService = sm.getDeclaredMethod("addService", String::class.java, IBinder::class.java)
            }

            attachBaseContext = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
                isAccessible = true
            }

            val ddm = Class.forName("android.ddm.DdmHandleAppName")
            setAppName = ddm.getDeclaredMethod("setAppName", String::class.java, Int::class.javaPrimitiveType)
        }.onFailure { e ->
            Log.e(TAG, "Initialization failed", e)
        }
    }

    @JvmStatic
    fun setAppName(name: String) {
        runCatching {
            setAppName?.invoke(null, name, 0)
        }.onFailure { e ->
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun getService(name: String): IBinder? {
        return runCatching {
            getService?.invoke(null, name) as? IBinder
        }.getOrNull()
    }

    @JvmStatic
    fun addService(name: String, service: IBinder) {
        runCatching {
            val method = addService ?: return
            if (method.parameterTypes.size == 4) {
                // Set dumpPriority to 0 so the service cannot be listed
                method.invoke(null, name, service, false, 0)
            } else {
                method.invoke(null, name, service)
            }
        }.onFailure { e ->
            Log.e(TAG, "addService failed", e)
        }
    }

    @JvmStatic
    fun attachBaseContext(wrapper: Any, context: Context) {
        if (wrapper is ContextWrapper) {
            runCatching {
                attachBaseContext?.invoke(wrapper, context)
            }
        }
    }
}
