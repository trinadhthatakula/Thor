// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.valhalla.thor.data.backup.job.JobSheetTargets
import com.valhalla.thor.domain.model.jobKindFromId
import com.valhalla.thor.util.Logger
import org.koin.android.ext.android.inject

private const val TAG = "JobSheetLaunchActivity"

/**
 * What a tap on a running job's notification lands on: ask the UI to reopen that job's sheet, bring
 * Thor's task forward, get out of the way.
 *
 * **Why a trampoline at all.** The notification cannot simply target `HomeActivity` with the payload
 * in its extras. `HomeActivity` is `standard` launchMode with no `onNewIntent`, and it reads
 * `pendingRestoreUri` from its creation intent `by lazy` — so a launcher-shaped intent resumes the
 * live task and drops the extras, while a component-targeted one stacks a second `HomeActivity` over
 * the first. Handing the request to [JobSheetTargets] instead keeps the notification's intent to a
 * single `kind` string and leaves the resume exactly as `BulkResultNotifier` already does it.
 *
 * An **activity** trampoline is legal from a notification; Android 12's ban covers services and
 * broadcasts. And unlike [FreezerLaunchActivity] this one takes no `taskAffinity=""` and no
 * `launchMode` — that pair exists there to *avoid* resuming Thor's task, which is the one thing this
 * activity is for.
 *
 * No `attachBaseContext`/`AppLocale.wrap` override either: it renders nothing and reads no string
 * resource, so there is no text for a locale to get wrong. Its only output is a log line.
 */
// Not a splash screen: a translucent trampoline that finishes inside onCreate.
@SuppressLint("CustomSplashScreen")
class JobSheetLaunchActivity : Activity() {

    private val sheetTargets: JobSheetTargets by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Null for an unrecognised id — a PendingIntent written by an older build, say. Falls through
        // to a plain resume rather than failing the tap.
        val kind = jobKindFromId(intent?.getStringExtra(EXTRA_JOB_KIND))
        val requested = kind?.let(sheetTargets::requestOpen) == true
        if (kind != null && !requested) {
            // The job is no longer live in this process: it finished, or the process died and this
            // activity is what restarted it. Either way there is no sheet to reopen and the resume
            // below is the whole of the tap.
            Logger.d(TAG, "${kind.id}: no live job to reopen, resuming only")
        }

        // The same shape BulkResultNotifier.homeIntent() uses, and for the same reason: ACTION_MAIN
        // plus NEW_TASK on the launch component brings the existing task forward with its state
        // intact instead of creating a second one. getLaunchIntentForPackage already sets NEW_TASK;
        // addFlags is belt-and-braces and matches the precedent.
        val resume = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resume != null) {
            startActivity(resume)
        } else {
            // No launchable component for our own package. Not reachable in any shipped build; if it
            // ever is, the request above is still queued and the next foreground will consume it.
            Logger.e(TAG, "no launch intent for $packageName; tap resumed nothing")
        }
        finish()
    }

    companion object {
        /** Carries [com.valhalla.thor.domain.model.ThorJobKind.id]. The only thing the notification's intent holds. */
        const val EXTRA_JOB_KIND = "com.valhalla.thor.extra.JOB_KIND"
    }
}
