# Issue #522: suspended-app access and native Unpause

## Behavior

- Home → **Suspended** opens a dedicated list containing suspended user and system apps.
  The counter is hidden when zero. Its count matches this combined list.
- Search, sorting, grid/list view, single-app actions, bulk actions and queue navigation reuse the
  existing app-list controls. The fixed suspension filter and combined source cannot be changed
  from this screen. The ordinary Apps filter is preserved.
- Root and Shizuku suspensions request Android's native **Unpause app** action on Android 11+
  (API 30+). Android supplies the localized label, removes the selected suspension and continues
  the original launch. No version code change is included.
- Shizuku uses Binder first when suspending on API 30+, because `pm suspend` cannot configure this
  button. Shell remains a compatibility fallback. Unsuspend retains its existing owner-aware flow.
- Suspension overloads are looked up with ordinary reflection. Attempting a nonexistent overload
  through Bypass's Unsafe fallback caused a native ART crash on the Android 11 test image.

## Platform limits

- Android 10 and earlier do not support this native Unpause action. The in-app Unsuspend action
  remains available.
- Dhizuku uses DevicePolicyManager, which does not accept a SuspendDialogInfo. Android controls
  its policy notice; use Thor's Unsuspend action. This change does not impersonate another owner.
- Existing suspensions keep their original dialog until unsuspended and suspended again.
- An OEM may reject dialog customization or Binder suspension. The compatibility fallback can
  suspend without an Unpause button. Multiple suspension owners still require each owner to lift
  its own entry; the native button cannot promise to clear another owner's policy.

## Validation — 2026-09-30

### Emulators first

- `Thor_Issue522_API30`, Android 11 / Google APIs ARM64: shell-backed Shizuku suspension, native
  Unpause click, actual FLAG_SUSPENDED removal and fixture restoration passed.
- `Thor_Issue522_Dhizuku_API36_1`, Android 16 / Google APIs 16 KB ARM64: Dhizuku was configured as
  device owner on a new disposable emulator. Suspension, the native "Blocked by work policy"
  notice, gateway Unsuspend and fixture restoration passed.
- The Android 16 emulator also passed shell-backed Shizuku's native Unpause round trip.
- Calendar was the initially active launchable emulator fixture. The dedicated list displayed it
  even though it is a system app. In-app Unsuspend updated the open detail sheet from Unsuspend
  to Suspend and removed the row. Back navigation and landscape rendering were checked.
- Root was not validated on the Android 11 emulator: its built-in su is restricted to shell.

### Physical device

- POCO F7 (`onyx`), Android 16 / API 36, build `BP2A.250605.031.A3`.
- A new disposable, code-free launcher fixture `com.valhalla.thor.suspendfixture` was installed;
  no existing personal app was used as the physical-device mutation target.
- Shizuku was verified as UID 2000. Real gateway suspension, native Unpause click and removal of
  the suspension flag passed.
- The first Root attempt timed out binding because Root access was not yet granted to the debug
  app. After the user granted access, the real Root gateway/native Unpause round trip passed.
- Home's Suspended count and dedicated list displayed the fixture; it was unsuspended after the
  list check. Final automated checks passed continued launch after Unpause in both modes.
  The disposable fixture was uninstalled afterward; the updated Thor Debug build remains installed.

### Regression coverage

- AppListViewModel regression mixes suspended user/system apps with active and disabled apps,
  checks the combined view and unchanged ordinary filters, and confirms live removal after resume.
- `SuspendedAppIntegrationTest` explicitly opts into a provider and initially unsuspended,
  launchable fixture. It verifies suspension through the real gateway, native UI action (Root and
  Shizuku on API 30+), continued launch, and finally restoration. Dhizuku verifies its admin notice
  and gateway Unsuspend instead.
- Required local gates passed: `./gradlew test lintFossDebug lintStoreRelease` with Zulu JDK 21.
  No MissingTranslation warnings or bypass SyntheticAccessor errors. Debug and instrumentation
  APK assembly passed. Existing compiler deprecation/opt-in warnings remain.
- Live UI checks were run in English. Native labels are supplied by Android in other locales;
  the opt-in instrumentation currently matches the English Unpause/admin text.

## Repeatable live checks

Install the Foss debug app and its androidTest APK, grant the selected provider access, then run:

```bash
adb -s DEVICE shell am instrument -w \
  -e class com.valhalla.thor.data.source.local.SuspendedAppIntegrationTest \
  -e suspendDialogMode shizuku \
  -e suspendDialogTarget YOUR_DISPOSABLE_LAUNCHABLE_PACKAGE \
  com.valhalla.thor.debug.test/com.valhalla.thor.ThorTestRunner
```

Use `root` or `dhizuku` for the other modes. Without a provider argument the test is skipped.
The default fixture is emulator Calendar; always supply a disposable target on physical devices.
