package com.valhalla.superuser.internal

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * A modern replacement for the legacy Handler wrapper.
 * Uses Coroutines to dispatch to the Main thread.
 */
@Suppress("unused")
object UiThreadHandler {

    // We keep the Handler for edge cases where strict Looper access is needed,
    // but prefer Dispatchers.Main
    val handler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * An executor that dispatches to the Main Thread via Coroutines.
     * Used by [com.valhalla.superuser.CallbackList].
     */
    val executor: Executor = Executor { command ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            command.run()
        } else {
            mainScope.launch { command.run() }
        }
    }

    /**
     * Runs the runnable on the UI thread asynchronously.
     */
    fun run(r: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run()
        } else {
            mainScope.launch { r.run() }
        }
    }

    /**
     * Runs the runnable on the UI thread and BLOCKS the caller until it is finished.
     * Replaces the archaic WaitRunnable.
     */
    fun runAndWait(r: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run()
        } else {
            // Bridge blocking code to Coroutines
            runBlocking {
                withContext(Dispatchers.Main) {
                    r.run()
                }
            }
        }
    }
}