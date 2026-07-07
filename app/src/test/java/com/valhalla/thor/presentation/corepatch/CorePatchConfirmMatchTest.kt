package com.valhalla.thor.presentation.corepatch

import org.junit.Assert.*
import org.junit.Test

class CorePatchConfirmMatchTest {
    @Test
    fun `exact phrase matches, trimmed`() = assertTrue(confirmPhraseMatches("  I understand the risk "))

    @Test
    fun `wrong phrase rejected`() = assertFalse(confirmPhraseMatches("yes"))

    @Test
    fun `empty rejected`() = assertFalse(confirmPhraseMatches(""))
}
