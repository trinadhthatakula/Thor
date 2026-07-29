# Follow-up: release builds emit no Thor logcat at all, including errors

**Status:** OPEN, undecided. Surfaced while building and measuring a release APK for the #22
cold-start comparison (2026-07-30).
**Severity:** Minor-to-moderate, and **narrower than it first looks** — see "What this is not" before
acting on it. Crashes are still diagnosable; non-fatal failures are not.
**Effort:** small — one `Logger.isDebug` assignment, plus a decision about what "release" should mean.

Files: `app/src/main/java/com/valhalla/thor/ThorApplication.kt:67`

## What was found

Thor makes **zero direct `android.util.Log` calls** in `app/src/main/java` — every one of its 149
logging sites goes through `com.valhalla.thor.extension.api.Logger`. That type lives in the
published `thor-extension-api` artifact, and it gates **all five levels — `v`, `d`, `i`, `w` and
`e`** — on a single `isDebug` flag. Thor sets it once:

```kotlin
com.valhalla.thor.extension.api.Logger.isDebug = BuildConfig.DEBUG
```

`BuildConfig.DEBUG` is `false` in release, R8 constant-folds the assignment, and the flag is never
written again. So a shipped Thor emits nothing to logcat under any tag it owns — not warnings, not
errors, not the gateway failure paths. This was confirmed against the **shipped release bytecode**,
not just the source: every `Logger` method body is `isDebug`-guarded in the release dex, and
`Shell.enableVerboseLogging` is likewise wired to `BuildConfig.DEBUG`.

The one app-side log path that survives is Odin's own `Utils.err`, which is ungated and prints under
tag `LIBSU`.

## What this is not

An earlier framing of this — "release builds are undiagnosable" — is **wrong, and should not be
repeated in a bug report.** Thor installs no custom `UncaughtExceptionHandler`, so a crash still goes
through the platform default handler and appears in logcat as a normal `AndroidRuntime FATAL
EXCEPTION` with a full stack trace, obfuscated names resolvable through `mapping.txt`. Play Console
crash reporting is unaffected. Anything that *kills* the app is still visible.

The real gap is narrower: **non-fatal failures are silent.** A gateway command that returns a
non-zero exit code, a Dhizuku binding that never completes, a preference write that throws and is
caught, a privilege probe that times out — all of these are handled paths that log and continue, and
in release they log nothing. When a user reports "freeze doesn't work on my device", there is no
artifact to ask them for. That is the cost, and it is worth stating precisely rather than
dramatically.

## Why it might be deliberate

Silence in release is a defensible default, and may well be the intent:

- Logcat is world-readable to anyone with adb, and Thor's logs carry package lists, privilege state
  and command strings. A privileged app that narrates its shell commands is leaking a map of the
  device.
- `Logger.isDebug` is API surface shared with extensions, so flipping it changes third-party
  behaviour too, not just Thor's.
- Nothing has actually been blocked on this yet — it is a hypothetical debugging cost, not a
  reported one.

So this is filed as a decision, not a defect.

## Options

1. **Leave it.** Accept that field diagnosis happens through crash reports and reproduction, not
   logs. Zero work, zero risk, and honest if the privacy argument is the one that matters. If this is
   the answer, delete this doc.
2. **Let errors through in release.** Ungate `Logger.e` (and possibly `w`) while leaving `v`/`d`/`i`
   debug-only. Smallest useful change: the failure paths become visible, the chatty ones stay quiet.
   Requires an audit that no `Logger.e` call site interpolates a package list or a raw command —
   several probably do, so this is a review pass, not a one-liner.
3. **A user-toggled debug-logging preference.** Off by default, surfaced in Settings, sets
   `Logger.isDebug = true` for the session. Turns "send me a logcat" into a supportable request
   without leaking by default. Costs a preference, a Settings row, and the same call-site audit as
   (2) if it is to be safe to share.
4. **A separate `benchmark`/`diagnostic` build type.** `initWith(release)` plus
   `isDebuggable = true`, its own `buildConfigField`, and an explicit `Logger.isDebug = true` — note
   that `initWith(release)` alone is **not** enough, because the flag reads `BuildConfig.DEBUG`,
   which stays false. This is also what #22 needs to measure a release-shaped build's privilege
   probe, so options (4) and the #22 work share an implementation. Do not build it for logging alone.

Option 2 is the smallest thing that removes the actual gap. Option 4 is the one to reach for if #22's
measurement work goes ahead, since it pays for itself twice.

## Acceptance

- Whichever option is chosen, a release-shaped build reproduces a **deliberately failed** privileged
  operation (e.g. revoke root, then freeze an app) and the failure is either visible in logcat or
  explicitly accepted as invisible in the doc's closing note.
- If (2) or (3): every `Logger.e`/`Logger.w` call site reviewed for package names, user identifiers
  and raw shell commands before it is allowed to reach a release build.
- Verify against the **dex**, not the source — `Logger` is an external artifact and its gating can
  change under a version bump without anything in this repo moving.
