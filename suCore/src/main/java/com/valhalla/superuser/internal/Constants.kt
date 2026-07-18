package com.valhalla.superuser.internal

internal object Constants {
    const val CMDLINE_START_SERVICE = "start"
    const val CMDLINE_START_DAEMON = "daemon"
    const val CMDLINE_STOP_SERVICE = "stop"

    @JvmStatic
    fun getServiceName(pkg: String): String {
        return "odin-$pkg"
    }
}
