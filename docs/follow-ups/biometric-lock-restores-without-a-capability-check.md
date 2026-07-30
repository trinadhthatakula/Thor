# Follow-up: a restored `biometric_lock=true` is a hard lockout with no in-app escape

**Status:** **FIXED.** The closed loop is gone everywhere. On API 29+ the user is shown a screen that
explains itself and deep-links to the enrolment that clears it; on API 28 with no biometric hardware
— where nothing they could enrol would ever satisfy the prompt — Thor now **refuses to arm the lock
and disarms one that arrived anyway**, which is the owner's decision of 2026-07-30. See
[Resolution](#resolution) and [the API 28 residual, and how it was closed](#residual-api-28-with-no-enrollable-biometric).
What shipped is **option 2, scoped to the unrecoverable case only**; options 1, 3 and 5 stay
unimplemented and are no longer needed. Surfaced while device-verifying follow-up #20 (backup rules),
confirmed by reading the auth path.
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
(`SettingsScreen.kt:338`, guarded at the time by `enabled = state.canUseBiometric`) **cannot be
reached by the user who needs it.** The guard is real and it is unreachable in precisely the case it
exists for. (That guard has since moved into `SettingsViewModel` as a refusal with a toast — see
[the owner's decision](#owners-decision-2026-07-30-and-what-it-turned-into) — but the reachability
problem it describes is the same one.)

### When this actually happens

From Android 10 up the prompt takes the device credential, so a device with just a PIN or pattern is
fine and the lockout needs a device with **no enrolled biometric the prompt accepts *and* no screen
lock**. That sounds exotic until you notice *when* Android restores: during setup wizard, on a
brand-new or freshly-wiped device, **before** the user has been asked to set a screen lock. That is
the single most likely moment for this to fire.

**On Android 9 the bar is higher, and it is a platform limit rather than a Thor choice** — see
[Resolution](#resolution). API 28's framework prompt has no device-credential path at all
(`setDeviceCredentialAllowed` arrives in Q), so a PIN does not count there: the prompt is
biometric-only, and a device with a screen lock but no enrolled fingerprint is equally stuck.

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

Fixed by making the capability predicate ask what the prompt can actually offer. It also fixes the
pre-existing Settings bug — the biometric-lock toggle was disabled on all Android 9/10 devices,
including ones with a working enrolled fingerprint.

### Android 10, found in the review of that fix

The first cut of `promptAuthenticators` asked `BIOMETRIC_STRONG` on **both** 28 and 29, on the
grounds that neither supports the combination. True of the *question*, false of the *prompt*:
Android 10 has `setDeviceCredentialAllowed`, the deprecated ancestor of `DEVICE_CREDENTIAL`. So the
escape hatch told an Android 10 user to set a screen lock, and setting one changed nothing — the
same dead end this document exists to remove, one API level narrower.

What ships now is three tiers, with the prompt and the predicate cut at the same versions:

| API | prompt built by `BiometricPromptHandler`     | asked of `BiometricManager`         |
|-----|---------------------------------------------|-------------------------------------|
| 30+ | `setAllowedAuthenticators(STRONG or CREDENTIAL)` | `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` |
| 29  | `setDeviceCredentialAllowed(true)`          | `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`   |
| 28  | biometric-only, Cancel button               | `BIOMETRIC_STRONG`                      |

The `WEAK` on 29 is not a weakening of what unlocks Thor — the Q prompt still accepts a strong
biometric or the credential — it is the only combination androidx will *answer* below R. Nor can it
be over-permissive in practice: Android refuses to enrol a biometric without a backup credential, so
"a credential is set" is implied by every enrolled biometric anyway.

`PromptAuthenticatorsTest` pins each tier and, more importantly, pins that the set of levels which
*ask* about a credential is exactly the set whose prompt *accepts* one. Those two halves living in
different source files is how the first version drifted.

The `Unavailable` copy is now chosen by that same boundary rather than being written to be vague:
Android 10 and up gets "set up a screen lock or enroll a fingerprint", Android 9 gets a string that
says a screen lock will not do it there and to enrol a fingerprint.

### Residual: API 28 with no enrollable biometric

An **Android 9 device with no biometric hardware at all** had no way in. The unavailable screen was
honest with them — it says a screen lock will not do it there — but honest is not the same as
unstuck: nothing they can do in system Settings satisfies a biometric-only prompt. Once there, the
remaining path was EXIT and clearing Thor's data.

There is one route that is *not* in options 1–3, and it is worth writing down because "unfixable
without the owner's call" overstated it — the platform does have a credential path on API 28:

5. **Confirm the device credential directly on API 28.**
   `KeyguardManager.createConfirmDeviceCredentialIntent()` (API 21, deprecated at 29 in favour of
   `BiometricPrompt`) shows the system PIN/pattern/password screen and returns a result. It is
   exactly what androidx's `BiometricPrompt` launches for the credential path on 28–29, which is why
   `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` is a combination androidx will answer at every API level.
   Thor calls the *framework* prompt rather than androidx's precisely because androidx needs a
   `FragmentActivity` and `HomeActivity` is a `ComponentActivity`, so the direct intent — behind an
   activity-result launcher on the unavailable screen — is the cheaper of the two ways to get there.

   Not taken. It does not open the lock, it makes a PIN satisfy it — but it *is* a change to what
   unlocks Thor on a shipped OS version, for someone who set the lock when only a fingerprint would
   do. And it still leaves a device with neither a credential nor biometric hardware stuck, so it
   would have closed the common half of the residual and left the hard half open.

#### Owner's decision (2026-07-30), and what it turned into

> "API 28 with no enrollable biometric will not be able to turn on biometric, we will just not allow
> him to turn on biometric and show them a toast saying biometric not enrolled. If the setting is
> restored from backup we do the same and turn off the setting ourself."

That is **option 2** — self-heal by writing `biometric_lock=false` — plus a refusal on the way in.
It ships **asymmetrically**, because the sentence is scoped to *"API 28 with no enrollable
biometric"* and the two halves have different costs if applied too widely:

- **Refusing to arm is universal.** `SettingsViewModel.setBiometricLock(true)` is refused on *any*
  device where `canAuthenticate()` is false, and answers with a toast naming what is missing.
  Refusing costs the user nothing: they are standing in Settings, they can go enrol and come back.
  The switch is no longer `enabled = state.canUseBiometric` — a disabled row swallows the tap
  (`clickable(enabled = false)`), so the old behaviour was a greyed-out control and silence, and
  there was no way to *answer* a tap that never arrived.
- **Disarming is confined to the unrecoverable case** — `enrolmentCanFixLockout(SDK_INT, hasHardware)`
  false, i.e. API 28 with no sensor. Everywhere else the user can enrol their way in, and the shipped
  `AuthState.Unavailable` screen takes them there. Silently dropping a lock the user can still open
  would be a security downgrade dressed up as a bug fix, so the wider reading of the instruction was
  not taken.

`enrolmentCanFixLockout` is a named predicate in `BiometricPromptHandler.kt` with its own test rather
than a condition inlined into the view model, because it is wrong in opposite directions: a `true`
leaked in strands a user at a prompt that can never succeed, a `false` leaked out hands Thor
permission to switch off a lock the user could still have opened. It asks about sensor *presence*,
not enrolment — a sensor with nothing enrolled is the recoverable case precisely because enrolling on
it is the fix.

The disarm is driven off the preference flow in `SecurityViewModel.init`, not checked once, because
the preference is read asynchronously: `_biometricEnabled` seeds `false` and the restored `true`
lands a moment later, which is exactly the case this exists for. Writing `false` flips that flow, so
it settles after one pass. A matching branch in the `authState` `when` (`!capable && !enrolmentCanFix
-> NotRequired`) keeps a frame of the dead-end screen from rendering while the write lands.

It is not silent: `SecurityViewModel` emits a `UiText` on a buffered `Channel`, collected by
`HomeActivity` — buffered, not `replay = 0`, because the disarm fires during `onCreate`, before any
collector reaches STARTED. Thor removed a security control the user deliberately switched on; saying
so is the minimum.

**Options 1 (fail open unconditionally), 3 (in-app disable from the error screen) and 5 are now
moot** — the case each of them existed to rescue no longer occurs.

## Acceptance

- **DONE.** With `biometric_lock=true` in DataStore on a device with **no enrolled biometric that
  `promptAuthenticators(SDK_INT)` accepts** and **no screen lock** — both, since on API 30+ either
  one alone still unlocks — Thor no longer cycles `BiometricLockView` ↔ `BiometricErrorView`; it
  shows `BiometricUnavailableScreen`, which satisfies the "working way out" clause because setting a
  screen lock or enrolling a biometric gets the user in.
- **DONE.** On API 28 with no biometric hardware — the one device where no enrolment could ever
  satisfy the prompt — Thor writes `biometric_lock=false`, opens to `MainScreen` and says so with a
  toast, and the Settings switch refuses to arm it again while the device stays that way.
- ~~A unit test on `SecurityViewModel` covering enabled-but-not-capable. The harness exists —
  `MainDispatcherRule` and `ViewModelTestDoubles` landed in the #16 batch, and `SecurityViewModel`
  already has behaviour tests, so this is a new case in an existing file, not new scaffolding.~~
  Done — eight new cases in `SecurityViewModelTest`, plus `FakeAuthCapability` in
  `ViewModelTestDoubles`: enabled-but-not-capable, the recover-and-return transition, stale-error
  clearing, no eviction of an unlocked session, no gate at all while the lock is off, and the three
  covering the disarm — that it happens and is *written*, that it is announced, and that a lock the
  user could enrol their way into is left alone. `Build.VERSION.SDK_INT` reads 0 under the mockable
  android.jar, so every one of them runs the API-28-shaped branch; that is also why
  `FakeAuthCapability.hardware` defaults to `true`, or the pre-existing cases would have silently
  switched branch. `onlyApi28WithNoSensorIsBeyondEnrolment` in `PromptAuthenticatorsTest` pins the
  predicate itself across 28–36.
- **OUTSTANDING (needs a device).** The restore path is exercised end to end at least once:
  `bmgr backupnow` with biometric lock on → `adb uninstall` → `adb install -t -r` → launch on a
  device with **no screen lock and no enrolled biometric**. "No screen lock" alone does not reach
  `Unavailable` on API 30+, and a device that has a fingerprint enrolled has a screen lock by
  construction, so the target device has to have neither. **`pm clear` + relaunch does not test
  this** — Android Auto Backup
  restores only at package *install* time, so a clear-and-relaunch never restores anything and will
  make a broken build look fine.
