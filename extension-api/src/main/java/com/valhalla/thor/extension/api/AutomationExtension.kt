package com.valhalla.thor.extension.api

import android.content.Context
import androidx.compose.runtime.Composable

interface AutomationExtension : ThorExtension {
    fun onTrigger(context: Context, eventType: String, shellExecutor: ShellExecutor)

    @Composable
    fun ConfigurationScreen(shellExecutor: ShellExecutor)
}
