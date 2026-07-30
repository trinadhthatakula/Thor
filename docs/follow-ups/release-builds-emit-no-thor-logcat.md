# Follow-up: release builds emit no Thor logcat at all, including errors

**Status:** OPEN, undecided. Surfaced while building and measuring a release APK for the #22
cold-start comparison (2026-07-30).
**Severity:** Minor-to-moderate, and **narrower than it first looks** — see "What this is not" before
acting on it. Crashes are still diagnosable; non-fatal failures are not.
**Effort:** depends entirely on the option. One `Logger.isDebug` assignment if the whole flag flips;
a `thor-extension-api` release if only errors are wanted (see option 2). Plus, either way, a decision
about what "release" should mean.

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
   debug-only. Smallest useful change *in principle*: the failure paths become visible, the chatty
   ones stay quiet. Two costs, and the first is easy to miss:
   - **It is not a change Thor can make.** `Logger`'s single `isDebug` flag gates all five levels
     together, and `Logger` ships in `com.trinadhthatakula:thor-extension-api` (pinned at **3.0.0**
     in `gradle/libs.versions.toml:34`), not in this repo. Per-level gating means a new API surface
     — a `minLevel`, or separate flags — released as a new artifact version and adopted here.
     Publishing to Central is irreversible, and the flag is contract with third-party extensions, so
     this is a versioned API decision, not a line of app code.
   - Then an audit that no `Logger.e` call site interpolates a package list or a raw command —
     several probably do. A review pass on top of the API change, not instead of it.
   If the API change is unwanted, the only in-repo variant of this option is flipping the whole flag
   for release, which broadens the privacy audit from `e`/`w` to all 149 sites and gives up the
   quiet-by-default property that made option 2 attractive.
3. **A user-toggled debug-logging preference.** Off by default, surfaced in Settings, sets
   `Logger.isDebug = true` for the session. Turns "send me a logcat" into a supportable request
   without leaking by default. Costs a preference, a Settings row, and the same call-site audit as
   (2) if it is to be safe to share.
4. ~~**A separate `benchmark`/`diagnostic` build type.**~~ **This already exists** — added
   2026-07-30 for #22. `storeBenchmark` is `initWith(release)` with
   `buildConfigField("boolean", "PRIVILEGE_TRACE", "true")`, and `ThorApplication` sets
   `Logger.isDebug = BuildConfig.DEBUG || BuildConfig.PRIVILEGE_TRACE` — both switches, because
   `initWith(release)` alone is **not** enough: the flag reads `BuildConfig.DEBUG`, which stays
   false. So a full-logging release-shaped build is one `./gradlew assembleStoreBenchmark` away
   today. It is confined to the store flavour and never distributed, which means it solves
   *reproduction*, not *field diagnosis* — you cannot ask a user to install it. It does not close
   this item.

Option 2 removes the actual gap most precisely but is **not** the cheapest — it reaches outside this
repo into a published artifact. Option 3 is the cheapest thing that is entirely in Thor's hands, and
buys the same diagnosis by asking the user to opt in rather than by changing what release means.
Option 4 has already been built for another reason and closes nothing here. So the remaining
question is what a **shipped** build should say, and whether answering it is worth an
`thor-extension-api` version.

## Acceptance

- Whichever option is chosen, a release-shaped build reproduces a **deliberately failed** privileged
  operation (e.g. revoke root, then freeze an app) and the failure is either visible in logcat or
  explicitly accepted as invisible in the doc's closing note.
- If (2) or (3): every `Logger.e`/`Logger.w` call site reviewed for package names, user identifiers
  and raw shell commands before it is allowed to reach a release build.
- Verify against the **dex**, not the source — `Logger` is an external artifact and its gating can
  change under a version bump without anything in this repo moving.
