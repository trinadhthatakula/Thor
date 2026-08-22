// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.appList

import com.valhalla.thor.domain.model.BundleFormat
import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the export sheet says about game data, decided without a device.
 *
 * The sheet used to *refuse* `.xapk` on an [ObbProbe.Undetermined] verdict — a disabled chip, a
 * LaunchedEffect that forced the selection back, and a throw in the builder. All three are gone; the
 * verdict now produces one note and nothing else. The interesting property of these three functions
 * is therefore what they do **not** do, and that is only checkable by pinning both sides of every
 * gate: a function that returned `false`/`0` for everything would satisfy every "does not block"
 * assertion in this file and say nothing to the user at all.
 *
 * `probe` is nullable here and nowhere below this layer. Null is "the probe is still in flight",
 * which is a fourth state the sheet has and the domain does not.
 */
class ExportFormatGateTest {

    private val files = listOf(
        ObbFile("main.1.com.example.game.obb", 4L),
        ObbFile("patch.1.com.example.game.obb", 6L)
    )

    // ---- shouldWarnUnreadableObb -----------------------------------------------------------

    @Test
    fun `an unreadable probe is a note on the xapk chip and silence on the others`() {
        val probe = ObbProbe.Undetermined("no privileged shell is available")

        assertTrue(shouldWarnUnreadableObb(BundleFormat.XAPK, probe))

        // The other side of the format gate. The note describes what a .xapk will contain, so on a
        // container that never carries expansions it answers a question the user did not ask.
        assertFalse(shouldWarnUnreadableObb(BundleFormat.APK, probe))
        assertFalse(shouldWarnUnreadableObb(BundleFormat.APKS, probe))
    }

    @Test
    fun `a probe that answered says nothing, and one still running says nothing either`() {
        // None is an answer: the app has no game data, and there is nothing to tell the user.
        assertFalse(shouldWarnUnreadableObb(BundleFormat.XAPK, ObbProbe.None))
        assertFalse(
            shouldWarnUnreadableObb(BundleFormat.XAPK, ObbProbe.Present(files, otherEntryCount = 0))
        )

        // Null is the in-flight state, not a verdict. Treating it as Undetermined would flash this
        // note on the sheet for the length of every probe and then retract it.
        assertFalse(shouldWarnUnreadableObb(BundleFormat.XAPK, null))
    }

    // ---- obbSizeBytesToShow ----------------------------------------------------------------

    @Test
    fun `the size line sums the measured expansions for a xapk`() {
        assertEquals(
            10L,
            obbSizeBytesToShow(BundleFormat.XAPK, ObbProbe.Present(files, otherEntryCount = 0))
        )

        // Same verdict, other containers: the bytes exist, they are simply not going into this
        // archive, so announcing them would be a promise the export does not keep.
        assertEquals(0L, obbSizeBytesToShow(BundleFormat.APK, ObbProbe.Present(files, 0)))
        assertEquals(0L, obbSizeBytesToShow(BundleFormat.APKS, ObbProbe.Present(files, 0)))
    }

    @Test
    fun `no size line for a verdict that measured nothing`() {
        assertEquals(0L, obbSizeBytesToShow(BundleFormat.XAPK, ObbProbe.None))
        assertEquals(0L, obbSizeBytesToShow(BundleFormat.XAPK, ObbProbe.Undetermined("gateway")))
        assertEquals(0L, obbSizeBytesToShow(BundleFormat.XAPK, null))

        // Present with no .obb files at all — the directory exists and holds only things the format
        // cannot carry. Zero bytes to announce; see the partial note below, which does fire here.
        assertEquals(
            0L,
            obbSizeBytesToShow(BundleFormat.XAPK, ObbProbe.Present(emptyList(), otherEntryCount = 3))
        )
    }

    // ---- shouldNotePartialObb --------------------------------------------------------------

    @Test
    fun `the partial note fires on entries the format cannot carry, whether or not there are obbs`() {
        assertTrue(
            shouldNotePartialObb(BundleFormat.XAPK, ObbProbe.Present(files, otherEntryCount = 2))
        )
        // The case that makes this a separate function from obbSizeBytesToShow: nothing to announce
        // a size for, and still something being left behind.
        assertTrue(
            shouldNotePartialObb(
                BundleFormat.XAPK,
                ObbProbe.Present(emptyList(), otherEntryCount = 1)
            )
        )
    }

    @Test
    fun `no partial note when the directory held nothing extra, or was never read`() {
        assertFalse(
            shouldNotePartialObb(BundleFormat.XAPK, ObbProbe.Present(files, otherEntryCount = 0))
        )
        assertFalse(shouldNotePartialObb(BundleFormat.XAPK, ObbProbe.None))
        assertFalse(shouldNotePartialObb(BundleFormat.XAPK, ObbProbe.Undetermined("gateway")))
        assertFalse(shouldNotePartialObb(BundleFormat.XAPK, null))

        // And the format gate, on the arm that would otherwise be true.
        assertFalse(shouldNotePartialObb(BundleFormat.APKS, ObbProbe.Present(files, 2)))
    }

    // ---- the three together ----------------------------------------------------------------

    @Test
    fun `an unreadable probe leaves the xapk offer intact and says exactly one thing`() {
        // The regression the owner reported, stated at the level the user experiences it. Before the
        // fix this verdict disabled the .xapk chip and forced the selection away from it; the
        // formats a sheet offers come from BundleFormat.autoFor plus XAPK and are no longer filtered
        // by the probe at all, so the only remaining trace of the verdict is one note.
        val probe = ObbProbe.Undetermined("the privileged shell exited with code 1")

        assertTrue(shouldWarnUnreadableObb(BundleFormat.XAPK, probe))
        assertEquals(0L, obbSizeBytesToShow(BundleFormat.XAPK, probe))
        assertFalse(shouldNotePartialObb(BundleFormat.XAPK, probe))
    }
}
