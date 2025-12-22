package com.valhalla.superuser.internal

import androidx.annotation.GuardedBy
import androidx.annotation.RestrictTo
import com.valhalla.superuser.NoShellException
import com.valhalla.superuser.Shell
import com.valhalla.superuser.Shell.GetShellCallback
import java.io.InputStream
import java.util.concurrent.Executor

@RestrictTo(RestrictTo.Scope.LIBRARY)
object MainShell {
    @GuardedBy("self")
    private val mainShell = arrayOfNulls<ShellImpl>(1)

    @GuardedBy("class")
    private var isInitMain = false

    @GuardedBy("class")
    private var mainBuilder: BuilderImpl? = null

    @JvmStatic
    @Synchronized
    fun get(): ShellImpl {
        var shell: ShellImpl? = cached
        if (shell == null) {
            if (isInitMain) {
                throw NoShellException("The main shell died during initialization")
            }
            isInitMain = true
            if (mainBuilder == null) mainBuilder = BuilderImpl()
            shell = mainBuilder!!.build()
            isInitMain = false
        }
        return shell
    }

    private fun returnShell(s: Shell, e: Executor?, cb: GetShellCallback) {
        if (e == null) cb.onShell(s)
        else e.execute { cb.onShell(s) }
    }

    @JvmStatic
    fun get(executor: Executor?, callback: GetShellCallback) {
        val shell: Shell? = cached
        if (shell != null) {
            returnShell(shell, executor, callback)
        } else {
            // Else we get shell in worker thread and call the callback when we get a Shell
            Shell.EXECUTOR.execute {
                try {
                    returnShell(get(), executor, callback)
                } catch (e: NoShellException) {
                    Utils.ex(e)
                }
            }
        }
    }

    @set:Synchronized
    var cached: ShellImpl?
        get() {
            synchronized(mainShell) {
                var s = mainShell[0]
                if (s != null && s.status < 0) {
                    s = null
                    mainShell[0] = null
                }
                return s
            }
        }
        set(shell) {
            if (isInitMain) {
                synchronized(mainShell) {
                    mainShell[0] = shell
                }
            }
        }

    @Synchronized
    fun setBuilder(builder: Shell.Builder?) {
        check(!(isInitMain || cached != null)) { "The main shell was already created" }
        mainBuilder = builder as BuilderImpl?
    }

    fun newJob(`in`: InputStream): Shell.Job {
        return PendingJob().add(`in`)
    }

    fun newJob(vararg commands: String?): Shell.Job {
        return PendingJob().apply {
            commands.forEach { cmd ->
                if (cmd != null) add(cmd)
            }
        }
    }
}
