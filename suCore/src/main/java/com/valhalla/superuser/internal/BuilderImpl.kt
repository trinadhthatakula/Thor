package com.valhalla.superuser.internal

import android.text.TextUtils
import androidx.annotation.RestrictTo
import com.valhalla.superuser.NoShellException
import com.valhalla.superuser.Shell
import java.io.IOException
import java.lang.reflect.Constructor

@RestrictTo(RestrictTo.Scope.LIBRARY)
class BuilderImpl : Shell.Builder() {

    var timeout: Long = 20
    private var flags = 0
    private var initializers: Array<Shell.Initializer?>? = null
    private var command: Array<String?>? = null

    fun hasFlags(mask: Int): Boolean {
        return (flags and mask) == mask
    }

    override fun setFlags(flags: Int): Shell.Builder {
        this@BuilderImpl.flags = flags
        return this
    }

    override fun setTimeout(timeout: Long): Shell.Builder {
        this@BuilderImpl.timeout = timeout
        return this
    }

    override fun setCommands(vararg commands: String?): Shell.Builder {
        command = arrayOf(*commands)
        return this
    }

    fun setInitializersImpl(clz: Array<out Class<out Shell.Initializer?>>) {
        initializers = arrayOfNulls(clz.size)
        for (i in clz.indices) {
            try {
                val c: Constructor<out Shell.Initializer?> = clz[i].getDeclaredConstructor()
                c.isAccessible = true
                initializers!![i] = c.newInstance()
            } catch (e: ReflectiveOperationException) {
                Utils.err(e)
            } catch (e: ClassCastException) {
                Utils.err(e)
            }
        }
    }

    private fun start(): ShellImpl {
        var shell: ShellImpl? = null

        // Root mount master
        if (!hasFlags(Shell.FLAG_NON_ROOT_SHELL) && hasFlags(Shell.FLAG_MOUNT_MASTER)) {
            try {
                shell = exec("su", "--mount-master")
                if (!shell.isRoot) shell = null
            } catch (_: NoShellException) {
            }
        }

        // Normal root shell
        if (shell == null && !hasFlags(Shell.FLAG_NON_ROOT_SHELL)) {
            try {
                shell = exec("su")
                if (!shell.isRoot) {
                    shell = null
                }
            } catch (_: NoShellException) {
            }
        }

        // Try normal non-root shell
        if (shell == null) {
            if (!hasFlags(Shell.FLAG_NON_ROOT_SHELL)) {
                Utils.setConfirmedRootState(false)
            }
            shell = exec("sh")
        }

        return shell
    }

    private fun exec(vararg commands: String?): ShellImpl {
        try {
            Utils.log(TAG, "exec " + TextUtils.join(" ", commands))
            val process = Runtime.getRuntime().exec(commands)
            return build(process)
        } catch (e: IOException) {
            Utils.ex(e)
            throw NoShellException("Unable to create a shell!", e)
        }
    }

    override fun build(process: Process?): ShellImpl {
        if(process == null) {
            throw NoShellException("Process cannot be null!, Unable to create a shell!")
        }
        val shell: ShellImpl
        try {
            shell = ShellImpl(this, process)
        } catch (e: Exception) {
            Utils.ex(e)
            throw NoShellException("Unable to create a shell!", e)
        }
        @Suppress("DEPRECATION")
        if (hasFlags(Shell.FLAG_REDIRECT_STDERR)) {
            Shell.enableLegacyStderrRedirection = true
        }
        MainShell.cached = (shell)
        if (initializers != null) {
            for (init in initializers) {
                if (init != null && !init.onInit( shell)) {
                    MainShell.cached = (null)
                    throw NoShellException("Unable to init shell")
                }
            }
        }
        return shell
    }

    override fun build(): ShellImpl {
        return if (command != null) {
            exec(*command!!)
        } else {
            start()
        }
    }

    companion object {
        private const val TAG = "BUILDER"
    }
}
