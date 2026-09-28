# Issue #519: Shizuku system-app freeze and restore

## Behavior

- Keep Binder `DISABLED_USER` and `pm disable-user --user N` as the initial Shizuku freeze paths.
- Settings → Freezer → **System app removal fallback** is on by default when no device-local choice exists. A saved off choice is never overridden. Re-enabling it after an opt-out requires a
  confirmation explaining removal for the current Android user, retained data files, possible
  account loss, application to manual/automatic freezes, and reinstalling on Unfreeze.
- The preference is in the non-backed-up local DataStore. Existing explicit off choices survive app updates.
  It is read when the operation executes; switching it off protects queued work too.
- Only an enabled, policy-refused **Shizuku system-app** disable may reach `pm uninstall -k
  --user N`. Generic transport failures, user apps, Root and Dhizuku cannot use this fallback.
- Unfreeze still restores legacy removed packages whether consent is on or off.
- Queue history retains stable disable-refused, disable-failed, and restore-failed reasons.
  Platform errors remain in diagnostic logs, with both shell streams retained for these commands.
- Root availability probes have separate provenance. Queue warnings derive from actual per-target
  root commands, never the global root lane state.
- Shizuku launches `sh` for both server identities. Child processes inherit the server UID; invoking
  an additional `su` was unnecessary and could fail when the root manager hides the executable.

## Device evidence — 2026-09-28

### Fresh API 37.2 emulator

AVD: `Thor_Issue519_API37_2`, Google Play 16 KB ARM64 image, build `CP41.260828.004.A7`.
Installed the user's `shizuku-v13.7.0-thedjchi(1).apk`, then started it through ADB (UID 2000).

On `com.android.egg`:

- `pm disable-user --user 0` succeeds: `installed=true`, `enabled=3`.
- `pm enable --user 0` succeeds.
- `pm uninstall -k --user 0` fails: `only root can delete system app for a particular user`.
- Opt-in instrumentation passed the actual Shizuku command path, gateway freeze/unfreeze and
  foreground queue freeze/unfreeze. No degraded-root warning. Original enabled setting restored.

This disproves a blanket Android 16+ prohibition on disabling system packages. On this API 37.2
build it is removal for a user, not disabling for a user, that is root-only.

### User's POCO F7, Xiaomi.eu

Android 16 / API 36, build `BP2A.250605.031.A3`. Authorized fixture:
`com.mfashiongallery.emag` (Mi Wallpaper Carousel), initially `installed=false`, `enabled=0`.
The user stopped root-backed Shizuku; the agent started it through ADB and verified UID 2000.

- `install-existing --user 0` succeeds.
- `disable-user --user 0` fails with exit 255 and `SecurityException: Cannot disable system packages`
  from Xiaomi's `PackageManagerServiceImpl.shouldRestrictEnabledSettingsChange`.
- `enable --user 0` and `uninstall -k --user 0` succeed.
- Production-gateway restoration of the old removed state succeeds with consent off.
- With consent off, freezing reports the typed refusal and leaves the package installed.
- With consent on, production foreground-queue freeze removes the package for user 0; queue
  unfreeze restores it and leaves it enabled. Neither operation reports root-lane degradation.
- Cleanup returned Carousel to `installed=false`, `enabled=0`; no unrelated package was mutated.

The exact ROM in issue #519 was not tested. The Xiaomi.eu reproduction supports the same vendor
restriction but does not establish that every reported unfreeze failure has the same cause.
No account-retention claim is made: the fixture was not an account provider.

## Repeatable live checks

`ShizukuFreezeIntegrationTest` is opt-in. Thor Debug must be authorized in Shizuku first.

- `shizukuFreezeTest=true`: disposable emulator Easter Egg round trip through shell, gateway, queue.
- `shizukuShellTest=true`: `id -u` through Thor must match the Shizuku server UID.
- `pocoCarouselTest=true`, `expectShizukuShell=true`: the specifically authorized Carousel test.
  Requires that package to start uninstalled for the current user and restores that state in finally.

Without these arguments the live tests skip. Do not run the Carousel test on someone else's device
without explicit consent for that package.

## Final validation

- `./gradlew test lintFossDebug lintStoreRelease`: passed under Zulu JDK 21.0.12.1.
  FOSS Debug: 3,091 unit tests; Store Debug: 3,091 unit tests; zero failures or skips.
  Neither lint report contains MissingTranslation warnings or SyntheticAccessor errors.
- Final debug and instrumentation APKs assembled and installed on the POCO and API 37.2 emulator.
  Shell identity and the applicable device round-trip test passed on each; the other device's
  opt-in fixture test was intentionally skipped.
- Manual emulator UI check: full confirmation text visible; Cancel leaves consent off, Confirm
  enables it, and switching off revokes it without another confirmation. Left off after testing.
- POCO cleanup verified again: Carousel `installed=false`, `enabled=0`. Shizuku remains in ADB mode
  as requested. The existing release Thor installation was not replaced by the debug APK.

## Default change

The initial implementation was opt-in. The subsequent product decision makes the fallback on by
default for an absent local key, including upgrades without a saved choice. Explicit false values
remain false; no freeze operation writes or re-enables the setting. The earlier device tests above
cover enabled and disabled behavior; preference regression tests cover the new default and opt-out
persistence. The screenshot shows the re-enable confirmation from the initial implementation.

After the default change, `test lintFossDebug lintStoreRelease :app:assembleFossDebug` passed
on JDK 21, with 3,093 unit tests per flavor and no test failures or errors.
