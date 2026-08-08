// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.pm.PackageManager
import com.valhalla.thor.domain.repository.InstallerLabelResolver
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads installer labels straight from the package manager.
 *
 * Callers ask once per distinct installer per recomputation, and the dashboard recomputes on every
 * app-list refresh, so the answers are memoised — a handful of binder round trips that would
 * otherwise repeat on a hot path.
 *
 * **Hits only.** A miss means nothing by that id is installed *right now*, which is exactly the
 * state that changes when the user installs the store: caching it would keep showing the raw
 * package id for the rest of the process. Misses are also the rare case, so re-asking costs little.
 *
 * The map is concurrent because the dashboard and the app list resolve on their own dispatchers.
 */
@Single
class InstallerLabelResolverImpl(
    private val packageManager: PackageManager
) : InstallerLabelResolver {

    private val labels = ConcurrentHashMap<String, String>()

    override fun labelFor(packageName: String): String? {
        labels[packageName]?.let { return it }

        val label = try {
            packageManager.getApplicationInfo(packageName, 0)
                .loadLabel(packageManager)
                .toString()
                .takeIf { it.isNotBlank() }
        } catch (_: PackageManager.NameNotFoundException) {
            // Uninstalled, or hidden from Thor by package visibility. Either way there is no name
            // to show, and the caller falls back to the id — which is still more use than nothing.
            null
        }

        return label?.also { labels[packageName] = it }
    }
}
