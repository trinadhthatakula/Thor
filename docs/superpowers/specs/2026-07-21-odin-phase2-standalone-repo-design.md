# Odin Phase 2 — standalone repo + basic `:suCore` port (design)

**Date:** 2026-07-21
**Status:** Approved design. Template = `thor-extension-api` (Android-only Maven Central lib).
**Predecessor:** Phase 1 merged to Thor `dev` via PR #264 (`:suCore` is now generic + coroutines-declared + Apache-2.0).

## Context

Roadmap (approved): publish Thor's in-house libsu fork **Odin** (`:suCore`, namespace `com.valhalla.superuser`) to Maven Central as **`com.trinadhthatakula:odin`**. Three phases: **1 (done)** prep `:suCore` in Thor; **2 (this spec)** extract to a standalone repo + make it publish-ready + push; **3** migrate Thor to the published dep.

**This phase is a verbatim, build-config-only port** — no code or behavior changes to the `com.valhalla.superuser` sources. Shell-execution modernization (analyzed in `docs/audits/2026-07-21-odin-shell-execution-modernization.md`) is explicitly future work in the new repo, NOT part of this phase.

**Template choice:** `thor-extension-api` (`/Users/trinadhthatakula/StudioProjects/thor-extension-api`), not Asgard. Asgard is Kotlin-Multiplatform; Odin is a pure Android library, and `thor-extension-api` is the exact analog — a single `com.android.library` module published via vanniktech, using **AGP-9 built-in Kotlin** (no explicit `org.jetbrains.kotlin.android` plugin), with CI on a `production` branch.

## Locked decisions

- **Namespace:** keep `com.valhalla.superuser` (verbatim port → zero Thor import churn at Phase 3).
- **Port method:** fresh copy, no git history (authoritative history stays in Thor).
- **minSdk:** 24 (unchanged from current `:suCore`).
- **First version:** `VERSION_NAME=1.0.0-SNAPSHOT` (honest "unreleased" marker).
- **Kotlin:** AGP-9 built-in Kotlin (as both `:suCore` and `thor-extension-api` do today) — do NOT apply `org.jetbrains.kotlin.android` explicitly.
- **License:** Apache-2.0.

## Goal (Phase 2)

Stand up `/Users/trinadhthatakula/StudioProjects/Odin` (→ GitHub `trinadhthatakula/Odin`), port `:suCore` into a `:odin` module, make it build standalone and be publish-ready as `com.trinadhthatakula:odin`, verify locally (`assembleRelease` + `publishToMavenLocal`), then push `main` to a new remote. Publishing to Maven Central is a later, deliberate `main→production` push — NOT this phase.

## Architecture / repo layout

```
Odin/                                   → GitHub trinadhthatakula/Odin
  settings.gradle.kts                   rootProject.name="Odin"; include(":odin"); FAIL_ON_PROJECT_REPOS
  build.gradle.kts                      android-library + vanniktech-maven-publish, apply false
  gradle.properties                     GROUP=com.trinadhthatakula; VERSION_NAME=1.0.0-SNAPSHOT
  gradle/libs.versions.toml
  gradle/wrapper/… (Gradle 9.6.0) + gradle/gradle-daemon-jvm.properties (toolchainVersion=21) + gradlew
  LICENSE (Apache-2.0), README.md, .gitignore, docs/PUBLISHING.md (adapted from template)
  .github/workflows/publish.yml, .github/workflows/pr-ci.yml
  odin/
    build.gradle.kts
    proguard-rules.pro                  (consumer rules, ported from suCore)
    src/main/java/com/valhalla/superuser/**   (ported verbatim)
    src/main/aidl/com/valhalla/superuser/**   (IIPC.aidl, IRootServiceManager.aidl)
```

Single library module `:odin` → artifactId `odin`. Classic Android layout (`src/main/java` + `src/main/aidl`), matching the current `:suCore` module. No sample/demo app this phase.

## Components / files

### Ported verbatim from Thor `:suCore` (no content changes)
- `suCore/src/main/java/com/valhalla/superuser/**` → `odin/src/main/java/com/valhalla/superuser/**` (Shell, `internal/` engine, `ipc/RootService`, `ktx/`, `utils/`).
- `suCore/src/main/aidl/com/valhalla/superuser/**` → `odin/src/main/aidl/com/valhalla/superuser/**` (`IIPC.aidl`, `IRootServiceManager.aidl`).
- `suCore/proguard-rules.pro` → `odin/proguard-rules.pro`.
- `suCore/LICENSE` (Apache-2.0) → repo-root `LICENSE`.

Self-contained: `:suCore`'s only external deps are `androidx.core.ktx` + `kotlinx-coroutines-android` (no `:bypass`/`:vm-runtime`/other local modules), verified against `suCore/build.gradle.kts`.

### Build-config deltas vs current `suCore/build.gradle.kts` (exactly two)
1. **Drop the empty `foss_release` build type** → publish the standard `release` variant.
2. **coroutines `implementation` → `api`** (public API exposes `suspend` fns + `Flow`). Keep the `-android` artifact — `internal/UiThreadHandler` uses `Dispatchers.Main`, so switching to `-core` risks a runtime regression; this is a dependency-scope change only.

(Unchanged: built-in Kotlin, `namespace`, minSdk 24, compileSdk 37, JVM 21, `buildFeatures { aidl = true; buildConfig = true }`, `consumerProguardFiles`.)

