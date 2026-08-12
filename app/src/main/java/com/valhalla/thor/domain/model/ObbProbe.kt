// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * What a privileged look at `Android/obb/<pkg>` found — three answers, not two.
 *
 * `AppInfo.obbFilePath` is computed with `File(...).exists()`, which on Android 11+ returns false
 * for another package's OBB directory *whether or not one exists*. Any probe shaped like a path
 * test therefore folds "I cannot see it" into "there is none" — and under Thor's export policy
 * ("only offer .xapk when the OBB is capturable") that fold produces exactly the silently
 * incomplete bundle GH#164 is about. So the probe asserts its own privilege first and only then
 * interprets absence.
 */
sealed interface ObbProbe {

    /** The privileged read succeeded and the app has no expansion files. `.xapk` is offered. */
    data object None : ObbProbe

    /**
     * The privileged read succeeded and found something.
     *
     * [files] holds the depth-1 `*.obb` files, the only shape the XAPK format can carry.
     * [otherEntryCount] counts everything else in the directory — subdirectories, non-`.obb`
     * files — which will not be packed. It is a note shown to the user, not a refusal: the format
     * has no way to carry those, so refusing would deny a bundle that is complete by the format's
     * own definition.
     *
     * [files] may be empty while [otherEntryCount] is not. That is still `Present`, because the
     * directory exists and holds content Thor deliberately leaves out.
     */
    data class Present(val files: List<ObbFile>, val otherEntryCount: Int) : ObbProbe

    /**
     * The active privilege could not read `Android/obb` at all — the Dhizuku device-owner process,
     * a gateway failure, a truncated reply.
     *
     * **Never treat this as [None].** It is the whole reason this type is a tri-state: [None] is a
     * measurement and this is the absence of one, they carry different [reason]s, and the export
     * sheet and the log say different things about them.
     *
     * What they no longer carry is different *blocking* consequences. Export used to refuse `.xapk`
     * outright on this verdict, which made the commonest situation on the device — an app with no
     * expansion files, probed through a shell that could not answer — an error. Per the owner it now
     * packs nothing and proceeds, and the export is fatal only where loss is *provable*: see
     * `requireStagedExpansions`, which fires when [Present] named files and the copy then failed.
     * Reading this as "so it is basically [None]" is the fold this type exists to prevent; the two
     * agree on one narrow question (how many expansions to pack) and on nothing else.
     */
    data class Undetermined(val reason: String) : ObbProbe
}

/** One expansion file in `Android/obb/<pkg>/`, named by its leaf. */
data class ObbFile(val name: String, val sizeBytes: Long)
