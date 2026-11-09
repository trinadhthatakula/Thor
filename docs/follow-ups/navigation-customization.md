# Navigation customization

Asgard 2.1.1 is consumed from Maven Central. It requires compile SDK 37.2; target and minimum
SDK policy remain independent. The existing issue #589 multiselect controls use that artifact.

Settings → Customization → Navigation bar opens a dedicated Navigation 3 page with a back arrow,
using the full window rather than retaining the Settings index beside it.
The tappable preview stays above the scrollable controls on phones; wider or short landscape
layouts place the preview and controls side by side. Apply, Cancel and Reset remain in the footer.
Preview taps change the selected destination locally without navigating the application.
The draft, preview selection and vertical scroll position survive saved-state restoration.

Available controls are docked/floating placement, expanding/segmented/standard style, automatic/
selected/all/hidden labels, icons, bar/selection shapes, item spacing, inner padding, floating margin
and elevation. Margin and elevation controls are enabled for floating placement. Hiding icons keeps
all labels visible so destinations remain identifiable, without overwriting the stored label choice.
Turning icons back on restores that choice, including after saving or restoring the draft.
Colors follow Thor's current theme.

Apply atomically saves the normalized style in the backed-up settings DataStore. Cancel, the back
arrow, system Back and Reset do not write preferences; Reset changes the draft until Apply is tapped.
Failed writes leave the editor and draft open for retry. Sizes imported from backups are clamped,
unknown future JSON fields are ignored, and malformed snapshots fall back to defaults.

The same configuration mapping renders the preview, compact bottom navigation and adaptive rail.
Existing route selection, per-tab back stacks, navigation visibility and export progress remain
owned by MainScreen. Automatic rail labels adapt to available height. Docked navigation stays in
Scaffold’s bottom bar. Floating navigation uses the centered floating-action slot, with the page
scrolling behind it. Measured overlay clearance is applied to scroll-content tails, draggable
scrollbars and action/feedback overlays, rather than reducing the whole page viewport. Export
progress keeps its bottom slot and remains cancellable below floating navigation. The floating
host owns system/keyboard insets; docked navigation handles navigation-bar insets, and the rail’s
host handles safe drawing insets and vertical overflow.

Validation commands:

```sh
./gradlew test lintFossDebug lintStoreRelease :app:assembleFossDebug :app:assembleFossDebugAndroidTest
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.valhalla.thor.presentation.settings.NavigationCustomizationTest,com.valhalla.thor.presentation.widgets.AppListUadFilterTest,com.valhalla.thor.presentation.navigation.ThorNavigationScaffoldTest \
  com.valhalla.thor.debug.test/com.valhalla.thor.ThorTestRunner
```

The unit suite covers absent/corrupt/future saved snapshots, restoration alongside other settings,
bounds and text-only labels. Device tests cover preview-before-Apply, Cancel, Reset, failed saving
and saved-state restoration.

Verified on 2026-11-08:

- Maven Central resolves Asgard and its Android artifact at 2.1.0 without a local composite override.
- The full build gate above passed: 3,753 unit tests per Foss/Store flavor, including six new
  preference tests per flavor; both lint gates reported zero errors and warnings. The app and
  Android test APKs assembled successfully.
- On the Magisk Android 17 / API 37 emulator, all 13 instrumented tests passed: five editor tests
  and eight issue #589 app-filter tests. The five editor tests also passed at 320 × 640 dp with
  system font scale set to 2.0. Display and font overrides were restored afterward.
- In the actual app, Floating + Segmented was applied, then retained after force-stop and restart
  and when reopening the editor. Preview and Home both rendered the selected style.

Physical-device behavior and a full TalkBack journey have not been validated.

The icon-toggle regression was also verified on 2026-11-09. Seven preference tests passed for each
Foss/Store flavor, including storage round trips for every label mode while icons are hidden.
All seven editor tests passed on the emulator, covering off/on toggling and restoring a draft with
icons hidden. In the actual app, Auto → icons off → icons on returned to Auto and rendered the
label beside only the selected destination again.

Dedicated-page validation on 2026-11-09:

- The full test/build gate passed with 3,754 unit tests per Foss/Store flavor and zero lint errors
  or warnings. The final layout APKs were rebuilt after the landscape refinements.
- All nine editor tests passed at normal size, at 320 × 640 dp with 2.0 font scale, and in landscape.
  These include Cancel, the back arrow, system Back, Apply, failed saving, draft restoration and the
  icon-toggle regression. The final landscape layout was checked again after adjusting pane widths.