### `odin/build.gradle.kts`
```kotlin
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
android {
    namespace = "com.valhalla.superuser"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = 24; consumerProguardFiles("proguard-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    buildFeatures { aidl = true; buildConfig = true }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
}
mavenPublishing {
    coordinates(
        groupId = providers.gradleProperty("GROUP").get(),
        artifactId = "odin",
        version = providers.gradleProperty("VERSION_NAME").get()
    )
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name.set("Odin")
        description.set("Kotlin-first root shell + RootService IPC for Android (in-house libsu fork).")
        inceptionYear.set("2026")
        url.set("https://github.com/trinadhthatakula/Odin")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer { id.set("trinadhthatakula"); name.set("Trinadh Thatakula"); url.set("https://github.com/trinadhthatakula") }
        }
        scm {
            url.set("https://github.com/trinadhthatakula/Odin")
            connection.set("scm:git:https://github.com/trinadhthatakula/Odin.git")
            developerConnection.set("scm:git:ssh://git@github.com/trinadhthatakula/Odin.git")
        }
    }
}
```

### Root `build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}
```

### `settings.gradle.kts`
```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Odin"
include(":odin")
```

### `gradle.properties`
```properties
org.gradle.jvmargs=-Xmx2g -XX:+UseG1GC
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official

# Maven Central coordinates for Odin
GROUP=com.trinadhthatakula
VERSION_NAME=1.0.0-SNAPSHOT
```

### `gradle/libs.versions.toml`
```toml
[versions]
agp = "9.4.0-alpha05"
kotlin = "2.4.10"
compileSdk = "37"
coreKtx = "1.19.0"
kotlinxCoroutines = "1.10.2"
vanniktechMavenPublish = "0.34.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
vanniktech-maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktechMavenPublish" }
```
Versions are pinned to Thor's current `gradle/libs.versions.toml` (`agp = 9.4.0-alpha05`, `kotlin = 2.4.10`, `coreKtx = 1.19.0`, `kotlinxCoroutines = 1.10.2`). No compose/coil/kotlin-android entries (unlike the template's leftovers). **Built-in-Kotlin note:** `:suCore` compiles today via AGP-9 built-in Kotlin with no `kotlin` plugin and no `kotlin` version reference in its own build file — in the monorepo the KGP on the shared build classpath (from `:app`) is 2.4.10. Standalone, Odin's built-in Kotlin uses the version AGP `9.4.0-alpha05` bundles. The implementation plan MUST verify `:odin:assembleRelease` compiles the ported sources on that bundled Kotlin; only if a version-specific compile error surfaces does it pin the built-in Kotlin version to `2.4.10` (via the AGP-9 built-in-Kotlin version mechanism). The `kotlin = "2.4.10"` catalog entry is provided for that pin.

### CI — `.github/workflows/publish.yml` (verbatim from `thor-extension-api`)
```yaml
name: Publish to Maven Central
on:
  push:
    branches: [ production ]
concurrency:
  group: publish-maven-central
  cancel-in-progress: false
permissions:
  contents: read
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: zulu, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - name: Publish to Maven Central
        run: ./gradlew publishToMavenCentral --no-configuration-cache --stacktrace
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_IN_MEMORY_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_IN_MEMORY_KEY_PASSWORD }}
```

### CI — `.github/workflows/pr-ci.yml` (small addition beyond the template)
On `pull_request` to `main`: JDK 21 (zulu) + `gradle/actions/setup-gradle@v4` + `./gradlew :odin:assembleRelease --stacktrace`. A cheap build-gate for a module more complex than plain interface declarations. (The template has no PR-CI; this is an intentional, low-cost value-add.)

## Data flow / safety

Publishing fires **only on push to `production`**. This phase pushes the port to **`main`**, so publishing cannot occur regardless of version or configured secrets. The `1.0.0-SNAPSHOT` version is an honest unreleased marker; the real 1.0.0 Central release is a deliberate later `main→production` push (after the shell-modernization API shaping). Local `publishToMavenLocal` is used for verification and does not touch Central.

## Testing / verification

- `./gradlew :odin:assembleRelease` — green standalone (validates the AGP-9 built-in-Kotlin + AIDL + coroutines-`api` port).
- `./gradlew publishToMavenLocal` — produces `com.trinadhthatakula:odin:1.0.0-SNAPSHOT` in `~/.m2` **with a sources jar and a javadoc jar** and a POM carrying the Apache-2.0 license, developers, and scm blocks. Inspect the generated POM.
- Create `trinadhthatakula/Odin` (`gh repo create`), push **`main`** (never `production` this phase).
- (Deferred: a consumer smoke-test from `mavenLocal` — belongs to Phase 3.)

## Out of scope (future work in the new repo)

- Shell-execution modernization Steps A–H (`docs/audits/2026-07-21-odin-shell-execution-modernization.md`).
- `explicitApi()` + binary-compatibility-validator (part of the modernization's pre-freeze gate).
- The `ShellResult`/`exec()` API reshape (must land before the real 1.0.0 release).
- The actual Maven Central release (`main→production`).
- Thor's Phase 3 migration to the published dep (catalog + `includeBuild` override + delete local `:suCore`).
- Any sample/demo app.

**Sequence going forward:** port → push `main` (`1.0.0-SNAPSHOT`) → shell-modernization shapes the API → flip `VERSION_NAME` to `1.0.0` + push `production` to release → Thor Phase 3.
