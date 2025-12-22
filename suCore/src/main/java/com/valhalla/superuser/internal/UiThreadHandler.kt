package com.valhalla.superuser.internal

import android.os.Handler
import android.os.Looper
import com.valhalla.superuser.ShellUtils.onMainThread
import java.util.concurrent.Executor

@Suppress("unused")
object UiThreadHandler {
    val handler: Handler = Handler(Looper.getMainLooper())
    val executor: Executor = Executor { obj: Runnable -> run(obj) }
    fun run(r: Runnable) {
        if (onMainThread()) {
            r.run()
        } else {
            handler.post(r)
        }
    }

    fun runAndWait(r: Runnable) {
        if (onMainThread()) {
            r.run()
        } else {
            val wr = WaitRunnable(r)
            handler.post(wr)
            wr.waitUntilDone()
        }
    }
}
