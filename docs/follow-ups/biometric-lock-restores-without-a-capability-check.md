# Follow-up: a restored `biometric_lock=true` is a hard lockout with no in-app escape

**Status:** **FIXED in code, one acceptance item outstanding.** The closed loop is gone: the launch
path now consults `canAuthenticate()` and the user is given a working way out. See
[Resolution](#resolution) — the fix takes **none** of options 1/2/3, which stay open as the owner's
call. Surfaced while device-verifying follow-up #20 (backup rules), confirmed by reading the auth
path.
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

`BiometricHelper` allows `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, so on API 30+ a device with just a
PIN or pattern is fine, and the lockout needs a device with **neither an enrolled biometric nor any
screen lock**. That sounds exotic until you notice *when* Android restores: during setup wizard, on a
brand-new or freshly-wiped device, **before** the user has been asked to set a screen lock. That is
the single most likely moment for this to fire.

**On API 28-29 the bar is higher, and it is a platform quirk rather than a Thor choice** — see
[Resolution](#resolution). `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is not a *supported combination*
below API 30, so a PIN does not count there: the prompt is biometric-only, and a device with a screen
lock but no enrolled fingerprint is equally stuck.

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

## Resolution

**What shipped is option 5, which nobody wrote down: keep the lock closed, but stop lying about it.**

The acceptance criterion below says "Thor opens to `MainScreen` **(or offers a working way out)**".
The second clause is what this takes, because every one of options 1–3 changes what the lock *means*
— who gets in, and on whose say-so — and that is the security call this document reserves for the
owner. A way out does not need that call, so it could be built now.

`AuthState` gains `Unavailable`, reached only when the lock is on and `canAuthenticate()` is false.
`HomeActivity` renders `BiometricUnavailableScreen` for it: an honest explanation, an **OPEN SECURITY
SETTINGS** button that deep-links to `ACTION_BIOMETRIC_ENROLL` (falling back to security settings, then
the settings root), and **EXIT**. `HomeActivity.onResume` calls `SecurityViewModel.refreshCapability()`
*before* the Shizuku early-return, so the user who leaves, sets a lock, and comes back lands on a
prompt that can actually succeed — no restart, no reinstall.

Why `Unavailable` sits where it does in the `when`:

- **Above `Error`.** The prompt fails the instant it opens on such a device, so `error` is populated
  a moment later either way. An `Error` screen offers TRY AGAIN, and TRY AGAIN re-arms the prompt —
  that is the loop, restated. `refreshCapability()` also clears a stale error across the transition,
  or the user comes back to the exact complaint they just went and fixed.
- **Below `authenticated`.** Someone who unlocked and *then* removed their screen lock is not thrown
  out of a session they legitimately opened.

Supporting change: `canAuthenticate()`/`hasHardware()` were extracted from `BiometricHelper` onto a
new `AuthCapability` interface in `domain/repository`. `BiometricHelper` is a final class over
`BiometricManager` and `:app` carries no mocking library by design, so the seam had to be an
interface for the launch-path decision to be testable at all. `SettingsViewModel` now depends on
`AuthCapability` too; nothing about its behaviour changed.

### The API 28-29 trap, found in review

Promoting `canAuthenticate()` from "should the Settings toggle be enabled" to "should this user be
held at the door" made a latent bug load-bearing, and it very nearly shipped as a *second* hard
lockout on top of the one this document is about.

`BiometricHelper` asked `canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`. That is not a
supported *combination* below API 30 — androidx's `AuthenticatorUtils.isSupportedCombination`
rejects it outright for `SDK_INT` in `[P, Q]` and `canAuthenticate` returns
`BIOMETRIC_ERROR_UNSUPPORTED` without looking at the device at all. Against a
`== BIOMETRIC_SUCCESS` test that is indistinguishable from "nothing is enrolled", so
`canAuthenticate()` was **hard-false on every Android 9 and 10 device**, whatever hardware it had
and whatever the user had enrolled. Thor's minSdk is 28: these are shipped, supported devices.

Previously that only suppressed the Settings toggle. With `Unavailable` gating the launch path it
would have meant: any Android 9/10 user whose `biometric_lock=true` arrived by restore lands on a
screen telling them to set up a screen lock, sets one up, comes back — and is still stuck, forever,
because the question being asked can never be answered yes on their OS version. The old code would
have shown them a working prompt, since `BiometricPromptHandler` already makes the API split and
falls back to a biometric-only prompt below API 30.

Fixed by making the capability predicate ask what the prompt can actually offer:
`promptAuthenticators(sdkInt)` returns `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` on API 30+ and
`BIOMETRIC_STRONG` below, mirroring `BiometricPromptHandler` exactly, with
`PromptAuthenticatorsTest` pinning the split. Two consequences worth noting:

- It also fixes the pre-existing Settings bug — the biometric-lock toggle was disabled on all
  Android 9/10 devices, including ones with a working enrolled fingerprint.
- The `Unavailable` copy no longer names a cause. "No fingerprint and no screen lock" is false on
  API 28-29, where a PIN-only device is genuinely incapable; the string now says only that there is
  nothing to unlock with, and points at both remedies.

**Still the owner's call, untouched by this:** options 1 (fail open), 2 (self-heal by writing
`biometric_lock=false`) and 3 (in-app disable from the error screen). Each remains implementable on
top of what shipped — `AuthState.Unavailable` is exactly the branch any of them would hang off.
Nothing here pre-empts that decision; it only means the user is no longer trapped while it is
pending.

## Acceptance

- ~~With `biometric_lock=true` in DataStore on a device with no enrolled biometric **and** no screen
  lock, Thor opens to `MainScreen` (or offers a working way out) instead of cycling
  `BiometricLockView` ↔ `BiometricErrorView`.~~ Done via the "working way out" clause —
  `BiometricUnavailableScreen`.
- ~~A unit test on `SecurityViewModel` covering enabled-but-not-capable. The harness exists —
  `MainDispatcherRule` and `ViewModelTestDoubles` landed in the #16 batch, and `SecurityViewModel`
  already has behaviour tests, so this is a new case in an existing file, not new scaffolding.~~
  Done — five new cases in `SecurityViewModelTest`, plus `FakeAuthCapability` in
  `ViewModelTestDoubles`: enabled-but-not-capable, the recover-and-return transition, stale-error
  clearing, no eviction of an unlocked session, and no gate at all while the lock is off.
- **OUTSTANDING (needs a device).** The restore path is exercised end to end at least once:
  `bmgr backupnow` with biometric lock on → `adb uninstall` → `adb install -t -r` → launch on a
  device with no screen lock. **`pm clear` + relaunch does not test this** — Android Auto Backup
  restores only at package *install* time, so a clear-and-relaunch never restores anything and will
  make a broken build look fine.
