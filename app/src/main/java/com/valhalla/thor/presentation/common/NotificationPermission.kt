// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.common

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.valhalla.thor.util.Logger

/**
 * Whether Thor can post a notification right now, and the one or two routes to changing that.
 *
 * Returned by [rememberNotificationPermissionRequest]; a fresh instance is produced whenever
 * [isEnabled] changes, so it is a value and not a controller.
 */
@Immutable
class NotificationPermissionRequest internal constructor(
    /**
     * `NotificationManagerCompat.areNotificationsEnabled()` — the exact question `ThorJobNotifications`
     * and `BulkResultNotifier` ask before posting, and therefore the only one worth reporting.
     *
     * ⚠️ **This is not "the permission is held".** It is also false when the user has muted Thor
     * app-wide, which is a different state with a different fix, and below API 33 it is the *only*
     * state — there is no `POST_NOTIFICATIONS` to hold. Anything that renders this must not say
     * "permission denied".
     */
    val isEnabled: Boolean,
    private val requester: (() -> Unit)?,
    private val openSettings: () -> Unit,
    private val deepLinkWhenBlocked: Boolean,
) {

    /**
     * Whether a system dialog exists to be shown at all — API 33+, and nothing else.
     *
     * The system decides separately whether it will actually *draw* that dialog: after two denials
     * `RequestPermission` returns immediately having shown nothing. That is not visible from here, and
     * [request] is written so that it does not need to be.
     */
    val canRequest: Boolean get() = requester != null

    /**
     * Ask, by whatever route this device has — and on a device with no dialog, only if the caller said
     * a trip to Settings is warranted.
     *
     * A no-op when notifications are already enabled: on API 33+ requesting a held permission returns
     * granted without drawing anything, so calling this on a whim would be silent but pointless, and
     * below 33 it would send a user who has no problem into Settings.
     *
     * Below API 33 there is no dialog at all, so this is *either* the deep link or nothing. That is
     * the same fork `deepLinkWhenBlocked` answers on 33+ after a hard denial, and it answers it the
     * same way: an ask Thor raised on the user's behalf stays silent rather than launching a Settings
     * screen nobody asked for. A caller that wants the deep link unconditionally — the Settings row,
     * where the user just tapped — has [openNotificationSettings] and does not go through here.
     */
    fun request() {
        if (isEnabled) return
        val requester = requester
        when {
            requester != null -> requester()
            deepLinkWhenBlocked -> openSettings()
        }
    }

    /**
     * The per-app notification settings screen.
     *
     * The only lever below API 33, and the only one left on 33+ once the user has denied twice or
     * muted the app. Also the only way *back off* — there is no dialog that withdraws a granted
     * permission.
     */
    fun openNotificationSettings() = openSettings()
}

/**
 * The one place that knows how Thor asks for permission to post a notification.
 *
 * Extracted from the Settings → Notification access row, which had all of this inline, because a
 * second caller arrived: a job that starts while notifications are off runs with no visible progress
 * and no result — `ThorJobNotifications.update` and `postResult` both open with
 * `if (!areNotificationsEnabled()) return`, so a backup silently has no surface at all. Three
 * hand-written copies of a permission flow is how one of them ends up missing the
 * `shouldShowRequestPermissionRationale` branch and nagging a user forever.
 *
 * [isEnabled] is re-read on every `ON_RESUME`, because none of the ways it can change come back
 * through the launcher callback: a grant made in system Settings, an app-wide mute, and (on a ROM
 * that allows it) a revocation while Thor is backgrounded are all invisible until we look again.
 *
 * @param deepLinkWhenBlocked what [NotificationPermissionRequest.request] does when there is no dialog
 *   to draw — either because the user has denied twice on API 33+, or because the device is below 33
 *   and the permission does not exist. `true` where the **user** asked — the Settings row, where doing
 *   nothing would leave a dead switch that snaps back. `false` where **Thor** raised it on the user's
 *   behalf, such as a job starting: throwing someone out to a Settings screen they did not ask for,
 *   every time they run a backup, is worse than the missing notification. With it false, a re-request
 *   after a hard denial is a silent no-op, which is the throttle — no per-process latch is needed
 *   for it.
 */
