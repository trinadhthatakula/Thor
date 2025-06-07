package com.valhalla.superuser.internal

import com.valhalla.superuser.Shell

internal class ResultImpl : Shell.Result() {

    override var out: MutableList<String?> = mutableListOf()
    override var err: MutableList<String?> = mutableListOf()
    override var code: Int = JOB_NOT_EXECUTED

}
