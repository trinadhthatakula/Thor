# Follow-up: a restored `biometric_lock=true` is a hard lockout with no in-app escape

**Status:** OPEN, unfixed. Surfaced while device-verifying follow-up #20 (backup rules), confirmed by
reading the auth path.
**Severity:** **Major.** The failure mode is a hard lockout with no in-app escape — the only exits are
"clear app data", "uninstall", or "go set up a screen lock". It is rare *and* it lands exactly on new
users, at the worst possible moment.
**Effort:** small — one capability check, in one of two places.
**Raised by:** the #20 backup-rules device pass (2026-07-30).

**This is not a regression from #20.** Before #20, Thor shipped the unedited AGP template rules, which
back up *everything* — so `thor_preferences.preferences_pb` was already restorable and this was
already reachable. #20 narrowed the ruleset to that one file, which makes the behaviour deliberate
rather than accidental, but it neither introduced nor worsened it. Do not file this against #20's
diff.

Files: `app/src/main/java/com/valhalla/thor/presentation/security/SecurityViewModel.kt:37-52`,
`app/src/main/java/com/valhalla/thor/HomeActivity.kt:86-90`,
`app/src/main/java/com/valhalla/thor/presentation/security/BiometricScreen.kt:235-242`,
`app/src/main/java/com/valhalla/thor/data/security/BiometricHelper.kt:22-24`,
`app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt:338`

## Problem

`biometric_lock` lives in `thor_preferences.preferences_pb`
(`PreferenceRepositoryImpl.kt:49`), which is what both `backup_rules.xml` and
`data_extraction_rules.xml` allowlist. So it rides cloud backup **and** device transfer.

`SecurityViewModel` derives the whole auth state from that preference and nothing else:

```kotlin
val authState = combine(_biometricEnabled, _isSessionAuthenticated, _authError) { enabled, authenticated, error ->
    when {
        !enabled      -> AuthState.NotRequired
        authenticated -> AuthState.Unlocked
        error != null -> AuthState.Error(error)
        else          -> AuthState.Locked
    }
}
```

`biometricHelper.canAuthenticate()` — the one function that asks whether authentication is even
*possible* on this device — is consulted in exactly two places, both in Settings
(`SettingsViewModel.kt:96` and `:109`). **Nothing on the launch path consults it.**

`HomeActivity` renders the lock screen for both terminal states:

```kotlin
AuthState.Locked,
is AuthState.Error -> { BiometricScreen(...) }
```

So on a device where authentication cannot succeed, the loop is closed:

1. `authState` = `Locked` → `BiometricLockView`'s `LaunchedEffect(Unit)` fires `handler.authenticate()`.
2. The prompt fails immediately — `ERROR_NO_BIOMETRICS` / `ERROR_HW_UNAVAILABLE`.
3. `onAuthError` → `authState` = `Error` → `BiometricErrorView`.
4. "TRY AGAIN" → `onRetry()` clears `_authError` → back to `Locked` → `BiometricLockView` re-enters
   composition → `LaunchedEffect(Unit)` fires again → step 2.

The only other control on that screen is **EXIT**, which calls `finish()`. `MainScreen` is never
composed, so Settings is unreachable, so the switch that would turn the preference off
(`SettingsScreen.kt:338`, correctly guarded by `enabled = state.canUseBiometric`) **cannot be reached
by the user who needs it.** The guard is real and it is unreachable in precisely the case it exists for.

### When this actually happens

`BiometricHelper` allows `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, so a device with just a PIN or
pattern is fine. The lockout needs a device with **neither an enrolled biometric nor any screen
lock**. That sounds exotic until you notice *when* Android restores: during setup wizard, on a
brand-new or freshly-wiped device, **before** the user has been asked to set a screen lock. That is
the single most likely moment for this to fire.

Also reachable without any backup at all: enable the lock, then remove the screen lock in system
Settings. Same dead end.

## Options

1. **Make `authState` ask whether auth is possible.** Inject `BiometricHelper` into
   `SecurityViewModel` and treat "enabled but not capable" as `NotRequired` — the app opens, and the
   Settings switch is already disabled, so the user can see what happened. Smallest change, fails
   open. The objection is that it fails *open*: someone who deliberately removed their screen lock
   gets in. That is the correct trade for a preference this app cannot verify was set by this user on
   this device, but it is a security call, so it is the owner's.
2. **Same check, but self-heal.** As (1), and also write `biometric_lock=false` back so the state
   stops being contradictory. Marginally more code; means the user must re-enable it deliberately
   once they set a screen lock, which may be surprising.
3. **Keep it locked, add a real escape.** Leave the lock in force but give `BiometricErrorView` a
   "disable biometric lock" action when `canAuthenticate()` is false. Fails closed, and is honest
   about what it is doing — but an unauthenticated user turning off the lock is not obviously more
   secure than (1), it just looks like it.
4. **Exclude `biometric_lock` from backup.** Cannot be done cleanly: the ruleset allowlists whole
   files, and `biometric_lock` shares `thor_preferences.preferences_pb` with the 22 preferences that
   *should* restore. Splitting it into its own DataStore is a lot of moving parts to dodge one
   `if`, and it fixes nothing for the remove-your-screen-lock path, which needs no backup at all.

Option 1 is the smallest correct fix and covers both routes into the state. Option 4 is listed only
so nobody re-proposes it.

## Acceptance

- With `biometric_lock=true` in DataStore on a device with no enrolled biometric **and** no screen
  lock, Thor opens to `MainScreen` (or offers a working way out) instead of cycling
  `BiometricLockView` ↔ `BiometricErrorView`.
- A unit test on `SecurityViewModel` covering enabled-but-not-capable. The harness exists —
  `MainDispatcherRule` and `ViewModelTestDoubles` landed in the #16 batch, and `SecurityViewModel`
  already has behaviour tests, so this is a new case in an existing file, not new scaffolding.
- The restore path is exercised end to end at least once: `bmgr backupnow` with biometric lock on →
  `adb uninstall` → `adb install -t -r` → launch on a device with no screen lock. **`pm clear` +
  relaunch does not test this** — Android Auto Backup restores only at package *install* time, so a
  clear-and-relaunch never restores anything and will make a broken build look fine.
