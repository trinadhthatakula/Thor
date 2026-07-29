// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * The container an app is packaged into when it is shared or exported.
 *
 * @param extension the filename suffix **without** the leading dot, so callers write
 *   `"$name.${format.extension}"`.
 * @param mime what the file is handed to SAF / MediaStore / an `ACTION_SEND` receiver as. Only a
 *   monolithic `.apk` is a package-archive; `.apks` and `.xapk` are zips that no system component
 *   can install directly, and typing them as a package-archive is what makes a receiver offer to
 *   install a bundle it will then choke on.
 */
enum class BundleFormat(val extension: String, val mime: String) {
    APK("apk", "application/vnd.android.package-archive"),
    APKS("apks", "application/octet-stream"),
    XAPK("xapk", "application/octet-stream");

    companion object {
        /**
         * The format to use when the user did not pick one: splits decide it, exactly as the
         * builder has always decided it.
         *
         * Never returns [XAPK]. XAPK is only ever an explicit choice — defaulting to it would
         * silently change what every existing share and export produces, for a container fewer
         * installers accept than `.apks`.
         */
        fun autoFor(appInfo: AppInfo): BundleFormat =
            if (appInfo.splitPublicSourceDirs.isEmpty()) APK else APKS
    }
}
