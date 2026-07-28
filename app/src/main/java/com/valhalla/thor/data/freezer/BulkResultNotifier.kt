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
import com.valhalla.thor.util.Logger
import com.valhalla.thor.util.bulkResultMessage
import org.koin.core.annotation.Single

/**
 * Posts the outcome of a bulk run as a notification, when permitted. Nothing here ever grants
 * the permission — it is requested from Settings like any other runtime permission.
 *
 * **Reporting coverage is not symmetric, and this is deliberate.** For FREEZE it is additive:
 * the run parks its result in `BulkFreezeRunner.lastResult`, so the tile subtitle reports it
 * on the next shade-open whether or not notifications are permitted. For UNFREEZE — which only
 * the launcher shortcut issues, since the tile is freeze-only — the runner deliberately does
 * *not* park the result (a process-lifetime result would surface in the freeze tile hours
 * later, saying "Unfroze 5 apps" on a tile that is now READY again), so this notification is
 * the only surface. A user who has notifications off gets no unfreeze report at all.
 *
 * That is the accepted trade: a silent unfreeze beats a tile that lies about its own state.
 * `docs/follow-ups/` tracks giving the unfreeze shortcut a surface of its own.
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
            // not worth crashing a background batch over. A FREEZE result is still reported by
            // the tile subtitle; an UNFREEZE one is lost, per the class KDoc.
            Logger.e("BulkResultNotifier", "notify() denied — permission revoked mid-post", e)
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
