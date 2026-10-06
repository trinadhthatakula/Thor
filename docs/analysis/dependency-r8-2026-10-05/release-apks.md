> This is a baseline audit snapshot. See [the current result](README.md) for applied cleanup and validation. Kotlin source references abbreviate `app/src/main/java/com/valhalla/thor/`. Machine-readable evidence is retained under `~/.codex/artifacts/thor-dependency-r8-audit-2026-10-05/` (APK evidence in its `apk-analysis/` subdirectory).

# Release APK dependency and R8 audit

The strongest APK-confirmed cleanup is the unused DataStore native counter library: **10,923 compressed payload bytes per APK across four ABIs**. Its complete Java/Kotlin loader path is already removed by R8. No test framework or Compose UI tooling runtime leaked into either APK, and billing/Google runtime code remains isolated to Store.

This audit is read-only. It did not build, change source, inspect a device, or alter an APK. It analyzes the frozen native-library-packaging candidates supplied by the parent task. The separate cleanup build was not inspected.

## Artifact identity and limits

| Artifact | File bytes | SHA-256 |
|---|---:|---|
| foss | 4,706,367 | `703649c5e34089230dde4a4677339373702ff67063bc4762937ab8825bd48667` |
| store | 4,921,552 | `64c6e521977f71463fede796c49336ac6174f3306937fe2c1a51e1a97be26fb7` |

Both APKs contain one DEX. Its embedded R8 `pg-map-id` exactly matches the supplied variant mapping file. Compiler: R8 9.5.20-dev, full mode, min API 28. These checks anchor all deobfuscated results to the correct APKs rather than to a later build.

| Metric | FOSS | Store |
|---|---:|---:|
| DEX file bytes | 6,428,736 | 6,790,728 |
| Compressed DEX ZIP payload | 3,206,091 | 3,394,197 |
| DEX class definitions | 7,311 | 7,715 |
| DEX method IDs, including references | 34,149 | 36,155 |
| apkanalyzer defined methods | 30,303 | 32,087 |
| resources.arsc bytes, stored uncompressed | 823,588 | 845,800 |

**Package figures below are raw DEX attribution, not removable APK bytes.** R8 inlines and merges across class/package boundaries. For example, the deobfuscated `androidx.compose.ui.text.input.EditingBufferKt` bucket contains moved Thor methods. FOSS/Store package totals can therefore move in opposite directions even with nearly identical source. Attribution also excludes some DEX-wide overhead. Do not multiply these values by a compression ratio or add nested package rows to estimate savings.

## Retained package impact

Each cell is `defined methods / attributed raw DEX bytes`.

| Package | FOSS | Store |
|---|---:|---:|
| `androidx.compose` | 10,625 / 1,706,083 | 10,833 / 1,760,880 |
| `com.valhalla.thor` | 8,725 / 1,966,686 | 8,855 / 1,957,665 |
| `androidx.work` | 603 / 135,809 | 577 / 131,233 |
| `com.airbnb.lottie` | 637 / 123,623 | 630 / 123,587 |
| `androidx.datastore` | 678 / 107,820 | 702 / 111,216 |
| `coil3` | 627 / 89,242 | 568 / 75,742 |
| `org.koin` | 204 / 57,605 | 180 / 43,505 |
| `androidx.room` | 323 / 53,806 | 323 / 57,773 |
| `androidx.compose.material3.adaptive` | 331 / 61,406 | 328 / 57,842 |
| `com.valhalla.superuser` | 855 / 76,408 | 855 / 76,629 |
| `com.valhalla.asgard` | 60 / 12,063 | 60 / 12,050 |
| `com.android.billingclient` | Absent | 308 / 99,573 |
| `com.google.android.gms` | Absent | 1,150 / 192,621 |
| `com.google.android.datatransport` | Absent | 213 / 33,982 |

Compose, navigation/adaptive UI, WorkManager, Room, Odin, Coil and Lottie have retained runtime code. APK presence and footprint alone do not establish that a dependency can be removed. Source audits must connect any removal to actual call sites and required behavior. The small `com.valhalla.asgard` bucket particularly must not be treated as the whole transitive Asgard cost; its Compose code can inline or merge elsewhere.

## Actionable native packaging evidence

In **both** variants, `usage.txt` lists these as wholly removed classes:

- `androidx.datastore.core.MultiProcessCoordinator` and its nested classes.
- `MultiProcessCoordinatorKt` and `MultiProcessDataStoreFactory`.
- `NativeSharedCounter`, `SharedCounter`, and all its nested counter implementations/factory.

Neither deobfuscated DEX defines these classes. Neither DEX contains the `datastore_shared_counter` loader string. The remaining DataStore native keep rule uses `-keepclasseswithmembernames`; it preserves native method names when classes remain live but did not retain the unused class here.

