package com.valhalla.thor.extension.api

import android.content.Context

interface AutomationExtension : ThorExtension {
    fun onTrigger(context: Context, eventType: String, shellExecutor: ShellExecutor)
}
