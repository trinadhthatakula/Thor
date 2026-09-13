// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.valhalla.thor.presentation.navigation.TaskNavigationTargets
import com.valhalla.thor.util.Logger
import java.util.UUID
import org.koin.android.ext.android.inject

private const val TAG = "TaskQueueLaunchActivity"

internal fun canonicalTaskId(raw: String?): UUID? {
    val parsed = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
    return parsed.takeIf { it.toString() == raw }
}

/** Publishes an exact task target, resumes Thor's existing task, and immediately exits. */
@SuppressLint("CustomSplashScreen")
class TaskQueueLaunchActivity : Activity() {
    private val targets: TaskNavigationTargets by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        canonicalTaskId(intent?.getStringExtra(EXTRA_TASK_ID))?.let(targets::requestOpen)

        val resume = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resume != null) {
            startActivity(resume)
        } else {
            Logger.e(TAG, "no launch intent for $packageName; tap resumed nothing")
        }
        finish()
    }

    companion object {
        const val EXTRA_TASK_ID = "com.valhalla.thor.extra.TASK_ID"
    }
}
