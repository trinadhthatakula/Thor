package com.valhalla.superuser.internal

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable

internal abstract class StreamGobbler<T>(
    protected val `in`: InputStream?,
    protected val list: MutableList<String?>?
) : Callable<T?> {
    private fun outputAndCheck(line: String?): Boolean {
        var line = line ?: return false

        val len = line.length
        val end = line.startsWith(JobTask.END_UUID, len - JobTask.UUID_LEN)
        if (end) {
            if (len == JobTask.UUID_LEN) return false
            line = line.take(len - JobTask.UUID_LEN)
        }
        if (list != null) {
            list.add(line)
            Utils.log(TAG, line)
        }
        return !end
    }

    @Throws(IOException::class)
    protected fun process(res: Boolean): String? {
        val br = BufferedReader(InputStreamReader(`in`, StandardCharsets.UTF_8))
        var line: String?
        do {
            line = br.readLine()
        } while (outputAndCheck(line))
        return if (res) br.readLine() else null
    }

    internal class OUT(`in`: InputStream?, list: MutableList<String?>?) :
        StreamGobbler<Int?>(`in`, list) {
        @Throws(Exception::class)
        override fun call(): Int {
            val codeStr = process(true)
            try {
                val code = codeStr?.toInt() ?: NO_RESULT_CODE
                Utils.log(TAG, "(exit code: $code)")
                return code
            } catch (_: NumberFormatException) {
                return NO_RESULT_CODE
            }
        }

        companion object {
            private const val NO_RESULT_CODE = 1
        }
    }

    internal class ERR(`in`: InputStream?, list: MutableList<String?>?) :
        StreamGobbler<Void?>(`in`, list) {
        @Throws(Exception::class)
        override fun call(): Void? {
            process(false)
            return null
        }
    }

    companion object {
        private const val TAG = "SHELL_OUT"
    }
}
