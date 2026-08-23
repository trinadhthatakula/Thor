// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.home

import com.valhalla.thor.R
import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.Installers
import com.valhalla.thor.presentation.widgets.installerLabel
import com.valhalla.thor.util.UiText

/** Bars beyond this many collapse into Others. */
private const val MAX_SLICES = 4

/**
 * One bar of the Home installation-source chart.
 *
 * [installerPackageName] is what a tap filters the Apps tab on. It is null for Others, the one
 * bucket that names no single installer, so that bar draws as a plain label instead of a button
 * that would land on a list nothing could populate.
 */
data class InstallerSlice(
    val installerPackageName: String?,
    val label: UiText,
    val count: Int
)

/**
 * Groups [apps] by the app that installed them, capped at [MAX_SLICES] bars.
 *
 * Buckets on the installer's **package id**. The label-keyed version this replaces built its key
 * with `pkg.substringAfterLast(".").uppercase()`, which drew `com.aurora.store` as the meaningless
 * "STORE" and silently merged any two installers sharing a last segment into a single bar.
 *
 * [labelFor] names an installer that the three curated ids don't cover — see
 * [com.valhalla.thor.domain.repository.InstallerLabelResolver], whose null answer (nothing by that
 * id is installed) leaves the raw id showing. The curated three are named from resources instead,
 * so they read identically on every device and translate.
 *
 * Everything past the cap collapses into Others, and so do the apps whose installer Android never
 * recorded: the Apps tab filters sources by installer id, and neither bucket has one to offer.
 */
internal fun installerDistribution(
    apps: List<AppInfo>,
    labelFor: (String) -> String?
): List<InstallerSlice> {
    if (apps.isEmpty()) return emptyList()

    // `null` and the "Unknown" placeholder are the same fact reported by different layers, so they
    // have to land in the same bucket or the chart draws the same thing twice.
    val counts = apps
        .groupingBy { app ->
            app.installerPackageName?.takeUnless { it == Installers.UNKNOWN || it.isBlank() }
        }
        .eachCount()

    val ranked = counts.entries.sortedByDescending { it.value }
    val kept = if (ranked.size <= MAX_SLICES) ranked else ranked.take(MAX_SLICES - 1)
    val named = kept.filter { it.key != null }

    val slices = named.map { (installer, count) ->
        InstallerSlice(
            installerPackageName = installer,
            label = installerLabel(installer!!, labelFor),
            count = count
        )
    }

    // Whatever the named bars don't account for: the tail past the cap, plus the no-installer
    // bucket if it made the cut. Computing it as the remainder rather than summing the tail is what
    // keeps those two from ever being drawn as separate, indistinguishable bars.
    val others = counts.values.sum() - slices.sumOf { it.count }
    return if (others > 0) {
        slices + InstallerSlice(null, UiText.StringResource(R.string.others), others)
    } else {
        slices
    }
}
