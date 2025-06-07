package com.valhalla.superuser.internal

class WaitRunnable(run: Runnable) : Runnable {
    private var r: Runnable?

    init {
        r = run
    }

    @Synchronized
    fun waitUntilDone() {
        while (r != null) {
            try {
                (this as Object).wait()
            } catch (_: InterruptedException) {
            }
        }
    }

    @Synchronized
    override fun run() {
        r!!.run()
        r = null
        (this as Object).notifyAll()
    }
}
