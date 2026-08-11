// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
import com.valhalla.thor.data.backup.PassphraseVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

/**
 * Why a passphrase was not accepted, as an enum rather than a string.
 *
 * No `@StringRes` here: an `R` reference would put an Android type in the class the JVM tests
 * construct, and the wording belongs to the screen that draws it.
 */
enum class PassphraseError { TOO_SHORT, MISMATCH, STORE_FAILED }

data class PassphraseSettingsUiState(
    /**
     * Whether a passphrase is stored on this device. A fact about the device, not an outcome of this
     * visit — [PassphraseSettingsViewModel.dismiss] leaves it alone.
     */
    val remembered: Boolean = false,
    /** The outcome of *this* visit. Cleared by [PassphraseSettingsViewModel.dismiss]. */
    val saved: Boolean = false,
    val error: PassphraseError? = null,
    val busy: Boolean = false,
)

/**
 * §5.4's surface: choose a passphrase to remember, replace it, or forget it.
 *
 * The vault is the only dependency, and the only source of truth for "is a passphrase stored" —
 * [PassphraseSettingsUiState.remembered] is collected from it rather than set beside it, because two
 * writers to that flag is exactly how a UI ends up offering to recall a passphrase that is not there.
 *
 * Nothing here re-encrypts anything, and nothing here can: an archive's key is derived from the
 * passphrase it was written with, so replacing the stored one changes what the *next* backup uses and
 * nothing else. That is a property of the format, which is why the sheet states it in words rather
 * than leaving it to be inferred.
 */
@KoinViewModel
class PassphraseSettingsViewModel(private val vault: PassphraseVault) : ViewModel() {

    private val _uiState = MutableStateFlow(PassphraseSettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            vault.isRemembered.collect { remembered ->
                _uiState.update { it.copy(remembered = remembered) }
            }
        }
    }

    /**
     * Validate and store, taking ownership of both arrays.
     *
     * Both arrays are wiped: [confirmation] before this returns on every path, [passphrase] before this
     * returns when it is refused and in the launched coroutine's `finally` when it is not.
     * `PassphraseVault.remember` clears only the byte buffer it derives from its argument, so this is
     * the last owner of the characters. As in `AppBackupViewModel.beginBackup`, that narrows the window
     * rather than closing it — the sheet held the same characters as a Compose `String` one frame
     * earlier, and no `fill` can reach that. It is done anyway, and for the same reason: every layer
     * beneath pays real complexity to keep key material short-lived, and none of it means anything if
     * the caller's copy is simply dropped in the clear.
     */
    fun save(passphrase: CharArray, confirmation: CharArray) {
        // Length before match: see the test that names this. Both checks run before the vault is
        // touched, so a refused attempt cannot replace a passphrase that was already stored — and §5.4
        // makes a passphrase lost that way unrecoverable, along with every archive written under it.
        val refusal = when {
            passphrase.size < MIN_PASSPHRASE_LENGTH -> PassphraseError.TOO_SHORT
            !passphrase.contentEquals(confirmation) -> PassphraseError.MISMATCH
            else -> null
        }
        if (refusal != null) {
            _uiState.update { it.copy(error = refusal, saved = false) }
            // This path never launches a coroutine, so a wipe placed only in the block below would
            // leave a rejected passphrase live for as long as the sheet is composed.
            passphrase.fill(' ')
            confirmation.fill(' ')
            return
        }
        // Already known equal to `passphrase`, and nothing below reads it again.
        confirmation.fill(' ')
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null, saved = false) }
            try {
                val stored = vault.remember(passphrase)
                _uiState.update {
                    it.copy(
                        busy = false,
                        saved = stored,
                        error = if (stored) null else PassphraseError.STORE_FAILED,
                    )
                }
            } finally {
                // In a `finally` so a throw out of the vault cannot leave the characters behind.
                // `remember` catches its own wrapping failures and reports them as `false`, so this
                // covers the store beneath it rather than the Keystore.
                passphrase.fill(' ')
            }
        }
    }

    fun forget() {
        viewModelScope.launch {
            vault.forget()
            // `remembered` is deliberately not written here — the collector in `init` owns it.
            _uiState.update { it.copy(saved = false, error = null) }
        }
    }

    fun dismiss() = _uiState.update { it.copy(saved = false, error = null) }
}
