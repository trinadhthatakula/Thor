// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/** Counts are memberships: one app added to two profiles contributes two to [addedCount]. */
data class ProfileAssignmentResult(
    val profiles: List<ProfileAssignmentCount>,
    /** Newly enrolled packages, counted once even when assigned to several profiles. */
    val freezerAddedCount: Int = 0,
) {
    val addedCount: Int get() = profiles.sumOf { it.addedCount }
    val alreadyPresentCount: Int get() = profiles.sumOf { it.alreadyPresentCount }
}

/** The profile name and insertion counts read in the same transaction as the assignment. */
data class ProfileAssignmentCount(
    val profileId: Long,
    val profileName: String,
    val addedCount: Int,
    val alreadyPresentCount: Int,
)

/** No selected profile is changed when one or more targets disappeared before the write. */
class MissingFreezeProfilesException(profileIds: Set<Long>) :
    IllegalStateException("Freeze profiles no longer exist: ${profileIds.sorted()}") {
    val profileIds: Set<Long> = profileIds.toSet()
}
