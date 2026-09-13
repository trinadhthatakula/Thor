// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.service

enum class ServiceStartFailure {
    BACKGROUND_START_NOT_ALLOWED,
    NOTIFICATION_CHANNEL_BLOCKED,
    SERVICE_COMPONENT_UNAVAILABLE,
    SECURITY_EXCEPTION,
    START_REQUEST_FAILED,
}

sealed interface ServiceStartResult {
    data object Requested : ServiceStartResult
    data object AlreadyRunning : ServiceStartResult
    data class Rejected(val reason: ServiceStartFailure) : ServiceStartResult
}
