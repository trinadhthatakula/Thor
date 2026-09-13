# Legacy target-SDK installation (TG-001)

Status: merged into `dev` via [PR #464](https://github.com/trinadhthatakula/Thor/pull/464)
(`50dd13c3`, 2026-09-10). The maintainer confirmed successful legacy APK installation on a physical
device through Root. The remaining device matrix and Store-policy review stay open; this record
does not establish a release.
Baseline: `dev` at `504e3418`, 2026-09-09.

## Request and boundaries

A Telegram user with a Galaxy S24 Ultra on Android 16 wants to install a target-23 APK using Shizuku.
This concerns Android's minimum **target SDK for new installs**, not an APK's `minSdkVersion`.

- Android 14 normally blocks targets below API 23. Android 15/16 normally block targets below API 24.
- The override is `pm install-create --bypass-low-target-sdk-block` on Root/Shizuku shell sessions.
- Normal, Dhizuku, and external installs cannot apply this override. Android below API 34 does not
  support this flag. OEM-specific package-manager restrictions can still differ.
- Signature, ABI, split consistency, version/downgrade, device compatibility, and Xiaomi system-app
  update restrictions are independent; this option does not bypass them or guarantee the app runs.

Official platform guidance: [Android 15 minimum target API level](https://developer.android.com/about/versions/15/behavior-changes-all#minimum-target-api-level).

## Implementation

The portable installer identifies APKs whose parsed target is below the known platform floor. The
target comes from the already-staged APK/selected bundle base, never untrusted sidecar metadata.
Unknown target SDK does not authorize a bypass.

**Maintainer amendment, 2026-09-09 UTC:** Settings → Installing now includes **Allow legacy APK
installs without asking**, off by default. The full setting title and security description remain
visible rather than being ellipsized. This replaces the first implementation's per-install checkbox.

- **Setting off:** tapping Install for an eligible Root/Shizuku APK opens a warning naming the app,
  package, target SDK, and platform floor. Nothing is installed until **Install this time** is
  accepted. Cancel leaves the installer ready and does not change the saved setting.
- **Setting on:** tapping Install automatically supplies the bypass for eligible APKs and skips the
  extra confirmation. The inline target-SDK warning remains, together with the saved-setting status.
- Turning the setting off restores the prompt on the next install. Its authoritative value is read
  for each attempt; an unreadable preference fails closed to asking, rather than assuming consent.

One-time approval never enables the setting. Confirmation IDs and selection revisions prevent stale
callbacks or delayed preference reads from authorizing another APK, mode, permission answer, or
dismissed sheet. An install attempt consumes one-time approval; retries require a new confirmation
unless the user has enabled the saved setting.

The warning explains that older apps can receive permissions under their legacy permission model.
Neither this setting nor confirmation enables the separate `-g` option. The setting applies to the
interactive installer, not background/headless restore/reinstall callers, whose default remains no
bypass. Normal/Dhizuku/external modes never receive it. Shizuku shell failure with an approved
override remains terminal and preserves the shell error; it cannot fall back to reflection/normal
installation that drops the selected option. Root retains its existing session path.

## Local verification

The saved-setting/confirmation-on-Install revision, including the Asgard compatibility correction
below, passed the following on 2026-09-09 UTC with JDK 21. These results supersede the earlier counts:

```sh
./gradlew --no-daemon test lintFossDebug lintStoreRelease assembleFossDebug \
  compileFossDebugAndroidTestKotlin --max-workers=1 --console=plain
```

- FOSS and Store: **2,727 tests each**, zero failures, errors, or skips (228 suites per variant).
- Lint: zero errors and zero `MissingTranslation` findings. Existing warning totals remain 66 for
  FOSS Debug and 53 for Store Release; dependency/toolchain hints also remain.
- FOSS Debug APK assembly and Android-test Kotlin compilation passed. Instrumentation tests were
  compiled, not executed.
- Coverage includes API-floor boundaries, parsed target `0` versus unknown, target metadata,
  default-off confirmation-on-Install, saved opt-in/no-repeat behavior, one-time approval without
  preference writes, cancel/retry behavior, stale confirmation IDs, delayed/failed/cancelled preference
  reads, duplicate taps, and persistence/catalog wiring. Command tests cover default-off flags,
  Root one-APK/split propagation, and terminal Shizuku failure.
- The new setting's full title and security description pass native-rendered Robolectric text-layout
  checks at 320dp width and 1.5× font scale, with one enabled, accessible switch. Installer warning
  and confirmation UI tests also pass. These are automated checks, not on-device UI acceptance.
- All eight locales contain the setting, saved-setting status, and confirmation text, with matching
  formatted placeholders.

### Home/Settings startup regression discovered during acceptance

The maintainer's subsequent launch log exposed a separate Asgard/Material 3 binary incompatibility:
`ConnectedButtonGroup` in Asgard 2.0.0 calls `ToggleButtonDefaults.toggleButtonColors`, which Material
3 `1.5.0-alpha27` renamed to `colors` without retaining the old binary method. The September 8
dependency update (`eada3844`) had undone the earlier alpha26 compatibility fix (`5479b643`). The
failure occurs in the Home header, before any legacy APK install is requested.

The catalog now restores `1.5.0-alpha26`; a strict runtime constraint prevents a BOM/transitive
dependency from silently raising it, and Dependabot excludes the exact known-broken alpha27 version.
Other versions remain eligible but must pass the new real-Asgard Home-header and Settings-picker UI
regressions. Both tests reproduced the reported `NoSuchMethodError` before the pin and passed after
it. The previous green suite did not render these controls, so it did not establish startup safety.
The full test/lint/build gates above passed again with this dependency fix, including both new UI
tests in each variant. On-device relaunch has not been performed by the agent.

### PR verification update — 2026-09-10

After syncing the feature branch with `dev` at `5c9672e6` (the web-only dependency updates in
[PR #463](https://github.com/trinadhthatakula/Thor/pull/463)), the combined legacy-installer,
settings-readability and crash-fix changes passed the required gates again:

```sh
./gradlew test lintFossDebug lintStoreRelease assembleFossDebug \
  --console=plain --no-parallel --max-workers=1 \
  '-Pkotlin.daemon.jvmargs=-Xmx4g -XX:+UseG1GC'
```

JDK 21 was used. The FOSS Debug and Store Debug reports each contain **2,731 tests across 229
suites**, with zero failures, errors or skips. Lint reports zero errors and zero `MissingTranslation`
findings; existing warning totals remain 66 for FOSS Debug and 53 for Store Release. These counts
supersede the earlier 2,727-test checkpoint. Gradle reused unchanged task outputs/cache entries.

The maintainer confirmed that all settings changes in the debug APK display and behave correctly on
a physical device. That confirmation covers the settings UI, not screenshot/recording/Recents
security testing or an exhaustive device/locale/font matrix. It preceded the separate physical Root
installation confirmation below.

### Merge and physical Root acceptance — 2026-09-10

[PR #464](https://github.com/trinadhthatakula/Thor/pull/464) merged into `dev` at
`50dd13c3173bd65f4050c30078a17fc4d4e2eb70` on 2026-09-10. GitHub merge status was verified.
The maintainer reported physical-device proof of successfully installing a legacy APK through Root.
This establishes a successful physical Root installation in addition to the earlier platform-only
emulator smoke test; it is maintainer-reported acceptance, not an agent-run device test.

The device/ROM, Android version, APK target/package, fresh-install versus update state, and exact
consent-setting path were not supplied with that confirmation. Do not infer coverage of the full
matrix below, Shizuku, the reporter's Samsung, unsupported-mode safeguards, or Store-policy approval.
The separate bulk Freeze/Suspend changes are not covered by this installation proof; their
acceptance is recorded in [QA-001](app-list-bulk-freeze-and-suspend.md).

### API-36 package-manager smoke test

A disposable, headless instance of `Thor_Root_API36` was used with snapshots disabled
for the initial implementation on 2026-09-09. This platform-only evidence was not repeated for the
subsequent setting/prompt revision.
Fingerprint: `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`.
The fixture was a purpose-built, signed APK with no code or requested permissions:
`com.valhalla.thor.test.legacytarget.tg001`, version 1, min SDK 23, target SDK 23.

For both verified shell UID 2000 and root UID 0, the streamed session sequence
(`pm install-create` → `cat ... | pm install-write -S ... -` → `pm install-commit`) gave:

| Session flags | Fresh-install result |
|---|---|
| `-r --user 0` | `INSTALL_FAILED_DEPRECATED_SDK_VERSION: App package must target at least SDK version 24, but found 23` |
| `-r --bypass-low-target-sdk-block --user 0` | `Success`; package presence confirmed with `pm path` |

Root identity was established with emulator `adb root`, not an Odin integration run. This verifies
the platform flag and streaming-session mechanism, **not** Thor's full UI/Odin/Shizuku integration
or a production OEM build. The fixture was uninstalled and the disposable emulator shut down without
saving its state. No app was installed on the connected physical phone.

## Device acceptance and remaining release checks

- [x] Physical-device legacy APK installation through Root: confirmed by the maintainer on
  2026-09-10 for PR #464. This is one successful path, not completion of the matrix below.

Use only known-safe, purpose-built APKs, and record device/ROM, build, privilege UID/mode, package,
target SDK, version code, exact error, and outcome. Use a clean test profile/package for fresh-install
checks: updates to an already-installed legacy app can have different platform behavior.

- [ ] Android 14: target 22 (and a valid parsed target-0 fixture if available) is blocked without
  consent and installable with an authorized override;
  target 23 needs no override.
- [ ] Android 15/16: target 23 is blocked by default and succeeds with consent through Root and
  ADB/wireless Shizuku. Repeat on the reporter's physical Android 16 Samsung device.
- [ ] Monolithic APK and a valid legacy split set use the same session flag; fresh install and update
  preserve their distinct behavior.
- [ ] With the setting off: cancel the warning, change mode, pick another APK, dismiss/reopen, and
  retry after failure. Each eligible attempt requires fresh confirmation; stale callbacks do nothing.
- [ ] Enable the setting manually: eligible installs skip the confirmation while retaining the
  inline warning. Disable it: the next eligible install asks again. Restart Thor to verify the
  saved setting persists; a failed setting write must not imply that it was saved.
- [ ] Normal/Dhizuku/external modes never apply the override. Disconnected Shizuku and a rejected
  shell install report failure without switching to another installer.
- [ ] Verify signing mismatch, ABI mismatch, and unrelated OEM refusal remain failures; check that
  permission grants are governed by Android's permission model and the separate grant option.
- [ ] Review Store-distribution policy and localized warning wording before release. No policy
  approval or additional physical-device acceptance is implied by the implementation or local tests.