| Packaged entry | Uncompressed bytes | Compressed payload bytes |
|---|---:|---:|
| `lib/arm64-v8a/libdatastore_shared_counter.so` | 7,784 | 2,859 |
| `lib/armeabi-v7a/libdatastore_shared_counter.so` | 5,916 | 2,504 |
| `lib/x86/libdatastore_shared_counter.so` | 6,124 | 2,797 |
| `lib/x86_64/libdatastore_shared_counter.so` | 7,336 | 2,763 |
| **Total per APK** | **27,160** | **10,923** |

A narrow packaging exclusion is supported once the source audit confirms every Thor DataStore uses the single-process coordinator. This is **native packaging cleanup, not additional Java/Kotlin shrinking**. ZIP headers/alignment and any signing effects require a fresh build comparison; 10,923 bytes is the measured removable compressed payload only. Preserve `libandroidx.graphics.path.so`, which is a separate runtime library. If a future feature introduces multiprocess DataStore, revisit the exclusion.

## Test/debug leakage

Both release manifests omit `debuggable` and `testOnly`, giving Android's default `false`. Neither APK defines:

- JUnit, Robolectric, Espresso/`androidx.test`, Turbine or coroutines-test classes.
- Compose UI tooling or UI test classes.
- `ThorTestRunner`, `OutfitVariableFontTest` or `FontPresetRenderingTest`.
- `kotlinx.coroutines.debug` or `kotlin.reflect.jvm.internal` runtime classes.

The APKs do retain **normal production diagnostics**, which must not be mislabeled as test leakage: Compose runtime stack-trace/tooling support (about 3.2 KB raw attribution), WorkManager's diagnostics receiver/worker, and ProfileInstaller with its profile assets. No removal is proposed for these here.

`DebugProbesKt.bin` is present as a 774-byte compressed resource in both APKs. Its coroutines source identifies it as the prebuilt JVM Java-agent replacement class loaded by `AgentPremain`; the agent runtime is not in either APK. This is a tiny inherited development resource, not a bundled test framework. It is recorded separately so that “no test leakage” does not incorrectly imply the ZIP has no debug-named resource.

## Store isolation

FOSS defines **zero** `com.android.billingclient` and `com.google.android.gms` classes. Store defines 42 Billing classes and 227 Google Play services classes. Store alone also includes billing proxy activities, Google API activity, DataTransport services and Firebase encoder code. Its billing/Google metadata is absent from FOSS. These are Store billing dependency effects, not evidence of a test dependency leaking into FOSS.

The complete Store APK is **215,185 bytes larger**. Of that, the compressed DEX payload difference is **188,106 bytes** and the uncompressed resource-table difference is **22,212 bytes**. The rest includes manifests, resources, metadata and ZIP overhead. Per-library subtraction is not reliable because R8 changes merged attribution; see `store-minus-foss-zip-payloads.csv` for exact ZIP payload differences.

## Kept code that should remain protected

Coil's `GenericViewTarget` and `ImageViewTarget` survive with 22 defined methods total and only **1,131 raw attributed DEX bytes in FOSS / 1,130 in Store**. The consumer rule explicitly prevents an R8 9.0+ vertical-merge bug that drops default `Target` super-calls and can break release image rendering (upstream issue 524864608). This tiny bucket is not a sound reason to override the workaround.

Thor's external extension ABI remains about 2,481 raw attributed bytes / 45 methods; the rootservice package remains about 34 KB / 285 methods. Both have intentional external loading/Binder contracts documented in app rules. They are not generic dependency leftovers. This report does not propose weakening those keeps.

## Portable evidence files

- `summary.json`: APK identity, matching R8 IDs, DEX counts, manifest flags, native payload and removed-loader evidence.
- `foss-dex-packages.txt`, `store-dex-packages.txt`: complete `apkanalyzer dex packages --defined-only --proguard-mappings ...` output.
- `*-p-size.csv`, `*-c-size.csv`, `*-dex-rows.json`: sorted package/class attribution and parsed machine data.
- `*-leakage-and-store-isolation.json`: exact defined-class match results for test/debug and billing prefixes.
- `*-zip-entries.json`, `store-minus-foss-zip-payloads.csv`: compressed and uncompressed ZIP payload sizes.
- `*-manifest.xml`, `*-r8-markers.txt`: decoded manifest and embedded compiler identity.
- `*-removed-datastore-native-users.txt`: removed DataStore native consumers.
- `*-resource-reachability.txt`: copied shrinker resource reachability evidence from the matched frozen build.

No font, image, UAD-data or license changes are recommended in this audit.
