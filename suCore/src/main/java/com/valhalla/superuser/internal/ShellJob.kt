package com.valhalla.superuser.internal

import com.valhalla.superuser.Shell
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Future

internal class ShellJob(private val shell: ShellImpl) : JobTask() {
    override fun exec(): Shell.Result {
        val holder = ResultHolder()
        callback = holder
        callbackExecutor = null
        try {
            shell.execTask(this)
        } catch (_: IOException) { /* JobTask does not throw */
        }
        return holder.result
    }

    override fun submit(executor: Executor?, cb: Shell.ResultCallback?) {
        callbackExecutor = executor
        callback = cb
        shell.submitTask(this)
    }

    override fun enqueue(): Future<Shell.Result?> {
        val future = ResultFuture()
        callback = future
        callbackExecutor = null
        shell.submitTask(this)
        return future
    }
}
