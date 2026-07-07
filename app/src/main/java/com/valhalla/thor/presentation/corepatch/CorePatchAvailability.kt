package com.valhalla.thor.presentation.corepatch

import com.valhalla.thor.domain.model.PrivilegeMode

/**
 * The exact phrase the user must type to confirm enabling CorePatch. Kept next to
 * [confirmPhraseMatches] so the gate check and the on-screen instruction never drift apart.
 */
const val CORE_PATCH_CONFIRM_PHRASE = "I understand the risk"

/**
 * CorePatch (Xposed signature-bypass) is only offered when the active privilege engine is ROOT and
 * the Strombringer LSPosed module is present ([lsposedActive]).
 *
 * Pure and free of Android / detection concerns so it is trivially unit-testable and independent of
 * how [lsposedActive] is sourced by the caller.
 */
fun corePatchAvailable(mode: PrivilegeMode, lsposedActive: Boolean): Boolean =
    mode == PrivilegeMode.ROOT && lsposedActive

/**
 * True only when [input], trimmed, exactly matches [CORE_PATCH_CONFIRM_PHRASE]. Drives the enabled
 * state of the opt-in dialog's confirm button. Pure.
 */
fun confirmPhraseMatches(input: String): Boolean = input.trim() == CORE_PATCH_CONFIRM_PHRASE