@Composable
fun rememberNotificationPermissionRequest(
    deepLinkWhenBlocked: Boolean,
): NotificationPermissionRequest {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isEnabled by remember(context) {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // runCatching because ACTION_APP_NOTIFICATION_SETTINGS is not resolvable on every ROM, and a
    // missing Settings screen must not take down the screen that offered the link. Logged rather than
    // swallowed silently: on such a ROM there is no route left at all, which is worth knowing when a
    // user reports that the switch does nothing.
    val openSettings: () -> Unit = remember(context) {
        {
            runCatching { context.startActivity(appNotificationSettingsIntent(context)) }
                .onFailure { throwable ->
                    Logger.w("Notifications", "No app notification settings screen: $throwable")
                }
        }
    }

    // Registering the launcher inside the version check is safe: SDK_INT is constant for the process,
    // so the conditional group is stable across recompositions. It also keeps every POST_NOTIFICATIONS
    // reference inside the check, which is what lint's InlinedApi wants.
    val requester: (() -> Unit)? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activity = remember(context) { context.findActivity() }
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { _ ->
                // Re-read instead of trusting `granted`. What callers act on is
                // areNotificationsEnabled(), which is also false when the permission is held but the
                // user muted the app — trusting `granted` flipped the Settings switch on and the next
                // ON_RESUME flipped it back off.
                val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                isEnabled = enabled
                // RequestPermission returns immediately without showing a dialog once the user has
                // denied twice, which would otherwise leave the caller with nothing to offer.
                // shouldShowRequestPermissionRationale distinguishes that (and the "granted but
                // muted" case, both false) from a plain first denial (true, where asking again still
                // shows the dialog). Only the caller knows whether the user invited this, so whether
                // a dead end is worth a trip to Settings is theirs to decide.
                if (deepLinkWhenBlocked && !enabled) {
                    val canAskAgain = activity?.let {
                        ActivityCompat.shouldShowRequestPermissionRationale(
                            it,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    } ?: false
                    if (!canAskAgain) openSettings()
                }
            }
            remember(launcher) { { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } }
        } else {
            null
        }

    return remember(isEnabled, requester, openSettings, deepLinkWhenBlocked) {
        NotificationPermissionRequest(
            isEnabled = isEnabled,
            requester = requester,
            openSettings = openSettings,
            deepLinkWhenBlocked = deepLinkWhenBlocked,
        )
    }
}

/**
 * Ask for the notification permission at the moment a background job starts without it.
 *
 * Draws nothing. It exists because a WorkManager job is the one case where Thor's *only* surface is a
 * notification: `ThorJobNotifications.update` and `postResult` both return early when notifications
 * are off, so a backup or restore started in that state runs invisibly, finishes invisibly, and — if
 * the sheet was dismissed — reports nothing at all. Every job sheet wants exactly this, so it is one
 * composable rather than a block copied per sheet.
 *
 * @param jobActive whether a job is running **or queued**. Queued counts: `APPEND_OR_REPLACE` puts
 *   work behind a live job, and that job posts no progress either. Keyed on the transition, so the
 *   ask follows the job actually being accepted rather than the button being pressed — an enqueue
 *   that is refused or replaced never reaches here.
 *
 * `deepLinkWhenBlocked = false` throughout: Thor raised this, not the user. After a hard denial the
 * request is a silent no-op, which is the whole throttle — a user who has said no twice runs their
 * backups without notifications rather than being thrown out to a Settings screen every time.
 */
@Composable
fun RequestNotificationsWhenJobStarts(jobActive: Boolean) {
    val notifications = rememberNotificationPermissionRequest(deepLinkWhenBlocked = false)
    LaunchedEffect(jobActive) {
        if (jobActive && !notifications.isEnabled) notifications.request()
    }
}

/** Thor's own entry in system Settings → Apps → Notifications. */
private fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/**
 * Unwrap [LocalContext] to the hosting Activity. Compose hands out a ContextWrapper in some hosts,
 * and `shouldShowRequestPermissionRationale` needs the real Activity.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