- The actual app opens the page from Customization and returns there using Back. The page uses
  the full window in landscape, and the unsaved Floating + Segmented + Auto draft survived rotation
  back to portrait. Both layouts were inspected visually, and device overrides were restored.

Navigation motion regression, verified on 2026-11-09:

The configurable Asgard 2.1.0 renderer had omitted animated item resizing and label transitions.
The local Asgard `fix/navigation-selection-motion` branch restores these, fixes appearance-specific
caption fitting, and keeps dynamic press scales current. See Asgard's `docs/motion-audit.md` for the
broader component audit and remaining validation work.

Asgard Android/WebAssembly compilation, host tests and 23 native component checks passed. Thor was
built with the following local override, passed all nine editor checks, and its patched Foss debug
APK was installed on the connected physical device:

```sh
./gradlew -PasgardDir=../Asgard -PasgardAgpVersion=9.6.0-alpha02 :app:assembleFossDebug :app:assembleFossDebugAndroidTest
```

The motion fix was merged in [Asgard #10](https://github.com/trinadhthatakula/Asgard/pull/10)
and published with the [2.1.1 release](https://github.com/trinadhthatakula/Asgard/pull/11).
Thor now pins that published version, so ordinary Maven Central builds include the fix.

Floating placement integration, verified on 2026-11-09:

- Floating navigation now uses Scaffold’s centered floating-action slot; docked navigation keeps
  the bottom-bar slot. Floating content extends behind the bar, while measured clearance protects
  action toolbars, feedback, scrollbars and the final scrollable items.
- All 3,757 unit tests passed per Foss/Store flavor, and both lint gates had zero warnings/errors.
  After a compiler heap exhaustion, the checks completed with one worker and a local 6 GiB heap
  override; repository Gradle memory settings were not changed.
- All 14 emulator checks passed: five navigation-scaffold geometry/scrolling/export checks and
  nine navigation-editor regression checks. Actual Apps/Freezer layouts and keyboard clearance
  were inspected on the emulator.
- Signed Foss release and debug/test builds (1.97.3 / 1973), plus Android test support, were
  installed on the connected physical device. The local Asgard motion override was used.

Floating-padding refinement, verified on 2026-11-09:

- Compensated Scaffold’s extra 16 dp FAB gap so the configured floating margin is applied once.
  Removed the additive legacy tail padding from unselected Apps lists and the Settings index.
  Docked spacing and room for action toolbars are retained.
- All 3,757 unit tests passed per flavor; both lint gates reported zero warnings/errors. All six
  navigation-scaffold emulator tests passed, including margin changes and final-item reachability.
- Apps at the end of the system list, Settings and Freezer were inspected. Emulator navigation
  and source choices were restored. Updated signed release and debug/test builds were installed
  on the physical device, including Android test support.

Published-dependency PR validation, 2026-11-09:

- Ran the complete test/lint gate and assembled Foss debug/test APKs without `-PasgardDir`.
  All 3,757 unit tests passed per Foss/Store flavor; both lint gates reported zero errors/warnings.
- All 24 focused emulator checks passed against Maven Central's Asgard 2.1.0: six navigation
  scaffold checks, nine editor checks and nine app/UAD filter checks.
- Both minified Foss and Store release APKs assembled successfully; no R8 missing rules were
  produced. Dependency insight confirmed the published `asgard-android:2.1.0` runtime artifact.
- Reviewer screenshots are retained in `docs/assets/navigation-customization/`. They show the
  local Asgard motion-patch integration; the published 2.1.0 checks above do not establish that
  the unpublished animation fix is included in a normal Thor build.

Published 2.1.1 integration, verified on 2026-11-09:

- Dependency insight selected `asgard:2.1.1` and `asgard-android:2.1.1` from Maven Central,
  without a local source override. The resolved Android AAR matches Central's SHA256 checksum.
- `./gradlew test lintFossDebug lintStoreRelease` passed with 3,757 unit tests per Foss/Store
  flavor and zero lint errors/warnings. Debug and Android-test APK assembly also passed.
- All 24 focused Android 17 emulator tests passed against the published artifact: six navigation
  scaffold tests, nine customization-editor tests and nine app/UAD filter tests.
- Both minified Foss and Store release APKs assembled successfully. Thor's version remains
  1.97.3 / 1973. The published dependency now includes Asgard's navigation-motion fixes.
