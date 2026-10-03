# Root manager discovery and privilege sheet validation

Validated on 2026-10-03. The tested app source is
[`34b43c2d58589a4145266b0b311d37abe2646c62`](https://github.com/trinadhthatakula/Thor/commit/34b43c2d58589a4145266b0b311d37abe2646c62),
based on dev commit `51d118b649fb03171c8de929b5608374fe1b6d29`.
The subsequent evidence commit adds documentation and screenshots only.

FOSS debug APK SHA-256:
`b82afd8a60e25492a36f703ed2443e1336078f792e0af5604212c7c156b14420`.
The installed APK hash matched on both devices. A build-only
`-PversionCode=1970` override allowed updating the existing test app without
removing its data; this feature does not change the repository version code.

## Local gates

Run with JDK 21 and `--max-workers=1 -PversionCode=1970`:

- [x] `./gradlew assembleFossDebug`
- [x] `./gradlew test` — 3,550 app tests in each of FOSS Debug and Store Debug;
  zero failures, errors, or skips.
- [x] `./gradlew lintFossDebug lintStoreRelease` — zero errors or warnings,
  including no MissingTranslation or SyntheticAccessor issues. The existing
  informational hints remain (9 FOSS Debug, 8 Store Release).
- [x] `git diff --check`

The feature adds 17 tests covering registry entries, canonical and renamed
launcher discovery, deduplication, disabled/removed apps, explicit shortcuts,
and local preference mapping.

## Device UI checks

| Device | Root manager | Result |
| --- | --- | --- |
| POCO F7 / onyx, Android 16 (API 36) | ReSukiSU v4.2.0-rc2 (35159), randomized package ID | Passed |
| Odin_Magisk_API36_1 emulator, Android 16 (API 36), 16 KB pages | Magisk 30.7 (30700) | Passed |

On both devices:

- [x] Automatic discovery shows the manager icon, package name and detection tick.
  The automatic row retains the independent live privilege status.
- [x] Refresh returns to the granted state.
- [x] Choose opens the searchable manager sheet; selecting the manager shows a
  selection tick and changes the action label to Change.
- [x] Change reopens the picker, and its Close icon returns to Privilege Check.
- [x] Clear restores automatic detection and Choose; Clear is disabled without
  a saved selection.
- [x] The header Close icon dismisses Privilege Check.

The physical-device checks also confirmed that selection and clearing survive
process restarts, the saved shortcut opens ReSukiSU's actual launcher, the
Shizuku row remains available, and search/results remain visible with the
keyboard open.

The final emulator build also passed Portuguese at 2× font scale: the wrapped
Choose label and single-line Clear label share the same button height (238 px).
English at normal/2× font scale and docked-keyboard scrolling were checked on
preview `02b40176`, before the final detection-detail changes. The emulator's
locale and font scale were restored, and its automatic Magisk sheet was left
open. The physical phone was left on its selected-manager sheet.

These are device UI smoke checks; no instrumentation suite or root-policy toggle
was run for this UI change. Root-manager policy was unchanged throughout.

## Screenshots

Phone package IDs are redacted in the published images.

| Before | ReSukiSU, detected | Magisk, detected |
| --- | --- | --- |
| ![Previous dialog](../images/privilege-check/previous-dialog.png) | ![ReSukiSU automatic discovery](../images/privilege-check/resukisu-detected.png) | ![Magisk automatic discovery](../images/privilege-check/magisk-detected.png) |

| ReSukiSU, selected | Magisk, selected | Portuguese at 2× text size |
| --- | --- | --- |
| ![Selected ReSukiSU](../images/privilege-check/resukisu-selected.png) | ![Selected Magisk](../images/privilege-check/magisk-selected.png) | ![Equal button heights](../images/privilege-check/magisk-portuguese-large-text.png) |
