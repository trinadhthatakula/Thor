// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What a sheet is while its job is live: the bar, what the worker says it is doing, and a way to leave.
 *
 * The Background button is honest about what it does because of where the work runs. The job is a
 * WorkManager foreground service, so dismissing the sheet drops this sheet's *watcher* and nothing
 * else. Reopening finds the same job again through `runningJobFor`, and the ongoing notification
 * carries the progress meanwhile, which is what makes leaving a real option rather than an
 * abandonment.
 *
 * No Cancel button, deliberately: the notification already carries one built from
 * `WorkManager.createCancelPendingIntent`, and a second cancel affordance on a surface that is about
 * to be dismissed is a control whose result the user would not be watching.
 *
 * Every sentence is a parameter rather than a string resource read in here. The frame is shared and
 * the copy is not: "waiting behind another backup or restore" is wrong over an export, and a shared
 * key that had to cover both would end up saying "waiting" and nothing else. Passing them in also
 * keeps this composable free of `R`, so it renders the same in a preview as in a sheet.
 *
 * @param queuedLabel shown only while [JobPhase.queued]. Says where the job is and stops there —
 *   a dependent whose prerequisite fails is cancelled, so "starting soon" would be a promise
 *   WorkManager has not made.
 */
@Composable
fun JobRunningFrame(
    phase: JobPhase,
    queuedLabel: String,
    backgroundLabel: String,
    backgroundDescription: String,
    onBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val percent = phase.progress?.percent
        if (percent == null) {
            // Indeterminate, never a determinate bar pinned at 0. A job that has published no
            // progress — or one whose stage has no total to measure against — is not "0% done", and a
            // bar that says so reads as stuck for however long the preparing stage takes.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (phase.queued) {
            Text(
                text = queuedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        phase.progress?.let {
            Text(
                text = it.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Above the button rather than below it: it is the sentence that makes the button's one word
        // mean something, and a caption under a button is read after it has been pressed.
        Text(
            text = backgroundDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onBackground, modifier = Modifier.fillMaxWidth()) {
            Text(backgroundLabel)
        }
    }
}
