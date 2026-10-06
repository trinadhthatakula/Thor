> This is a baseline audit snapshot. See [the current result](README.md) for applied cleanup and validation. Kotlin source references abbreviate `app/src/main/java/com/valhalla/thor/`. Machine-readable evidence is retained under `~/.codex/artifacts/thor-dependency-r8-audit-2026-10-05/` (APK evidence in its `apk-analysis/` subdirectory).

## 3. Optimization summary

Generated from fresh R8 9.5.20-dev configuration-analyzer builds of `dev` at
`18162fe050b1360c79de0071eed4c59ddcb49622`, using AGP 9.5.0-alpha08 and JDK 21.
Both baseline unsigned APKs match the previously retained hashes byte for byte.

| Variant | Optimization eligibility | Shrinking eligibility | Obfuscation eligibility | Live items |
| --- | ---: | ---: | ---: | ---: |
| FOSS | 97.84% | 98.06% | 98.08% | 155,596 |
| Store | 97.85% | 98.06% | 98.08% | 165,282 |

The denominator counts live classes, fields and methods. These scores describe keep-rule
constraints, not unused code, bytes saved, or safe removable percentages. R8 full mode,
optimized defaults and resource shrinking are already active. No global disabling rule
was found. Scores and counts come from each generated `analysis.txt`.

## 4. Keep rules evaluation

### `-keep class com.valhalla.superuser.** { *; }`

- **Keeps**: 114 classes, 285 fields, 739 methods in the FOSS analyzer result.
- **Kept items**: `Lcom/valhalla/superuser/BuildConfig;`, `Lcom/valhalla/superuser/CallbackList;`, `Lcom/valhalla/superuser/JobHandle;`.
- **Purpose**: Odin API, implementation, bootstrap and IPC.
- **Action**: Retain until a measured surgical replacement is validated in a separate root-service change. Removing the blanket rule requires accounting for root-process class loading, framework callbacks and Binder contracts. The retained package is only about 76 KB of attributed raw DEX; that includes necessary code and is not removable APK payload. The conservative optimization-only experiment in [the current result](README.md) saved too little compressed payload to adopt.

### `-keep class com.valhalla.thor.rootservice.** { *; }`

- **Keeps**: 36 classes, 187 fields, 306 methods in the FOSS analyzer result.
- **Kept items**: `Lcom/valhalla/thor/rootservice/AndroidRootDataClearPreparationKt$prepareAndroidRootDataClear$observer$1;`, `Lcom/valhalla/thor/rootservice/AndroidRootDataClearPreparationKt;`, `Lcom/valhalla/thor/rootservice/BoundedPackageDumpReader;`.
- **Purpose**: Thor root service and AIDL contract.
- **Action**: Retain while identifying any precise redundant member keeps. Class-name loading and root identity-sensitive suspend/clear-data callbacks require signed/minified device coverage; JVM and debug builds are insufficient.

### `-keep class com.valhalla.thor.extension.api.** { *; }`

- **Keeps**: 10 classes, 11 fields, 43 methods in the FOSS analyzer result.
- **Kept items**: `Lcom/valhalla/thor/extension/api/AppIconModel;`, `Lcom/valhalla/thor/extension/api/AutomationExtension;`, `Lcom/valhalla/thor/extension/api/DebloatExtension;`.
- **Purpose**: Host extension ABI.
- **Action**: Retain original names and public ABI members. External extension APKs resolve these host types at runtime, so absence of internal call sites does not demonstrate dead code.

### `-keep class com.valhalla.bypass.Helper$* { *; }`

- **Keeps**: 8 classes, 43 fields, 17 methods in the FOSS analyzer result.
- **Kept items**: `Lcom/valhalla/bypass/Helper$AccessibleObject;`, `Lcom/valhalla/bypass/Helper$Class;`, `Lcom/valhalla/bypass/Helper$Executable;`.
- **Purpose**: Hidden API reflection helpers.
- **Action**: Retain: this is targeted protection for string-based reflection, including fields and constructors. The compile-only vm-runtime stubs are not packaged.

### `-keep class * extends coil3.target.GenericViewTarget { *; }`

- **Keeps**: 1 class, 2 fields, 19 methods in the FOSS analyzer result.
- **Kept items**: `Lcoil3/target/ImageViewTarget;`.
- **Purpose**: Coil View target subclasses.
- **Action**: Retain the upstream workaround. GenericViewTarget and ImageViewTarget total about 1.1 KiB of attributed raw DEX in this Compose app; the rule prevents an upstream R8 vertical-merge bug, so overriding it is not a worthwhile size target.

## 5. Subsumed keep rules

The Odin IPC and RootService subclass rules overlap the broad Odin/app service keeps.
Deleting a subsumed rule while its encompassing rule remains does not enable additional
shrinking. Do not report that housekeeping as an APK reduction. All exact overlap records
are retained in the generated report JSON alongside `analysis.txt`.

## 6. Historical analysis summary

The earlier pre-font audit reported approximately 97.84% optimization, 98.06% shrinking
and 98.08% obfuscation eligibility. Fresh results remain essentially unchanged. Font and
native ZIP packaging improvements do not imply that a large new R8 opportunity appeared.
No broad keep rule was changed in the implementation. A separate artifact-only Odin
optimization experiment is recorded in [the current result](README.md).
