// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.usecase

import com.valhalla.thor.domain.model.InstalledAppFacts
import com.valhalla.thor.domain.repository.AppDataArchiveGateway
import com.valhalla.thor.domain.repository.AppRepository
import org.koin.core.annotation.Factory

/**
 * What the §8.1 gate needs to know about the app as it is installed right now, or null if it is not.
 *
 * Two call sites — the restore screen, which shows the gate's answer, and the restore worker, which
 * re-runs it after the chain drains. They have to agree on what "installed" means, so they share this
 * rather than each assembling the facts from a repository and a gateway.
 */
// `internal` because `AppDataArchiveGateway` is: a public class cannot expose an internal type in its
// constructor. That visibility travels — the restore view model takes this, so it is internal too.
@Factory
internal class ReadInstalledAppFactsUseCase(
    private val appRepository: AppRepository,
    private val gateway: AppDataArchiveGateway,
) {

    suspend operator fun invoke(packageName: String): InstalledAppFacts? {
        val app = appRepository.getAppDetails(packageName) ?: return null
        return InstalledAppFacts(
            // Null here is *not* "no signer" — the gate refuses on it. See InstalledAppFacts.
            signerSha256 = gateway.signerSha256(packageName),
            versionCode = app.versionCode,
            versionName = app.versionName,
        )
    }
}
