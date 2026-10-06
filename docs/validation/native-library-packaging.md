# Native library packaging validation

Validation started 2026-10-04 on `chore/compress-native-libraries`, branched from
`dev` at `18162fe050b1360c79de0071eed4c59ddcb49622` (PR #556 merge).
Version remains **1.97.0 / 1970**. Any PR targets `dev`.

## Packaging policy

[`app/build.gradle.kts`](../../app/build.gradle.kts) sets
`packaging.jniLibs.useLegacyPackaging = true`. Standalone FOSS and Store APKs therefore
compress their native libraries and declare `extractNativeLibs=true`; Android extracts
the device's matching ABI during installation.

AGP **9.5.0-alpha08** also applies that DSL setting to bundle packaging by default.
An `androidComponents.onVariants` override sets
`variant.packaging.jniLibs.useLegacyPackagingFromBundle` to `false`, preserving normal
uncompressed, directly loadable native libraries in device-targeted APKs generated from
the Store bundle. DEX packaging and the set of supported ABIs are unchanged.

This reduces standalone APK downloads. It creates extracted native copies on installation;
net installed-storage usage has not been measured and also depends on APK padding and
filesystem allocation. The standalone reduction is not a Play download-size claim.

## Library inventory and measured APK sizes

Both flavors contain the same two libraries for **arm64-v8a, armeabi-v7a, x86, and x86_64**:

| Library | Dependency | Purpose | Raw bytes, all four ABIs |
| --- | --- | --- | ---: |
| `libandroidx.graphics.path.so` | `androidx.graphics:graphics-path:1.1.0` | Compose path iteration and conic conversion | 35,592 |
| `libdatastore_shared_counter.so` | `androidx.datastore:datastore-core-android:1.2.1` | Multiprocess DataStore coordination | 27,160 |

The eight native entries retain identical decompressed bytes. Their combined payload changes
from **62,752 STORED bytes to 27,508 DEFLATED bytes**. No APK entries were added or removed;
the only changed decompressed entry is `AndroidManifest.xml`. The remaining APK reduction
comes from packaging overhead, including native ZIP alignment padding.

| Unsigned standalone release APK | Baseline bytes | Candidate bytes | Saved bytes | Reduction |
| --- | ---: | ---: | ---: | ---: |
| FOSS | 4,808,071 | 4,706,367 | **101,704** | **2.12%** |
| Store | 5,031,260 | 4,921,552 | **109,708** | **2.18%** |

Candidate SHA-256:

```text
FOSS  703649c5e34089230dde4a4677339373702ff67063bc4762937ab8825bd48667
Store 64c6e521977f71463fede796c49336ac6174f3306937fe2c1a51e1a97be26fb7
```

All existing native ELF load segments have 16 KiB alignment. Compressed entries do not
need direct-from-ZIP page alignment, but extracted ELF alignment still matters. Runtime
validation includes an actual 16 KiB-page emulator, as recorded below.

## Runtime coverage

[`NativeLibraryPackagingTest`](../../app/src/androidTest/java/com/valhalla/thor/packaging/NativeLibraryPackagingTest.kt)
adds two checks of real native consumers:

- Convert a circular path's conics to quadratics through Compose/AndroidX and check the
  resulting geometry. This still exercises native conic conversion on API 34 and later.
- Create a temporary multiprocess DataStore, update its counter, close it and reopen it,
  then verify persistence and another update. The multiprocess factory exercises the
  native shared counter in this single-process test; it does not test interprocess races
  or change Thor's ordinary preferences.

Both checks assert that the target app's extracted library exists and appears in
`/proc/self/maps`, preventing a library bundled in the instrumentation APK from masking
a target APK packaging failure. Temporary DataStore files are removed after the test.
The same run includes the five existing Outfit and font-preset rendering checks.
After the bundle-policy override, the debug app, instrumentation app, and both standalone
release APKs were rebuilt and remained byte-identical to the tested candidate artifacts.

| Target | API / ABI / page size | Instrumentation | App UI smoke |
| --- | --- | --- | --- |
| Odin Magisk emulator (`emulator-5582`) | 36 / arm64-v8a / **16 KiB** | **7/7 passed** | Home, App List and Settings visually checked |
| Android 11 emulator (`emulator-5584`) | 30 / arm64-v8a / **4 KiB** | **7/7 passed** | Home, App List and Settings visually checked |
| POCO F7 / ReSuKiSU (`1da5425f`) | 36 / arm64-v8a / **4 KiB** | **7/7 passed** | Home, App List and Settings visually checked |

After reconnecting, the phone passed the same seven tests in **6.491s** using the unchanged
final debug and instrumentation APKs. Home, App List and Settings were visually checked.
Its three isolated release install/update, extraction, cold-start and visual checks also
passed. A Shizuku authorization prompt covered the first minified captures; after it was
resolved, all three visible-screen checks were repeated and Home rendered correctly.
The assistant used no `adb root` commands or new root grants, and the daily-use release
app was not replaced.

Both emulators and the POCO passed release-mode installation and cold-start checks using
an isolated `com.valhalla.thor.nativepackaging` fixture. An artifact-only init script changes the
application ID and applies debug signing while retaining R8 and resource shrinking; these
fixtures are **not byte-identical to the production-package artifacts**.

- Freshly install the signed minified FOSS APK and cold-launch Home. Both extracted arm64
  libraries are present, at **9,952** and **7,784 bytes**.
- Update the same package to the signed Store split set using the final bundle policy and
  cold-launch Home. The native directory is empty, consistent with direct split loading.
- Update the Store split installation back to the compressed FOSS APK and cold-launch
  Home. Both extracted libraries return with the expected sizes.

Home screens for all three installation cases were visually checked on all three targets.
The captured emulator Store-split cold-start logcat and all three phone follow-up logcats
have no fatal or JNI-loading errors. On the Magisk emulator, the new fixture's Magisk request
timed out without a grant; the phone's Shizuku prompt was handled separately as described above.
Normal production build outputs were restored afterward;
both release APKs and the Store AAB match the retained
candidate bytes exactly.
The temporary phone fixture and scratch UI XMLs were removed after validation; the normal
debug app was retained. Tested production and native-test source hashes remained unchanged.

## Host and bundle verification

The final packaging-policy build passed `test lintFossDebug lintStoreRelease` and release
artifact generation in **4m32s**: 322 tasks, 30 executed and 292 up-to-date. The reports contain
**3,584 unit tests per debug flavor (7,168 total)**, zero failures/errors/skips, and no lint
errors or warnings. FOSS Debug has nine existing hints and Store Release has eight. No
MissingTranslation or SyntheticAccessor findings occurred. `tested-source.json` records
the production Gradle configuration and native test source hashes for this run.

The final Store AAB is **10,654,073 bytes**, with SHA-256
`ae893984f9150258be6412c4ae597dc0364063ee8ea3d0c7e2ee5a7df67d6c29`.
Its `BundleConfig.pb` is byte-identical to the baseline; only
`base/manifest/AndroidManifest.xml` changes among decompressed entries. Local bundletool
**1.18.3** generated device splits using both emulator specifications and the phone
specification. All final split sets retain **STORED native entries** and
`extractNativeLibs=false` in the base master APK, confirming the intended direct loading.

The arm64 split remains **45,321 bytes**. Total APK-set sizes exactly match the baseline:
**8,334,599 bytes** for the API 36 emulator/phone specifications and **8,350,983 bytes** for
API 30. Estimated transfer size changes by only **-2 bytes** and **+11 bytes**, respectively.
All generated APK signatures and `zipalign` checks pass; native hashes and ELF alignment
are unchanged. Bundletool's signed universal APK follows the standalone extraction policy:
**4,935,301 bytes**, down **106,496 bytes** from its signed baseline.

Earlier results in `bundletool-audit/compressed-play-experiment.md` describe a historical
experiment that compressed Play splits as well. Those results are not acceptance evidence
for the final bundle policy. They showed essentially unchanged estimated transfer sizes,
which motivated retaining normal split loading. This is local bundletool validation;
no Play Console upload or Play-server delivery has been tested.

## Reproduction and evidence

Use JDK 21 and the Android SDK. Run Gradle builds sequentially from the topic worktree:

```sh
./gradlew --max-workers=1 test lintFossDebug lintStoreRelease \
  :app:assembleFossRelease :app:assembleStoreRelease :app:bundleStoreRelease
./gradlew --max-workers=1 :app:assembleFossDebug :app:assembleFossDebugAndroidTest
```

Run against each available test target, retaining a separate evidence directory:

```sh
target_serial=emulator-5582
validation_dir=$(mktemp -d "${TMPDIR:-/tmp}/thor-native-validation.XXXXXX")
adb -s "$target_serial" install -r -t app/build/outputs/apk/foss/debug/app-foss-debug.apk
adb -s "$target_serial" install -r -t \
  app/build/outputs/apk/androidTest/foss/debug/app-foss-debug-androidTest.apk
adb -s "$target_serial" shell am instrument -w -r \
  -e class com.valhalla.thor.packaging.NativeLibraryPackagingTest,com.valhalla.thor.presentation.theme.OutfitVariableFontTest,com.valhalla.thor.presentation.theme.FontPresetRenderingTest \
  com.valhalla.thor.debug.test/com.valhalla.thor.ThorTestRunner \
  | tee "$validation_dir/instrumentation.log"
```

Require `OK (7 tests)`; an ADB exit code alone is insufficient. Inspect the compiled
standalone manifests with `apkanalyzer manifest print`, compare ZIP entry methods and
decompressed hashes, and run `zipalign -c -P 16 -v 4` on the release APKs. For the Store
bundle, use bundletool `dump config`, `get-device-spec` and `build-apks --device-spec`;
verify generated split manifests and native entry compression separately from direct APKs.

Retained evidence: `~/.codex/artifacts/thor-native-library-packaging-2026-10-04/`, including
`baseline/`, `candidate/`, `apk-comparison.json`, `final-policy-apk-identity.json`,
`host-verification.json`, `tested-source.json`, `validation-summary.json`,
`candidate/final-build.log`, target preflight JSON files, device instrumentation logs,
screenshots, and `bundletool-audit/`.
`isolated-release/artifacts.json` identifies release fixtures, with per-device installation,
cold-start, native-directory, screenshot, and logcat evidence under `isolated-release/<serial>/`.
The phone's final visible-screen evidence is in `isolated-release/1da5425f/ui-followup-/`,
with `ui-followup-results.json` and `minified-contact-sheet.png` in its device directory.
Historical global-compression artifacts are identified under `experiments/compressed-play/` and
`bundletool-audit/compressed-play-experiment.md`.

Uncovered targets include API 28, 32-bit ARM, x86/x86_64 runtime, additional OEMs, and actual
Play delivery. The four-ABI binary/ELF audit does not substitute for running those ABIs.
