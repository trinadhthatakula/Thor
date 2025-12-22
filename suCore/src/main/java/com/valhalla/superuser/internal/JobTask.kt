package com.valhalla.superuser.internal

import com.valhalla.superuser.Shell
import com.valhalla.superuser.internal.StreamGobbler.ERR
import com.valhalla.superuser.internal.StreamGobbler.OUT
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

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

        if (outList != null && outList === errList && !Utils.isSynchronized(outList)) {
            // Synchronize the list internally only if both lists are the same and are not
            // already synchronized by the user
            val list = Collections.synchronizedList<String?>(outList)
            outList = list
            errList = list
        }

        val outGobbler = FutureTask(OUT(stdout, outList))
        val errGobbler = FutureTask(ERR(stderr, errList))
        Shell.EXECUTOR.execute(outGobbler)
        Shell.EXECUTOR.execute(errGobbler)

        val result = ResultImpl()
        try {
            for (src in sources) src.serve(stdin)
            stdin.write(END_CMD)
            stdin.flush()

            val code: Int = outGobbler.get()!!
            errGobbler.get()

            result.code = code
            result.out = outList?: mutableListOf()
            result.err = (if (noErr) null else err)?: mutableListOf()
        } catch (e: IOException) {
            Utils.err(e)
        } catch (e: ExecutionException) {
            Utils.err(e)
        } catch (e: InterruptedException) {
            Utils.err(e)
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
        private val END_CMD: ByteArray? =
            String.format("__RET=$?;echo %1\$s;echo %1\$s >&2;echo \$__RET;unset __RET\n", END_UUID)
                .toByteArray(
                    StandardCharsets.UTF_8
                )
        
        //private static final byte[] END_CMD = String
        //            .format("__RET=$?;echo %1$s;echo %1$s >&2;echo $__RET;unset __RET\n", END_UUID)
        //            .getBytes(UTF_8);
    }
}
