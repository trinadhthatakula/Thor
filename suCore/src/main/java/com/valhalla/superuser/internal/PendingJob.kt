package com.valhalla.superuser.internal

import com.valhalla.superuser.NoShellException
import com.valhalla.superuser.Shell
import com.valhalla.superuser.Shell.GetShellCallback
import com.valhalla.superuser.internal.MainShell.get
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Future

internal class PendingJob : JobTask() {
    private var retryTask: Runnable? = null

    init {
        to(UNSET_LIST)
    }

    override fun shellDied() {
        if (retryTask != null) {
            val r = retryTask
            retryTask = null
            r!!.run()
        } else {
            super.shellDied()
        }
    }

    private fun exec0() {
        val shell: ShellImpl?
        try {
            shell = get()
        } catch (_: NoShellException) {
            super.shellDied()
            return
        }
        try {
            shell.execTask(this)
        } catch (_: IOException) { /* JobTask does not throw */
        }
    }

    override fun exec(): Shell.Result {
        retryTask = Runnable{
            this.exec0()
        }
        val holder = ResultHolder()
        callback = holder
        callbackExecutor = null
        exec0()
        return holder.result
    }

    private fun submit0() {
        get(null, object: GetShellCallback{
            override fun onShell(shell: Shell) {
                    (shell as ShellImpl).submitTask(this@PendingJob)
            }
        })
    }

    override fun enqueue(): Future<Shell.Result?> {
        retryTask = Runnable { this.submit0() }
        val future = ResultFuture()
        callback = future
        callbackExecutor = null
        submit0()
        return future
    }

    override fun submit(executor: Executor?, cb: Shell.ResultCallback?) {
        retryTask = Runnable { this.submit0() }
        callbackExecutor = executor
        callback = cb
        submit0()
    }
}
