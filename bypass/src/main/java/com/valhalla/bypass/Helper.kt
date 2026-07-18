package com.valhalla.bypass

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.invoke.MethodType
import java.util.HashSet

@RequiresApi(Build.VERSION_CODES.P)
object Helper {
    val signaturePrefixes: MutableSet<String> = HashSet()

    private var cachedOffsetData: LongArray? = null
    private var cacheFile: File? = null
    private var artVersion = 0L

    @JvmStatic
    fun getCachedOffsetData(): LongArray? {
        return cachedOffsetData
    }

    @JvmStatic
    fun setCachedOffsetData(data: LongArray) {
        if (cachedOffsetData != null || data.size != 6) return
        cachedOffsetData = data

        val file = cacheFile ?: return
        try {
            FileOutputStream(file).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeUTF(Build.FINGERPRINT)
                    oos.writeLong(artVersion)
                    oos.writeObject(cachedOffsetData)
                }
            }
        } catch (ignored: IOException) {
        }
    }

    @JvmStatic
    fun enableOffsetCache(context: Context) {
        if (cacheFile != null) return
        cacheFile = File(context.cacheDir, "HiddenApiBypass")
        artVersion = getArtVersion(context)

        try {
            FileInputStream(cacheFile).use { fis ->
                ObjectInputStream(fis).use { ois ->
                    val fingerprint = ois.readUTF()
                    if (Build.FINGERPRINT != fingerprint) return
                    val art = ois.readLong()
                    if (artVersion != art) return
                    cachedOffsetData = ois.readObject() as LongArray
                }
            }
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun getArtVersion(context: Context): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return -1L
        val pm = context.packageManager
        return try {
            val moduleInfo = pm.getModuleInfo("com.android.art", 1)
            val name = moduleInfo.packageName ?: return -2L
            val info = pm.getPackageInfo(name, PackageManager.MATCH_APEX)
            info.longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                FileReader("/proc/self/mountinfo").use { file ->
                    BufferedReader(file).use { reader ->
                        val line = reader.lines()
                            .filter { s -> s.contains(" / /apex/com.android.art@") }
                            .findAny()
                        if (!line.isPresent) return -3L
                        val part = line.get().split("@".toRegex(), 2).toTypedArray()[1]
                        val versionStr = part.split(" ".toRegex(), 2).toTypedArray()[0]
                        versionStr.toLong()
                    }
                }
            } catch (e2: Exception) {
                -4L
            }
        }
    }

    @JvmStatic
    fun checkArgsForInvokeMethod(params: Array<java.lang.Class<*>>, args: Array<out Any?>): Boolean {
        if (params.size != args.size) return false
        for (i in params.indices) {
            val param = params[i]
            val arg = args[i]
            if (param.isPrimitive) {
                if (param == Int::class.javaPrimitiveType && arg !is Int) return false
                else if (param == Byte::class.javaPrimitiveType && arg !is Byte) return false
                else if (param == Char::class.javaPrimitiveType && arg !is Char) return false
                else if (param == Boolean::class.javaPrimitiveType && arg !is Boolean) return false
                else if (param == Double::class.javaPrimitiveType && arg !is Double) return false
                else if (param == Float::class.javaPrimitiveType && arg !is Float) return false
                else if (param == Long::class.javaPrimitiveType && arg !is Long) return false
                else if (param == Short::class.javaPrimitiveType && arg !is Short) return false
            } else if (arg != null && !param.isInstance(arg)) return false
        }
        return true
    }

    // Shadow classes matching internal ART layouts exactly for Unsafe offset scanning
    open class AccessibleObject {
        @JvmField protected var override: Boolean = false
    }

    class Executable : AccessibleObject() {
        @JvmField var declaringClass: Class? = null
        @JvmField var declaringClassOfOverriddenMethod: Class? = null
        @JvmField var parameters: Array<Any>? = null
        @JvmField var artMethod: Long = 0
        @JvmField var accessFlags: Int = 0
    }

    class Class {
        @JvmField var classLoader: ClassLoader? = null
        @JvmField var componentType: java.lang.Class<*>? = null
        @JvmField var dexCache: Any? = null
        @JvmField var extData: Any? = null
        @JvmField var ifTable: Array<Any>? = null
        @JvmField var name: String? = null
        @JvmField var superClass: java.lang.Class<*>? = null
        @JvmField var vtable: Any? = null
        @JvmField var iFields: Long = 0
        @JvmField var methods: Long = 0
        @JvmField var sFields: Long = 0
        @JvmField var accessFlags: Int = 0
        @JvmField var classFlags: Int = 0
        @JvmField var classSize: Int = 0
        @JvmField var clinitThreadId: Int = 0
        @JvmField var dexClassDefIndex: Int = 0
        @JvmField @Volatile var dexTypeIndex: Int = 0
        @JvmField var numReferenceInstanceFields: Int = 0
        @JvmField var numReferenceStaticFields: Int = 0
        @JvmField var objectSize: Int = 0
        @JvmField var objectSizeAllocFastPath: Int = 0
        @JvmField var primitiveType: Int = 0
        @JvmField var referenceInstanceOffsets: Int = 0
        @JvmField var status: Int = 0
        @JvmField var copiedMethodsOffset: Short = 0
        @JvmField var virtualMethodsOffset: Short = 0
    }

    class MethodHandle {
        @JvmField var type: MethodType? = null
        @JvmField var nominalType: MethodType? = null
        @JvmField var cachedSpreadInvoker: MethodHandle? = null
        @JvmField protected val handleKind: Int = 0
        @JvmField protected val artFieldOrMethod: Long = 0
    }

    @Suppress("UNUSED_PARAMETER", "unused")
    class NeverCall private constructor() {
        private fun a() {}
        private fun b() {}
        private var i = 0
        private var j = 0

        companion object {
            @JvmStatic private val s = 0
            @JvmStatic private val t = 0
        }
    }

    class InvokeStub private constructor(vararg args: Any?) {
        companion object {
            @JvmStatic
            private fun invoke(vararg args: Any?): Any {
                throw IllegalStateException("Failed to invoke the method")
            }
        }
        init {
            throw IllegalStateException("Failed to new an instance")
        }
    }
}
