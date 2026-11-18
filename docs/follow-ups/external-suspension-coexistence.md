# Freezing apps paused by another app

Digital Wellbeing, device policy, and other managers can suspend an app independently of Thor.
The Android aggregate suspended flag means at least one owner remains. It cannot prove that Thor
added a suspension or that Thor's own entry was removed.

## Behavior

- **Freeze mode:** disable/hide first. Remove only a verified Thor suspension when converting modes.
  An existing external suspension is retained. A verified disable is the successful freeze result,
  even when suspension ownership cannot be read or an older entry cannot be safely removed.
- **Suspend mode:** add a separate Thor entry without first unsuspending the app. An existing
  verified Thor entry is idempotent, including after switching privilege modes. If converting from
  disable/hide, attempt enable/unhide only after Thor's suspension has been verified. A verified
  suspension remains successful and tracked even if this optional cleanup fails; cancellation is
  still propagated.
- **Unfreeze:** enable/unhide first, then remove verified Thor suspension entries. External owners
  remain in control of their restrictions. A known remaining external pause is reported as such,
  including in durable task details and the Quick Settings tile. Launch shortcuts do not attempt to
  launch through a remaining external pause. Removing recovery memberships also reports external
  pauses; profile removal rechecks live ownership before accepting a remaining external-only pause.
- **Watchlist toggle:** disabled/hidden apps and verified Thor suspensions are Unfreeze targets.
  A verified external-only pause is a Freeze target, subject to the normal safety policy. Unknown
  suspension ownership remains a recovery target; checking ownership never mutates a suspension.
- **Unknown ownership:** preserve the suspension. Restoration can still clear disabled/hidden state
  before reporting the unresolved suspension; the watchlist retains entries whose restoration could
  not be verified.

## Ownership and compatibility

Root uses Thor's application package identity and verifies the typed, user-scoped readback. Root
never falls back to impersonating Digital Wellbeing, `android`, or an unverified shared identity.

Shell-backed Shizuku uses the platform's shared `com.android.shell` entry. Thor attaches a random
per-package/per-user marker in launcher extras and checks that marker together with the owner
record. Another shell tool replacing that entry without Thor's marker makes it external again.
Launcher extras are read through the public LauncherApps API for Thor's Android user; the private
app-extras getter is restricted to the target app and is not used.

Older shared shell/root suspensions without the marker cannot be attributed to Thor safely. They
are preserved rather than automatically claimed. The marker is provenance, not a security boundary
against another privileged tool intentionally copying it.

Android 9/10 has a single suspension slot. Existing external suspension cannot be overlaid and is
left untouched. Android 9's shell-only suspension cannot carry a verifiable independent Thor
identity and is skipped. Dhizuku's shared device-owner suspension API cannot verify an independent
Thor entry and is also skipped with an explanation. During Unfreeze, Dhizuku preserves a confirmed
suspension with unknown ownership and reports it as remaining after enabling/unhiding; an unreadable
app state still fails verification. Freeze mode remains available; no automatic switch of the selected
mode occurs.

## Device validation (pending)

JVM/Robolectric tests verify policy decisions, mode conversion order, ownership failure handling,
user scoping, gateway readback, and persistence of the marker. They do not establish OEM behavior.

- [ ] Root on Android 11+: Digital Wellbeing timer pause → Thor Freeze → Thor Unfreeze. The timer
      pause remains, and Thor's disabled state clears.
- [ ] Root on Android 11+: timer pause → Thor Suspend → timer reset. Thor's entry remains until
      Thor Unfreeze; repeat with the timer still expired when Thor removes its entry.
- [ ] Shell Shizuku: repeat the independent Suspend cycle, verify launcher-extras ownership survives
      process death, and verify another shell tool replacing the entry loses Thor attribution.
- [ ] Multiple external owners and a work profile: only verified Thor entries in the requested user
      are changed. The other profile remains untouched.
- [ ] Android 9/10: attempting to overlay an external suspension is skipped; Freeze mode preserves it.
- [ ] Dhizuku: Suspend is skipped without mutation; Freeze and unhide recovery remain usable.
- [ ] Root/SDK/OEM ownership readback failure: Freeze can still track a verified disable; Unfreeze
      enables first, reports the unresolved pause, and retains a retry path.

The reported Instagram device details are still pending. These checks must be run against that
Android version, Thor privilege mode, and OEM before claiming the reported device issue is verified.
