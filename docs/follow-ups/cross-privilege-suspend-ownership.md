# Follow-up: cross-privilege suspend ownership (root ⇄ Shizuku can't cross-unsuspend)

**Status:** IMPLEMENTED in PR #330 — **but not verified on a device.** The design question this doc
was filed to answer is settled; what remains is the hardware pass described at the bottom. Do not
close this row until that pass runs.
**Area:** Freezer "Suspend" mode · `SystemGateway` backends · GH#239 lineage.

## Symptom

An app **suspended under one privilege mode could not be unsuspended under another**, and Thor
**reported every such unsuspend as a success**.

- App suspended via **Root** → could not be unsuspended while in **Shizuku** mode.
- App suspended via **Shizuku** → could not be unsuspended while in **Root** mode.

Dhizuku, being the Device-Owner path, is a third distinct owner with the same class of problem.

## Root cause

Two separate things, and the second is why nobody noticed the first.

**1. The recorded suspender is not stable across privilege modes.**

| Mode | Suspender identity recorded by the OS |
|------|----------------------------------------|
| Root | `com.valhalla.thor` |
| Shizuku / Dhizuku | `com.android.shell` |
| Device owner (DPM) | `android` |
| Pre-GH#239 builds, API < 28 root path | `root` (a non-existent package) |

**2. ⚠️ Whether that matters is version-dependent.** The original filing stated flatly that "Android
only lets the suspending package lift a suspension". That is **false below API 30** — `setSuspended(false)`
clears the single slot regardless of who set it ([android-9.0.0_r1 `PackageSettingBase.java:399-407`](https://cs.android.com/android/platform/superproject/+/android-9.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/PackageSettingBase.java;l=399-407)).
It became true **from API 30**, when `removeSuspension(callingPackage)` started removing only the
caller's own entry ([android-11.0.0_r1 :443-452](https://cs.android.com/android/platform/superproject/+/android-11.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/PackageSettingBase.java;l=443-452)).

**3. The failure was silent by construction.** Lifting a suspension you do not own finds no entry to
remove, so `oldSuspendParams == newSuspendParams == null` → `changed` stays `false` →
PackageManagerService logs *"No change is needed"* → the package is **never added to
`unmodifiablePackages`** → the API returns an **empty failure array**. Every caller reads an empty
array as total success. Thor did too.

**An empty result means "unknown", never "nothing was wrong."**

## What shipped (PR #330)

Not option 1, 2 or 3 below — a fourth: **stop inferring the owner and read it.**

- `domain/model/SuspenderReadback.kt` · `parseSuspendingPackages()` parses the recorded suspender set
  out of `dumpsys package <pkg>`. Four line shapes (API 28 inline `dialogMessage=`, 29 inline
  `dialogInfo=`, 30–34 `Suspend params:` block, 35–37 the same block keyed by `UserPackage.toString()`
  = `"<0>com.android.shell"` — that last one silently poisons a regex written for 30–34).
- `canLiftSuspension(recordedSuspender, isRoot, sdkInt)` encodes the API-30 split above rather than
  assuming the modern rule.
- **Root** issues one removal per recorded owner, *under that owner's name* — it may, because
  `enforceCanSetPackagesSuspendedAsUser` unconditionally early-returns for `Process.ROOT_UID` before
  any suspender-name validation. **Shell cannot**, so a shell-uid Shizuku facing a root-recorded
  suspension on API 30+ now **refuses and names the owner** instead of returning success.
- An unreadable dump is never "nothing to do": `readSuspenders()` returns `null` unless the output
  actually contained a `Package [<pkg>]` block, and `null` fails closed everywhere. This is
  load-bearing — `dumpsys` needs `android.permission.DUMP`, so a permission-denied read parses as
  *the empty set*, which is the identical silent-success bug one level down.
- **Dhizuku deliberately has no readback** — it runs as the device-owner app, not shell, so no dump it
  may take exists. It gates every success on `ApplicationInfo.FLAG_SUSPENDED` instead and fails closed
  when that is unreadable. Do not add a dump there.

The three approaches this doc originally proposed are recorded below for the record; all three
inferred the owner instead of reading it, which is why none was taken.

<details>
<summary>Original candidate approaches (superseded)</summary>

1. **Dual-owner unsuspend everywhere** — clear *all* known suspender identities from every path.
   Fails on 30+ from a shell uid, which may not name another package.
2. **Standardize the suspender identity** across backends. The OS validates that the calling UID owns
   `callingPackage`, so no single identity is reachable from every backend.
3. **Persist the suspending mode per package** and route unsuspend through that backend. Carries
   state that goes stale the moment anything else suspends the app.

</details>

## ⚠️ Verification — still outstanding

**No device testing has been done.** Everything above rests on AOSP source reading and 17 unit tests
over captured dump shapes. The parser is pinned against fixtures written from the documented formats,
**not against output from a real device.**

On a device: suspend an app in Root mode → switch to Shizuku mode → unsuspend → confirm it actually
unsuspends, or refuses with the owner named. Then the reverse. Repeat for Dhizuku.

Priority order, highest risk first:

- **API 35+ `<0>`-keyed shape** — the one most likely to be wrong, because the `UserPackage.toString()`
  prefix is the newest change and the tests pin it against a hand-written fixture.
- **The cross-privilege refusal path** — that a shell-uid Shizuku facing a root-recorded suspension on
  30+ refuses *and names the owner*, rather than refusing generically or silently succeeding.
- **API < 30** — that Thor correctly *allows* the cross-privilege unsuspend there instead of refusing
  it. A wrong refusal is as much a bug as a wrong success, and this is the branch with no real-device
  evidence at all.
- **A dump Thor is not allowed to read** — confirm it fails closed rather than reading as "not
  suspended".
