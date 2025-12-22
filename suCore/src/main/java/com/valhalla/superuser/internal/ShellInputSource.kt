package com.valhalla.superuser.internal

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

internal interface ShellInputSource : Closeable {
    @Throws(IOException::class)
    fun serve(out: OutputStream)

    override fun close() {}

    companion object {
        const val TAG: String = "SHELL_IN"
    }
}

internal class InputStreamSource(private val `in`: InputStream) : ShellInputSource {
    @Throws(IOException::class)
    override fun serve(out: OutputStream) {
        Utils.pump(`in`, out)
        `in`.close()
        out.write('\n'.code)
        Utils.log(ShellInputSource.TAG, "<InputStream>")
    }

    override fun close() {
        try {
            `in`.close()
        } catch (_: IOException) {
        }
    }
}

internal class CommandSource(private val cmd: Array<out String>) : ShellInputSource {
    @Throws(IOException::class)
    override fun serve(out: OutputStream) {
        for (command in cmd) {
            out.write(command.toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            Utils.log(ShellInputSource.TAG, command)
        }
    }
}
