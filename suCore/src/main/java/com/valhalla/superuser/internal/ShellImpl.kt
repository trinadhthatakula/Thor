package com.valhalla.superuser.internal

import android.text.TextUtils
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ShellUtils.cleanInputStream
import com.valhalla.superuser.ShellUtils.escapedString
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.Volatile

class ShellImpl(builder: BuilderImpl, private val process: Process) : Shell() {
    @Volatile
    override var status: Int
        private set

    private val stdIn: NoCloseOutputStream
    private val stdOut: NoCloseInputStream
    private val stdErr: NoCloseInputStream

    // Guarded by scheduleLock
    private val scheduleLock = ReentrantLock()
    private val idle: Condition = scheduleLock.newCondition()
    private val tasks = ArrayDeque<Task?>()
    private var isRunningTask = false

    private class SyncTask(private val condition: Condition) : Task {
        private var set = false

        fun signal() {
            set = true
            condition.signal()
        }

        fun await() {
            while (!set) {
                try {
                    condition.await()
                } catch (_: InterruptedException) {
                }
            }
        }

        override fun run(stdin: OutputStream, stdout: InputStream, stderr: InputStream) {}
    }

    private class NoCloseInputStream(`in`: InputStream?) : FilterInputStream(`in`) {
        override fun close() {}

        @Throws(IOException::class)
        fun close0() {
            `in`.close()
        }
    }

    private class NoCloseOutputStream(out: OutputStream) :
        FilterOutputStream(out as? BufferedOutputStream ?: BufferedOutputStream(out)) {
        @Throws(IOException::class)
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        @Throws(IOException::class)
        override fun close() {
            out.flush()
        }

        @Throws(IOException::class)
        fun close0() {
            super.close()
        }
    }

    init {
        status = UNKNOWN
        stdIn = NoCloseOutputStream(process.outputStream)
        stdOut = NoCloseInputStream(process.inputStream)
        stdErr = NoCloseInputStream(process.errorStream)

        // Shell checks might get stuck indefinitely
        val check = FutureTask<Int?> { this.shellCheck() }
        EXECUTOR.execute(check)
        try {
            try {
                status = check.get(builder.timeout, TimeUnit.SECONDS)!!
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause is IOException) {
                    throw cause
                } else {
                    throw IOException("Unknown ExecutionException", cause)
                }
            } catch (e: TimeoutException) {
                throw IOException("Shell check timeout", e)
            } catch (e: InterruptedException) {
                throw IOException("Shell check interrupted", e)
            }
        } catch (e: IOException) {
            release()
            throw e
        }
    }

    @Throws(IOException::class)
    private fun shellCheck(): Int {
        try {
            process.exitValue()
            throw IOException("Created process has terminated")
        } catch (_: IllegalThreadStateException) {
            // Process is alive
        }

        // Clean up potential garbage from InputStreams
        cleanInputStream(stdOut)
        cleanInputStream(stdErr)

        var status = NON_ROOT_SHELL
        BufferedReader(InputStreamReader(stdOut)).use { br ->
            stdIn.write(("echo SHELL_TEST\n").toByteArray(StandardCharsets.UTF_8))
            stdIn.flush()
            var s = br.readLine()
            if (TextUtils.isEmpty(s) || !s!!.contains("SHELL_TEST")) throw IOException("Created process is not a shell")

            stdIn.write(("id\n").toByteArray(StandardCharsets.UTF_8))
            stdIn.flush()
            s = br.readLine()
            if (!TextUtils.isEmpty(s) && s.contains("uid=0")) {
                status = ROOT_SHELL
                Utils.setConfirmedRootState(true)
                // noinspection ConstantConditions
                val cwd = escapedString(System.getProperty("user.dir") ?: "/")
                stdIn.write(("cd $cwd\n").toByteArray(StandardCharsets.UTF_8))
                stdIn.flush()
            }
        }
        return status
    }

    private fun release() {
        status = UNKNOWN
        try {
            stdIn.close0()
        } catch (_: IOException) {
        }
        try {
            stdErr.close0()
        } catch (_: IOException) {
        }
        try {
            stdOut.close0()
        } catch (_: IOException) {
        }
        process.destroy()
    }

    @Throws(InterruptedException::class)
    override fun waitAndClose(timeout: Long, unit: TimeUnit): Boolean {
        if (status < 0) return true

        scheduleLock.lock()
        try {
            if (isRunningTask && !idle.await(timeout, unit)) return false
            close()
        } finally {
            scheduleLock.unlock()
        }

        return true
    }

    override fun close() {
        if (status < 0) return
        release()
    }

    override val isAlive: Boolean
        get() {
            // If status is unknown, it is not alive
            if (status < 0) return false

            try {
                process.exitValue()
                // Process is dead, shell is not alive
                release()
                return false
            } catch (_: IllegalThreadStateException) {
                // Process is still running
                return true
            }
        }

    @Synchronized
    @Throws(IOException::class)
    private fun exec0(task: Task) {
        if (status < 0) {
            task.shellDied()
            return
        }

        cleanInputStream(stdOut)
        cleanInputStream(stdErr)
        try {
            stdIn.write('\n'.code)
            stdIn.flush()
        } catch (_: IOException) {
            release()
            task.shellDied()
            return
        }

        task.run(stdIn, stdOut, stdErr)
    }

    private fun processTasks() {
        var task: Task?
        while ((processNextTask(false).also { task = it }) != null) {
            try {
                exec0(task!!)
            } catch (_: IOException) {
            }
        }
    }

    private fun processNextTask(fromExec: Boolean): Task? {
        scheduleLock.lock()
        try {
            val task = tasks.poll()
            if (task == null) {
                isRunningTask = false
                idle.signalAll()
                return null
            }
            if (task is SyncTask) {
                task.signal()
                return null
            }
            if (fromExec) {
                // Put the task back in front of the queue
                tasks.offerFirst(task)
            } else {
                return task
            }
        } finally {
            scheduleLock.unlock()
        }
        EXECUTOR.execute { this.processTasks() }
        return null
    }

    override fun submitTask(task: Task) {
        scheduleLock.lock()
        try {
            tasks.offer(task)
            if (!isRunningTask) {
                isRunningTask = true
                EXECUTOR.execute { this.processTasks() }
            }
        } finally {
            scheduleLock.unlock()
        }
    }

    @Throws(IOException::class)
    override fun execTask(task: Task) {
        scheduleLock.lock()
        try {
            if (isRunningTask) {
                val sync = SyncTask(scheduleLock.newCondition())
                tasks.offer(sync)
                // Wait until it's our turn
                sync.await()
            }
            isRunningTask = true
        } finally {
            scheduleLock.unlock()
        }
        exec0(task)
        processNextTask(true)
    }

    override fun newJob(): Job {
        return ShellJob(this)
    }
}
