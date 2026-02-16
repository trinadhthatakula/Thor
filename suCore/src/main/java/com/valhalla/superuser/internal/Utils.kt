package com.valhalla.superuser.internal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.ArraySet
import androidx.annotation.RestrictTo
import com.valhalla.superuser.Shell
import com.valhalla.superuser.internal.MainShell.get
import com.valhalla.superuser.utils.Logger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

@Suppress("unused")
@RestrictTo(RestrictTo.Scope.LIBRARY)
object Utils {

    private const val TAG = "LIB_SU"
    private var synchronizedCollectionClass: Class<*>? = null

    // -1: uninitialized
    //  0: checked, no root
    //  1: checked, undetermined
    //  2: checked, root access
    private var currentRootState = -1

    fun log(log: Any) {
        log(TAG, log)
    }

    fun log(tag: String?, log: Any) {
        if (vLog()) Logger.d(tag, Logger.toString())
    }

    fun ex(t: Throwable?) {
        if (vLog()) Logger.e(TAG, "", t)
    }

    fun err(t: Throwable?) {
        err(TAG, t)
    }

    fun err(tag: String?, t: Throwable?) {
        Logger.e(tag, "", t)
    }

    fun vLog(): Boolean {
        return Shell.enableVerboseLogging
    }

    fun hasStartupAgents(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val agents = File(context.codeCacheDir, "startup_agents")
        return agents.isDirectory()
    }

    fun isSynchronized(collection: MutableCollection<*>?): Boolean {
        if (synchronizedCollectionClass == null) {
            synchronizedCollectionClass =
                Collections.synchronizedCollection<Any?>(mutableListOf<Any?>()).javaClass
        }
        return synchronizedCollectionClass!!.isInstance(collection)
    }

    @Throws(IOException::class)
    fun pump(`in`: InputStream, out: OutputStream): Long {
        var read: Int
        var total: Long = 0
        val buf = ByteArray(64 * 1024) /* 64K buffer */
        while ((`in`.read(buf).also { read = it }) > 0) {
            out.write(buf, 0, read)
            total += read.toLong()
        }
        return total
    }

    fun <E> newArraySet(): MutableSet<E?> {
        return ArraySet<E?>()
    }

    @get:Synchronized
    val isAppGrantedRoot: Boolean?
        get() {
            if (currentRootState < 0) {
                if (Process.myUid() == 0) {
                    // The current process is a root service
                    currentRootState = 2
                    return true
                }
                // noinspection ConstantConditions
                for (path in System.getenv("PATH")!!.split(":".toRegex())
                    .dropLastWhile { it.isEmpty() }.toTypedArray()) {
                    val su = File(path, "su")
                    if (su.canExecute()) {
                        // We don't actually know whether the app has been granted root access.
                        // Do NOT set the value as a confirmed state.
                        currentRootState = 1
                        return null
                    }
                }
                currentRootState = 0
                return false
            }
            return when (currentRootState) {
                0 -> false
                2 -> true
                else -> null
            }
        }

    @Synchronized
    fun setConfirmedRootState(value: Boolean) {
        currentRootState = if (value) 2 else 0
    }

    val isRootImpossible: Boolean
        get() = isAppGrantedRoot == java.lang.Boolean.FALSE

    val isMainShellRoot: Boolean
        get() = get().isRoot

    @get:SuppressLint("DiscouragedPrivateApi")
    val isProcess64Bit: Boolean
        get() {
            return Process.is64Bit()
        }
}
