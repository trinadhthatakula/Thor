// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.freezer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.valhalla.thor.R
import com.valhalla.thor.domain.model.BulkResult
import com.valhalla.thor.presentation.tile.bulkResultMessage
import org.koin.core.annotation.Single

/**
 * Posts the outcome of a bulk run as a notification, when permitted.
 *
 * Strictly additive: the tile's own subtitle is the unconditional surface, so a user who
 * never grants POST_NOTIFICATIONS still sees their result. Nothing here ever grants the
 * permission — it is requested from Settings like any other runtime permission.
 */
@Single
class BulkResultNotifier(
    private val context: Context,
) {
    fun post(result: BulkResult) {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(manager)

        // areNotificationsEnabled() covers both regimes with no SDK_INT branch: it is backed
        // by POST_NOTIFICATIONS on 33+ and by the user's app-level toggle on 28-32. The
        // channel check catches a user who silenced this channel specifically.
        val channelSilenced = manager.getNotificationChannelCompat(CHANNEL_ID)?.importance ==
                NotificationManager.IMPORTANCE_NONE
        if (!manager.areNotificationsEnabled() || channelSilenced) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.frozen)
            .setContentTitle(bulkResultMessage(result).asString(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .setContentIntent(homeIntent())
            .build()

        // A fixed id, so repeated taps replace the previous result instead of stacking.
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // areNotificationsEnabled() can race a revocation; a lost result notification is
            // not worth crashing a background batch over. The tile subtitle still reports.
        }
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        // IMPORTANCE_DEFAULT, not LOW: SystemUI's PeekNotImportantSuppressor strips any peek
        // below DEFAULT, so LOW would be silently invisible. Not HIGH either — that is
        // dishonest for a routine result.
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(
                CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            )
                .setName(context.getString(R.string.channel_bulk_result_name))
                .setShowBadge(false)
                .build()
        )
    }

    private fun homeIntent(): PendingIntent? {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val CHANNEL_ID = "thor.bulk_result"
        const val NOTIFICATION_ID = 1001
        const val TIMEOUT_MS = 10_000L
    }
}
