# System app UAD filter validation

Validated 2026-10-04 on `feat/system-app-uad-filter`, based on `dev` at
`750ffd5ea683ccee6b1e60baeb86b08402cda0a1` (PR #554). The tested implementation
is recorded by the file hashes in the artifact directory below. No version bump.

## Behavior

- System apps offer **UAD recommendation** with All, Recommended, Advanced,
  Expert, Unsafe, and Unknown. User apps do not expose this category.
- The selected category and chip persist separately for User and System apps.
  Switching tabs restores each profile. The tab itself remains session-only.
- Four scoped preference keys inherit the old shared pair until that profile is
  first changed. Legacy keys remain read-only, so changing one tab cannot change
  the other tab's fallback. Invalid categories/selections normalize together.
- Unknown means a successful lookup without recognized advice, including custom
  extension tags. Loading and failed metadata are not Unknown. Recognized tags
  use the existing case-insensitive handling; extra whitespace remains unknown.
- The initial Room snapshot stays fast and un-enriched. Subsequent snapshots
  enrich both observed and retained cache rows. Scan failures preserve available
  cached rows without leaving metadata loading forever; an empty failed scan
  follows the load-error path. All remains available during UAD loading/failure.
- Sorting, layout, search, and action protections retain their existing behavior.
  Recommended is UAD's label, not a guarantee that removing an app is safe.
  The suspended-only view does not overwrite either saved filter profile.

## Host checks

The final debug build and 109 focused tests passed. The complete required gate
also passed with the repository's Java 21 daemon configuration and published
dependencies, without local composite overrides:

```sh
./gradlew --max-workers=1 :app:assembleFossDebug :app:assembleFossDebugAndroidTest
./gradlew --max-workers=1 test lintFossDebug lintStoreRelease
```

- **3,584 tests per debug flavor**, zero failures, errors, or skips.
- FOSS Debug lint: 9 hints; Store Release lint: 8 hints. **Zero errors/warnings**,
  including no MissingTranslation or SyntheticAccessor findings.
- New regression coverage includes legacy migration, scoped atomic writes,
  queued edits across tabs, installer shortcuts, permission-index cancellation,
  suspended-view isolation, UAD classification, cache enrichment, terminal load
  failures, and recovery. All nine locale resource files include the new labels.
- `git diff --check` passed. The primary `dev` checkout remains clean and synced.

## Devices

Both targets ran the same hash-verified FOSS debug app and test APKs.

| Target | Instrumentation | Real Apps screen |
| --- | --- | --- |
| Odin Magisk emulator, Android 16/API 36 (`emulator-5582`) | 6/6 passed, no skips | Tab restoration and cold restart passed |
| POCO F7 / ReSuKiSU, Android 16/API 36 (`1da5425f`) | 6/6 passed, no skips | Tab restoration and cold restart passed |

The two integration tests compare every installed system app's filter membership
against the real UAD snapshot, check detail metadata, and exercise production
preference persistence through repository recreation. The four Compose tests
cover system-only visibility, all five chip values, the invalid User/UAD guard,
and loading/failure feedback with All still usable.

| UAD category | Emulator | Physical device |
| --- | ---: | ---: |
| Recommended | 31 | 100 |
| Advanced | 57 | 90 |
| Expert | 53 | 80 |
| Unsafe | 39 | 60 |
| Unknown | 74 | 27 |
| **Total system apps** | **254** | **357** |

In the real app UI, User/State/Frozen and System/UAD/Recommended each survived
switching away and back. After force-stopping and cold-launching Thor debug,
the User tab restored Frozen, and the System tab restored Recommended. UAD chips
were absent from User apps. Screenshots were visually checked in the emulator's
light theme and the phone's dark theme.

Screenshots of the Recommended filter:
[Magisk emulator (light)](../images/system-app-uad-filter/magisk-recommended.png)
and [ReSuKiSU phone (dark)](../images/system-app-uad-filter/resukisu-recommended.png).
System status and navigation bars are cropped from these documentation copies.

Both profiles were restored to their original Source/All selections afterward;
legacy preference values stayed unchanged. No app freeze, uninstall, root-policy
change, `adb root`, or physical-device reboot was required. Only Thor debug and
its test APK were updated.

## Evidence and limits

Evidence is retained in
`~/.codex/artifacts/thor-system-app-uad-filter-2026-10-04/`:

- `tested-source.json` and `validation-summary.json`
- `debug-final.log` and `gates-final.log`
- `*-instrumentation.log` and `*-final-verification.json`
- `*-system-recommended.png`, tab-restoration XML, and cold-restart XML

Metadata loading/failure cases use controlled tests; the live device UAD reads
succeeded. Device UI acceptance used FOSS debug. Store Release lint passed;
minified release UI, older Android versions, and additional OEMs were not tested
for this feature.
