package com.valhalla.superuser.internal

import com.valhalla.superuser.Shell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class ResultFuture : ResultHolder(), Future<Shell.Result?> {
    private val latch = CountDownLatch(1)

    override fun onResult(out: Shell.Result) {
        super.onResult(out)
        latch.countDown()
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        return latch.count != 0L
    }

    override fun isCancelled(): Boolean {
        return false
    }

    override fun isDone(): Boolean {
        return latch.count == 0L
    }

    @Throws(InterruptedException::class)
    override fun get(): Shell.Result {
        latch.await()
        return result
    }

    @Throws(InterruptedException::class, TimeoutException::class)
    override fun get(timeout: Long, unit: TimeUnit?): Shell.Result {
        if (!latch.await(timeout, unit)) {
            throw TimeoutException()
        }
        return result
    }
}
