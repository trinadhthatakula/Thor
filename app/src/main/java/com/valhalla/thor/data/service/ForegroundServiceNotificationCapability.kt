// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID

const val DATA_FOREGROUND_CHANNEL_ID = "thor.jobs.data"
const val PRIVILEGED_FOREGROUND_CHANNEL_ID = "thor.jobs.privileged"

const val FOREGROUND_PENDING_INTENT_FLAGS: Int =
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

/** Separate request-code ranges prevent one task's content and cancellation intents retargeting. */
enum class ForegroundPendingIntentNamespace(private val namespace: Int) {
    DATA_CONTENT(0x1000_0000),
    DATA_CANCELLATION(0x2000_0000),
    PRIVILEGED_CONTENT(0x3000_0000),
    PRIVILEGED_CANCELLATION(0x4000_0000),
    ;

    fun requestCode(taskId: UUID): Int = namespace or (taskId.hashCode() and UUID_BITS_MASK)

    private companion object {
        const val UUID_BITS_MASK = 0x0fff_ffff
    }
}

sealed interface ForegroundNotificationState {
    data object Available : ForegroundNotificationState
    data object PostPermissionDenied : ForegroundNotificationState
    data object Blocked : ForegroundNotificationState
    data class Invalid(val reason: String) : ForegroundNotificationState
}

val ForegroundNotificationState.permitsExecution: Boolean
    get() = this === ForegroundNotificationState.Available ||
        this === ForegroundNotificationState.PostPermissionDenied

/**
 * Classifies the notification surface before a queued task starts and guards initial promotion.
 *
 * Android 13's runtime notification denial is intentionally not a foreground-service start block:
 * the running service remains visible in Task Manager. App- or channel-level blocking is a product
 * start block, while a missing channel or failed notification construction/promotion is invalid.
 */
class ForegroundServiceNotificationCapability private constructor(
    private val environment: ForegroundNotificationEnvironment,
) {
    constructor(context: Context) : this(
        AndroidForegroundNotificationEnvironment(context.applicationContext)
    )

    internal constructor(
        sdkInt: Int,
        postNotificationsGranted: () -> Boolean,
        appNotificationsEnabled: () -> Boolean,
        channelImportance: (String) -> Int?,
    ) : this(
        LambdaForegroundNotificationEnvironment(
            sdkInt = sdkInt,
            postNotificationsGranted = postNotificationsGranted,
            appNotificationsEnabled = appNotificationsEnabled,
            channelImportance = channelImportance,
        )
    )

    fun currentState(channelId: String): ForegroundNotificationState = try {
        val importance = environment.channelImportance(channelId)
            ?: return ForegroundNotificationState.Invalid(
                "Missing notification channel: $channelId"
            )
        when {
            importance < NotificationManager.IMPORTANCE_NONE ->
                ForegroundNotificationState.Invalid(
                    "Invalid notification channel importance for $channelId: $importance"
                )

            importance == NotificationManager.IMPORTANCE_NONE ->
                ForegroundNotificationState.Blocked

            environment.sdkInt >= Build.VERSION_CODES.TIRAMISU &&
                !environment.postNotificationsGranted() ->
                ForegroundNotificationState.PostPermissionDenied

            !environment.appNotificationsEnabled() -> ForegroundNotificationState.Blocked
            else -> ForegroundNotificationState.Available
        }
    } catch (exception: Exception) {
        ForegroundNotificationState.Invalid(exception.invalidReason())
    }

    /**
     * Runs notification construction and `startForeground` only when foreground execution is legal.
     * Both operations belong in [constructAndPromote], so either failure becomes a typed abort.
     */
    fun evaluateAndPromote(
        channelId: String,
        constructAndPromote: () -> Unit,
    ): ForegroundNotificationState {
        val state = currentState(channelId)
        if (!state.permitsExecution) return state

        return try {
            constructAndPromote()
            state
        } catch (exception: Exception) {
            ForegroundNotificationState.Invalid(exception.invalidReason())
        }
    }
}

private interface ForegroundNotificationEnvironment {
    val sdkInt: Int

    fun postNotificationsGranted(): Boolean

    fun appNotificationsEnabled(): Boolean

    fun channelImportance(channelId: String): Int?
}

private class AndroidForegroundNotificationEnvironment(
    private val context: Context,
) : ForegroundNotificationEnvironment {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    @SuppressLint("InlinedApi")
    override fun postNotificationsGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    override fun appNotificationsEnabled(): Boolean = notificationManager.areNotificationsEnabled()

    override fun channelImportance(channelId: String): Int? =
        notificationManager.getNotificationChannel(channelId)?.importance
}

private class LambdaForegroundNotificationEnvironment(
    override val sdkInt: Int,
    private val postNotificationsGranted: () -> Boolean,
    private val appNotificationsEnabled: () -> Boolean,
    private val channelImportance: (String) -> Int?,
) : ForegroundNotificationEnvironment {
    override fun postNotificationsGranted(): Boolean = postNotificationsGranted.invoke()

    override fun appNotificationsEnabled(): Boolean = appNotificationsEnabled.invoke()

    override fun channelImportance(channelId: String): Int? =
        channelImportance.invoke(channelId)
}

internal fun Exception.invalidReason(): String = message ?: javaClass.simpleName
