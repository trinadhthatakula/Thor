package com.valhalla.thor.extension.api

interface ShellExecutor {
    fun execute(command: String): Pair<Int, String?>
}
