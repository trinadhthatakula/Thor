package com.valhalla.superuser.internal

import com.valhalla.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor

internal abstract class JobTask : Shell.Job(), Shell.Task {
    private val sources: MutableList<ShellInputSource> = ArrayList()
    private var out: MutableList<String?>? = null
    private var err: MutableList<String?>? = UNSET_LIST

    @JvmField
    protected var callbackExecutor: Executor? = null
    @JvmField
    protected var callback: Shell.ResultCallback? = null

    private fun setResult(result: ResultImpl) {
        if (callback != null) {
            if (callbackExecutor == null) callback!!.onResult(result)
            else callbackExecutor!!.execute { callback!!.onResult(result) }
        }
    }

    private fun close() {
        for (src in sources) src.close()
    }

    override fun run(
        stdin: OutputStream,
        stdout: InputStream,
        stderr: InputStream
    ) {
        val noOut = out === UNSET_LIST
        val noErr = err === UNSET_LIST

        var outList = if (noOut) (if (callback == null) null else ArrayList()) else out
        var errList =
            if (noErr) (if (Shell.enableLegacyStderrRedirection) outList else null) else err

        // Legacy compatibility: synchronize if lists are the same and not thread-safe
        if (outList != null && outList === errList && !Utils.isSynchronized(outList)) {
            val list = Collections.synchronizedList(outList)
            outList = list
            errList = list
        }

        val result = ResultImpl()

        // BRIDGE: We use runBlocking here because ShellImpl expects this method to block
        // until the task is finished. Inside, we use structured concurrency for I/O.
        runBlocking {
            // 1. Start reading STDOUT asynchronously
            val outJob = async(Dispatchers.IO) {
                CoroutineStreamGobbler.Out(stdout, outList).readCode()
            }

            // 2. Start reading STDERR asynchronously
            val errJob = launch(Dispatchers.IO) {
                CoroutineStreamGobbler.Err(stderr, errList).drain()
            }

            try {
                // 3. Write inputs to STDIN
                // We perform this on IO dispatcher to avoid blocking the main shell thread
                // (which called this method) on pipe writes.
                withContext(Dispatchers.IO) {
                    for (src in sources) src.serve(stdin)
                    stdin.write(END_CMD)
                    stdin.flush()
                }

                // 4. Wait for the exit code (UUID marker) from STDOUT
                val code = outJob.await()

                // 5. Ensure STDERR is fully drained
                errJob.join()

                result.code = code
                result.out = outList ?: mutableListOf()
                result.err = (if (noErr) null else err) ?: mutableListOf()

            } catch (e: Exception) {
                Utils.err(e)
                // Result code remains JOB_NOT_EXECUTED (-1)
            }
        }

        close()
        setResult(result)
    }

    override fun shellDied() {
        close()
        setResult(ResultImpl())
    }

    override fun to(stdout: MutableList<String?>?): Shell.Job {
        out = stdout
        err = UNSET_LIST
        return this
    }

    override fun to(
        stdout: MutableList<String?>?,
        stderr: MutableList<String?>?
    ): Shell.Job {
        out = stdout
        err = stderr
        return this
    }

    override fun add(inputStream: InputStream): Shell.Job {
        sources.add(InputStreamSource(inputStream))
        return this
    }

    override fun add(vararg cmds: String): Shell.Job {
        if (cmds.isNotEmpty()) sources.add(CommandSource(cmds))
        return this
    }

    companion object {
        @JvmField
        val UNSET_LIST: MutableList<String?> = ArrayList<String?>(0)

        @JvmField
        val END_UUID: String = UUID.randomUUID().toString()
        const val UUID_LEN: Int = 36

        // Corrected format string syntax
        private val END_CMD: ByteArray =
            String.format("__RET=\$?;echo %1\$s;echo %1\$s >&2;echo \$__RET;unset __RET\n", END_UUID)
                .toByteArray(StandardCharsets.UTF_8)
    }
}