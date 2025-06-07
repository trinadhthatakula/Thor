package com.valhalla.superuser.internal

import com.valhalla.superuser.Shell

internal open class ResultHolder : Shell.ResultCallback {

    var result: Shell.Result = ResultImpl()

    override fun onResult(out: Shell.Result) {
        result = out
    }

}
