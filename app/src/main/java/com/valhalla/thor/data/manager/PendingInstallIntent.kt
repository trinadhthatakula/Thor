// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.manager

import android.content.Intent
import org.koin.core.annotation.Single
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-slot, consume-once holder for the PackageInstaller confirmation [Intent]
 * (the OS-supplied EXTRA_INTENT for STATUS_PENDING_USER_ACTION).
 *
 * The data-layer [com.valhalla.thor.data.receivers.InstallReceiver] stashes the Intent
 * here and emits the payload-less [com.valhalla.thor.domain.InstallState.UserConfirmationRequired]
 * on the event bus. The presentation layer consumes it exactly once to launch the system
 * confirm dialog. This keeps the domain [com.valhalla.thor.domain.InstallState] free of
 * any Android types.
 *
 * Consume-once semantics ([consume] atomically clears the slot) mean a replayed
 * UserConfirmationRequired state (the InstallerEventBus replays the latest value) cannot
 * re-launch the dialog.
 */
@Single
class PendingInstallIntent {
    private val ref = AtomicReference<Intent?>(null)

    fun set(intent: Intent) {
        ref.set(intent)
    }

    /** Returns the pending Intent (if any) and atomically clears the slot. */
    fun consume(): Intent? = ref.getAndSet(null)
}
