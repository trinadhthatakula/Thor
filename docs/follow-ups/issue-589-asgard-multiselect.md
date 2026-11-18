# Issue #589: Asgard multiselect integration

Thor's Apps and Freezer start on User and use single-select `ConnectedButtonGroup` controls by
default, matching Home. **Settings → Customization → Combined app lists** opts into the
`selectedIndices` / `onSelectionChanged` overload in both the headers and configuration sheets.
Enabling it permits both sources without changing the current selection. Disabling it while both
are selected returns to User and clears bulk selection; System-only selection stays on System.
The separate User, System and combined filter profiles remain saved. The dedicated Suspended
list continues to include both sources independently of this setting. User grid icons have no
person overlay; list rows retain the translated User app chip.
All three screens use the person icon for User apps and Asgard's primary/onPrimary selected colors.

## Published integration

Thor pins Asgard 2.1.1, which includes the multiselect overload introduced in 2.1.0 and the
navigation-motion fixes. Normal builds resolve it from Maven Central, without a composite build.
The artifact requires compile SDK 37.2.
For future cross-repository development, the optional source override remains available:

```sh
./gradlew :app:assembleFossDebug -PasgardDir=/path/to/Asgard -PasgardAgpVersion=9.6.0-alpha02
```

The `asgardDir` Gradle property uses the existing composite-build integration in
`settings.gradle.kts`; an `asgardDir` entry in `local.properties` is not read by that integration.
Asgard's optional `asgardAgpVersion` override must match Thor's pinned Android Gradle Plugin
version, because Gradle requires matching AGP versions across included Android builds.

## Published-dependency validation, 2026-11-09

`./gradlew test lintFossDebug lintStoreRelease` and debug/test APK assembly passed without
`-PasgardDir`, using the published Asgard 2.1.1 artifact. All 3,757 unit tests passed per Foss/Store
flavor; lint reported zero errors and warnings. All nine `AppListUadFilterTest` emulator checks
passed against the published dependency,
alongside the nine navigation-editor and six navigation-scaffold checks.

The library allows empty selection. Thor keeps at least one source selected by rejecting an
empty callback, so the last selected button retains its selected colors and enabled appearance.
