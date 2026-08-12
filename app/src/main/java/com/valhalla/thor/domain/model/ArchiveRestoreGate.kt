// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * What `PackageManager` says about the app right now.
 *
 * @param signerSha256 null when it could not be read. **Not the same as "no signer"** — the gate
 *   refuses on null rather than treating an unverifiable app as a match.
 */
data class InstalledAppFacts(
    val signerSha256: String?,
    val versionCode: Long,
    val versionName: String?,
)

/** Why a restore will not be attempted. Every one of these is shown to the user in words. */
enum class ArchiveRestoreRefusal {
    /** The installed app is signed by a different key. No override exists for this one. */
    SIGNER_MISMATCH,

    /** The installed app's signer could not be read, so the mismatch check could not run. */
    SIGNER_UNVERIFIABLE,

    /** The app is not installed and the archive holds no `.xapk` to install it from. */
    DATA_ONLY_AND_APP_ABSENT,

    /** A selected class has no member in this archive. */
    CLASS_NOT_IN_ARCHIVE,

    NOTHING_SELECTED,

    /** Written by a newer Thor. Reading it partially would restore an incomplete tree. */
    SCHEMA_TOO_NEW,

    /**
     * The header's `schemaVersion` is zero or negative.
     *
     * No Thor ever wrote one: the field defaults to `ARCHIVE_SCHEMA_VERSION` and is encoded even at
     * its default, so this value was put there — by a crafted header or by corruption. It is the
     * same fail-closed rule the two below follow, applied to the field that decides how every
     * *other* field in the header is read.
     */
    INVALID_SCHEMA_VERSION,

    /**
     * The header's `packageName` is not a valid package name.
     *
     * This field is read from untrusted archive JSON and is used in filesystem paths. An invalid
     * value cannot be sanitised — the gate fails closed.
     */
    INVALID_PACKAGE_NAME,

    /**
     * The header's `userId` is negative.
     *
     * A negative user id cannot appear in a valid data directory path and indicates a corrupt or
     * crafted archive.
     */
    INVALID_USER_ID,
}

/** A condition the user is told about and may proceed through. */
enum class ArchiveRestoreWarning {
    /** Newer data onto older code — the classic permanent-crash-on-launch. */
    INSTALLED_VERSION_OLDER,

    /** `DE` holds first-run state; restoring `CE` alone can leave the app in a half-migrated state. */
    CE_WITHOUT_DE,
}

sealed interface ArchiveRestoreDecision {

    /**
     * @param installFirst the app is absent and will be installed from the archive's `.xapk` before
     *   any data is written. §8.1 is explicit that this is not a refusal.
     */
    data class Allowed(
        val installFirst: Boolean,
        val warnings: List<ArchiveRestoreWarning>,
    ) : ArchiveRestoreDecision

    data class Refused(val reason: ArchiveRestoreRefusal) : ArchiveRestoreDecision
}

/**
 * §8.1's table as one function.
 *
 * @param installed null when the app is not installed. That is the branch that must be tested
 *   **before** the signer, because an absent app has no signer and checking it first would refuse
 *   every install-then-restore.
 */
fun evaluateArchiveRestoreGate(
    header: ArchiveHeader,
    installed: InstalledAppFacts?,
    selectedClasses: Set<DataClass>,
): ArchiveRestoreDecision {
    // Both ends of the range. The upper one is §8.1's; the lower one is here because
    // `schemaVersion` is untrusted JSON that decides how the rest of the header is read, and a
    // header claiming version 0 or -1 is not an old archive Thor can still manage — it is not an
    // archive Thor wrote. Fails closed, like `INVALID_PACKAGE_NAME` and `INVALID_USER_ID` below.
    if (header.schemaVersion <= 0) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.INVALID_SCHEMA_VERSION)
    }
    if (header.schemaVersion > ARCHIVE_SCHEMA_VERSION) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.SCHEMA_TOO_NEW)
    }
    // Validate untrusted header fields that become filesystem paths. The gate is the security
    // boundary; it does not inherit validation from `dataClassRoot` — it asserts it explicitly.
    if (!isUsablePackageName(header.packageName)) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.INVALID_PACKAGE_NAME)
    }
    if (header.userId < 0) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.INVALID_USER_ID)
    }
    if (selectedClasses.isEmpty()) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.NOTHING_SELECTED)
    }
    val held = header.heldClasses()
    if (selectedClasses.any { it !in held }) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.CLASS_NOT_IN_ARCHIVE)
    }

    val warnings = mutableListOf<ArchiveRestoreWarning>()
    // The warning is about the user's *selection*, so it only applies when DE was there to select.
    if (DataClass.CE in selectedClasses &&
        DataClass.DE !in selectedClasses &&
        DataClass.DE in held
    ) {
        warnings += ArchiveRestoreWarning.CE_WITHOUT_DE
    }

    if (installed == null) {
        return if (header.appBundle == null) {
            ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.DATA_ONLY_AND_APP_ABSENT)
        } else {
            // No version warning: the version about to be installed *is* the archive's.
            ArchiveRestoreDecision.Allowed(installFirst = true, warnings = warnings)
        }
    }

    if (installed.signerSha256 == null) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.SIGNER_UNVERIFIABLE)
    }
    if (!installed.signerSha256.equals(header.signerSha256, ignoreCase = true)) {
        return ArchiveRestoreDecision.Refused(ArchiveRestoreRefusal.SIGNER_MISMATCH)
    }

    if (installed.versionCode < header.versionCode) {
        warnings += ArchiveRestoreWarning.INSTALLED_VERSION_OLDER
    }
    // An installed version *newer* than the archive gets no warning at all. Forward migration is what
    // apps are built for, and a warning on the common case trains users past the one that matters.

    return ArchiveRestoreDecision.Allowed(installFirst = false, warnings = warnings)
}
