package com.valhalla.superuser.ktx

import com.valhalla.superuser.Shell
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Suspends until the Shell Job is complete and returns the result.
 * Replaces the archaic [Shell.Job.submit] callback hell.
 */
suspend fun Shell.Job.await(): Shell.Result = suspendCancellableCoroutine { cont ->
    // Fix: ResultCallback is not a 'fun interface', so we must use an object expression.
    submit(object : Shell.ResultCallback {
        override fun onResult(out: Shell.Result) {
            if (cont.isActive) {
                cont.resume(out)
            }
        }
    })
}

/**
 * Converts a Shell Job's output into a reactive Flow.
 * Replaces the "CallbackList".
 *
 * Usage:
 * Shell.cmd("logcat").asFlow().collect { line -> ... }
 */
fun Shell.Job.asFlow(): Flow<String> = callbackFlow {
    // We create a custom list that emits to the flow when items are added.
    val flowList = object : java.util.ArrayList<String?>() {
        override fun add(element: String?): Boolean {
            element?.let { trySend(it) }
            return super.add(element)
        }
    }

    // Direct output to our flow-emitting list
    to(flowList)

    // Execute asynchronously using object expression for the callback
    submit(object : Shell.ResultCallback {
        override fun onResult(out: Shell.Result) {
            close() // Close the flow when the job is done
        }
    })

    awaitClose {
        // Handle cancellation if necessary, though libsu jobs might not support explicit cancel
    }
}

/**
 * Gets the main shell instance purely via Coroutines, removing the need for
 * blocking [Shell.getShell] calls on the main thread.
 */
suspend fun getShellAwait(): Shell = suspendCancellableCoroutine { cont ->
    Shell.getShell(object : Shell.GetShellCallback {
        override fun onShell(shell: Shell) {
            if (cont.isActive) {
                cont.resume(shell)
            }
        }
    })
}