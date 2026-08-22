// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.settings

import androidx.lifecycle.ViewModel
import com.valhalla.thor.data.backup.MIN_PASSPHRASE_LENGTH
import com.valhalla.thor.data.backup.PassphraseVault
import com.valhalla.thor.presentation.launchGuarded
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        // No `onFailure`: `remembered` defaults to false, and a vault this cannot read is one the sheet
        // should not be claiming holds anything. Guarded all the same — this runs in the constructor,
        // so a throw out of the DataStore collector would kill the process as the sheet opens.
        launchGuarded {
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
     *
     * One hole is left, the same one `beginBackup`'s KDoc names: if this view model is cleared before
     * the launched block is dispatched, the block and its `finally` never run, and [passphrase] is left
     * intact. It is **narrower here than there**, which is worth stating because the disclosure reads
     * the other way round. `AppBackupSheet` scopes its view model with `rememberViewModelStoreOwner()`,
     * so its window opens on every dismissal; this sheet takes the default owner, which under
     * `NavDisplay` is the **`NavEntry`**, not the activity — `MainScreen` installs
     * `rememberViewModelStoreNavEntryDecorator()` on every tab's entries. So what makes the window
     * narrow is a property of the route, not of the owner: `ThorRoute.Settings` is the root of
     * `settingsBackStack` and is never popped, so its entry outlives every visit to this sheet.
     * Do not read that as "the process is going anyway" — a finishing activity can leave a cached
     * process behind, still holding whatever this array did not get to wipe. Narrow is not closed,
     * so it is written down rather than left to be rediscovered.
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
        launchGuarded(
            // Reached by nothing the vault can produce: `PassphraseVault.remember` catches both its
            // Keystore wrap and its store write and reports either as `false`, which the block below
            // turns into `STORE_FAILED` on its own. This is the residual guard for anything else that
            // could ever be added to the block, and it reports the same thing the vault's own failure
            // reports, because from the sheet there is no difference: the passphrase is not stored.
            // `busy` is not cleared here — the block's `finally` has already run by the time this does.
            onFailure = { _uiState.update { it.copy(saved = false, error = PassphraseError.STORE_FAILED) } }
        ) {
            _uiState.update { it.copy(busy = true, error = null, saved = false) }
            try {
                val stored = vault.remember(passphrase)
                _uiState.update {
                    it.copy(
                        saved = stored,
                        error = if (stored) null else PassphraseError.STORE_FAILED,
                    )
                }
            } finally {
                // Both of these are in the `finally` because nothing reaching this block may leave
                // either one behind — including a cancellation, which no `catch` above would see.
                //
                // `busy` is cleared **here and nowhere else** — deliberately not folded into the
                // completion update above, which only ever runs on the path that already worked. A
                // `busy` left true disables both text fields and every button the sheet is showing,
                // `dismiss()` does not reset it, and this view model outlives the sheet — its owner is
                // the `NavEntry` for `ThorRoute.Settings`, that back stack's never-popped root — so the
                // flag would survive closing and reopening the sheet: the user's only way out would be
                // to leave the app. The cost of putting it here is one extra emission on the happy
                // path. The test `busy is cleared when the store fails, and the failure is reported`
                // pins that, and pins it against a store that fails rather than one that returns.
                //
                // The store failure this used to have to let escape is now reported instead:
                // `PassphraseVault.remember` catches its own store write, as `recall()` always did,
                // and returns `false` — which the block above maps to `PassphraseError.STORE_FAILED`.
                // That is what allowed this to stop being the one bare `viewModelScope.launch` on the
                // branch; `launchGuarded` now wraps it like every other coroutine in these three view
                // models.
                passphrase.fill(' ')
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    fun forget() {
        // No `onFailure`: `remembered` is owned by the `init` collector, so a forget that did not take
        // leaves the sheet still saying a passphrase is stored — which is the truth, and the button is
        // still there to press again. Nothing to report that the state does not already say.
        launchGuarded {
            vault.forget()
            // `remembered` is deliberately not written here — the collector in `init` owns it.
            _uiState.update { it.copy(saved = false, error = null) }
        }
    }

    fun dismiss() = _uiState.update { it.copy(saved = false, error = null) }
}
