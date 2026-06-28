# Publish `thor-extension-api` to Maven Central — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract Thor's `:extension-api` module into a standalone GitHub repo and publish it to Maven Central as `io.github.trinadhthatakula:thor-extension-api:1.0.0`, then rewire the Thor app to consume the published artifact.

**Architecture:** A new sibling Gradle project (`../thor-extension-api`) holds the moved API sources and a Vanniktech publishing config targeting Sonatype's Central Portal. Thor depends on the published coordinate via the version catalog, with an optional Gradle composite build (`includeBuild`) that substitutes a local checkout for fast cross-repo development.

**Tech Stack:** Gradle 9.6.0, AGP 9.4.0-alpha02 (built-in Kotlin), Kotlin 2.4.0, Jetpack Compose (BOM 2026.06.00), Coil3 3.5.0, Vanniktech `com.vanniktech.maven.publish` 0.34.0, JDK 21.

## Global Constraints

- **JDK 21** (Zulu) for all builds — `sourceCompatibility`/`targetCompatibility = VERSION_21`, `jvmTarget = JVM_21`.
- **compileSdk 37, minSdk 28** (copied from Thor's catalog).
- **Namespace stays `com.valhalla.thor.extension.api`** — moving sources must not change package or namespace (host loads classes by name).
- **GAV:** `io.github.trinadhthatakula` : `thor-extension-api` : `1.0.0` (independent SemVer).
- **Dependency scoping rule:** types in public API signatures → `api`; internal-only → `implementation`. Compose (`api`), Coil (`api`), core-ktx (`implementation`).
- **No secrets in any repo** — Central Portal token + GPG key live only in `~/.gradle/gradle.properties` or env vars.
- **Two repos:** new-repo work happens in `/Users/trinadhthatakula/StudioProjects/thor-extension-api`; Thor work in `/Users/trinadhthatakula/StudioProjects/Thor`. Each task states its working dir. Commits go to the repo named in the task.
- Vanniktech manages GPG signing itself — do **not** apply the `signing` plugin manually.

---

### Task 1: Scaffold the standalone `thor-extension-api` Gradle project

**Working dir:** `/Users/trinadhthatakula/StudioProjects/thor-extension-api` (create it)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `.gitignore`, `gradle/libs.versions.toml`
- Copy from Thor: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `LICENSE`

**Interfaces:**
- Produces: a buildable empty Gradle project with the version catalog aliases `libs.plugins.android.library`, `libs.plugins.kotlin.compose`, `libs.plugins.vanniktech.maven.publish`, and `libs.versions.compileSdk` / `minSdk`, consumed by Task 2 and Task 3.

- [ ] **Step 1: Create the directory and copy the Gradle wrapper + license from Thor**

```bash
mkdir -p /Users/trinadhthatakula/StudioProjects/thor-extension-api/gradle/wrapper
cd /Users/trinadhthatakula/StudioProjects/thor-extension-api
cp /Users/trinadhthatakula/StudioProjects/Thor/gradlew .
cp /Users/trinadhthatakula/StudioProjects/Thor/gradlew.bat .
cp /Users/trinadhthatakula/StudioProjects/Thor/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/
cp /Users/trinadhthatakula/StudioProjects/Thor/gradle/wrapper/gradle-wrapper.properties gradle/wrapper/
cp /Users/trinadhthatakula/StudioProjects/Thor/LICENSE .
chmod +x gradlew
```

- [ ] **Step 2: Create `settings.gradle.kts`**

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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "thor-extension-api"
include(":extension-api")
```

- [ ] **Step 3: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "9.4.0-alpha02"
kotlin = "2.4.0"
coreKtx = "1.19.0"
composeBom = "2026.06.00"
coil3 = "3.5.0"
compileSdk = "37"
minSdk = "28"
vanniktechMavenPublish = "0.34.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil3" }

[plugins]
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
vanniktech-maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktechMavenPublish" }
```

- [ ] **Step 4: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2g -XX:+UseG1GC
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official

# Maven Central coordinates for thor-extension-api (independent of Thor's versionCode)
GROUP=io.github.trinadhthatakula
VERSION_NAME=1.0.0
```

- [ ] **Step 5: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}
```

- [ ] **Step 6: Create `.gitignore`**

```gitignore
.gradle/
build/
local.properties
.idea/
*.iml
.kotlin/
captures/
.DS_Store
```

- [ ] **Step 7: Verify Gradle resolves the project and plugins**

Run (from the new repo dir):
```bash
./gradlew projects --no-configuration-cache
```
Expected: BUILD SUCCESSFUL, listing root project `thor-extension-api` and `+--- Project ':extension-api'`. If the Vanniktech plugin version `0.34.0` fails to resolve, find the latest at https://github.com/vanniktech/gradle-maven-publish-plugin/releases and update `vanniktechMavenPublish` in `gradle/libs.versions.toml`, then re-run.

> Note: `:extension-api` has no build script yet, so `projects` works but `build` will not — that's expected until Task 2.

- [ ] **Step 8: Initialize git and commit the scaffold**

```bash
cd /Users/trinadhthatakula/StudioProjects/thor-extension-api
git init -q
git add -A
git commit -q -m "chore: scaffold standalone thor-extension-api Gradle project"
```

---

### Task 2: Move the API sources into the `:extension-api` module

**Working dir:** `/Users/trinadhthatakula/StudioProjects/thor-extension-api`

**Files:**
- Create: `extension-api/build.gradle.kts`
- Copy: the 7 source files from Thor into `extension-api/src/main/java/com/valhalla/thor/extension/api/`

**Interfaces:**
- Consumes: catalog aliases from Task 1.
- Produces: a buildable Android library with namespace `com.valhalla.thor.extension.api` exposing `ThorExtension`, `DebloatExtension`, `AutomationExtension`, `ShellExecutor`, `ExtensionDataStore`, `Logger`, `AppIcon`/`AppIconModel`. Consumed by Task 3 (publishing) and Thor (Task 5).

- [ ] **Step 1: Copy the source files verbatim**

```bash
cd /Users/trinadhthatakula/StudioProjects/thor-extension-api
mkdir -p extension-api/src/main/java/com/valhalla/thor/extension/api
cp /Users/trinadhthatakula/StudioProjects/Thor/extension-api/src/main/java/com/valhalla/thor/extension/api/*.kt \
   extension-api/src/main/java/com/valhalla/thor/extension/api/
ls extension-api/src/main/java/com/valhalla/thor/extension/api/
```
Expected: `AppIcon.kt AutomationExtension.kt DebloatExtension.kt ExtensionDataStore.kt Logger.kt ShellExecutor.kt ThorExtension.kt`

- [ ] **Step 2: Create `extension-api/build.gradle.kts` with corrected dependency scopes (no publishing yet)**

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    namespace = "com.valhalla.thor.extension.api"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // api: Compose types appear in public signatures (@Composable ConfigurationScreen, Modifier, ColorFilter).
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    // api: backs the public AppIcon() helper so consumers can use it directly.
    api(libs.coil.compose)
}
```

- [ ] **Step 3: Build the release variant to verify it compiles**

Run:
```bash
./gradlew :extension-api:assembleRelease --no-configuration-cache
```
Expected: BUILD SUCCESSFUL; produces `extension-api/build/outputs/aar/extension-api-release.aar`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -q -m "feat: move thor-extension-api sources into standalone library module"
```

---

### Task 3: Add Vanniktech publishing config and POM

**Working dir:** `/Users/trinadhthatakula/StudioProjects/thor-extension-api`

**Files:**
- Modify: `extension-api/build.gradle.kts`

**Interfaces:**
- Consumes: the library module from Task 2; catalog alias `libs.plugins.vanniktech.maven.publish`; `GROUP`/`VERSION_NAME` from `gradle.properties`.
- Produces: a `publishToMavenLocal` / `publishToMavenCentral` capable build emitting `io.github.trinadhthatakula:thor-extension-api:1.0.0` (AAR + sources + javadoc + signed POM).

- [ ] **Step 1: Add the Vanniktech plugin to the module's `plugins {}` block**

In `extension-api/build.gradle.kts`, change the plugins block to:
```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.vanniktech.maven.publish)
}
```

- [ ] **Step 2: Add imports and the `mavenPublishing {}` block at the end of `extension-api/build.gradle.kts`**

Add these imports at the very top of the file (above the existing `import org.jetbrains.kotlin...`):
```kotlin
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
```

Append this block at the end of the file:
```kotlin
mavenPublishing {
    coordinates(
        groupId = providers.gradleProperty("GROUP").get(),
        artifactId = "thor-extension-api",
        version = providers.gradleProperty("VERSION_NAME").get()
    )
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true
        )
    )
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name.set("Thor Extension API")
        description.set("Contract interfaces for building extensions for the Thor app manager.")
        inceptionYear.set("2026")
        url.set("https://github.com/trinadhthatakula/thor-extension-api")
        licenses {
            license {
                name.set("GNU General Public License v3.0 or later")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("trinadhthatakula")
                name.set("Trinadh Thatakula")
                url.set("https://github.com/trinadhthatakula")
            }
        }
        scm {
            url.set("https://github.com/trinadhthatakula/thor-extension-api")
            connection.set("scm:git:https://github.com/trinadhthatakula/thor-extension-api.git")
            developerConnection.set("scm:git:ssh://git@github.com/trinadhthatakula/thor-extension-api.git")
        }
    }
}
```

> The `signing` plugin is NOT applied manually — Vanniktech applies it. `publishToMavenLocal` does not require GPG keys (signing is only enforced for remote publishing), so this task is verifiable without any credentials. Confirm the GPLv3 SPDX wording matches the license headers in the Thor source before first remote publish.

- [ ] **Step 3: Publish to the local Maven repo**

Run:
```bash
./gradlew :extension-api:publishToMavenLocal --no-configuration-cache
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Inspect the generated POM for correct coordinates and dependency scopes**

Run:
```bash
cat ~/.m2/repository/io/github/trinadhthatakula/thor-extension-api/1.0.0/thor-extension-api-1.0.0.pom
```
Expected, verify by eye:
- `<groupId>io.github.trinadhthatakula</groupId>`, `<artifactId>thor-extension-api</artifactId>`, `<version>1.0.0</version>`
- `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, `<scm>` all present
- Compose UI and `coil-compose` appear as `<scope>compile</scope>` dependencies; `core-ktx` as `<scope>runtime</scope>`
- Sibling files exist: `...-1.0.0.aar`, `...-1.0.0-sources.jar`, `...-1.0.0-javadoc.jar`

```bash
ls ~/.m2/repository/io/github/trinadhthatakula/thor-extension-api/1.0.0/
```

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -q -m "feat: add Maven Central publishing via vanniktech plugin"
```

---

### Task 4: Write consumer README and maintainer publishing runbook

**Working dir:** `/Users/trinadhthatakula/StudioProjects/thor-extension-api`

**Files:**
- Create: `README.md`, `docs/PUBLISHING.md`

**Interfaces:**
- Consumes: nothing (documentation).
- Produces: developer-facing usage docs and the maintainer release runbook referenced by Task 6.

- [ ] **Step 1: Create `README.md`**

````markdown
# Thor Extension API

Contract interfaces for building extensions for the [Thor](https://codeberg.org/trinadh/thor) Android app manager.

## Add the dependency

```kotlin
dependencies {
    // Thor loads extensions into its own process and provides these classes at runtime,
    // so depend on the API as compileOnly — do NOT bundle it into your extension APK.
    compileOnly("io.github.trinadhthatakula:thor-extension-api:1.0.0")
}
```

## Declare your extension

An extension is a normal Android APK with **no launcher activity**. Its package name must start with
`com.valhalla.thor.ext.` and its manifest points Thor at your implementation class:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:hasCode="true">
        <meta-data
            android:name="thor.extension.class"
            android:value="com.valhalla.thor.ext.sample.SampleDebloatExtension" />
        <meta-data
            android:name="thor.extension.api.version"
            android:value="1" />
    </application>
</manifest>
```

## Minimal example

```kotlin
package com.valhalla.thor.ext.sample

import com.valhalla.thor.extension.api.DebloatExtension
import com.valhalla.thor.extension.api.ExtensionDebloatItem

class SampleDebloatExtension : DebloatExtension {
    override val name = "Sample Debloat List"
    override val description = "Example manufacturer debloat list"
    override val version = "1.0.0"
    override val author = "you"
    override val targetManufacturer = "Generic"

    override fun getDebloatItems(): List<ExtensionDebloatItem> = listOf(
        ExtensionDebloatItem(
            packageName = "com.example.bloat",
            recommendation = "recommended",
            description = "Removable sample bloatware"
        )
    )
}
```

## Current limitation: signature trust

On **release** builds, Thor currently loads only extensions signed with Thor's own signing key.
Third-party extensions signed with your own key load on **debug / self-built** Thor today; loading
third-party-signed extensions on the official release build is a planned future change.
````

- [ ] **Step 2: Create `docs/PUBLISHING.md` (maintainer runbook)**

````markdown
# Publishing `thor-extension-api` to Maven Central

## One-time setup

### 1. Central Portal account + namespace
1. Create an account at https://central.sonatype.com.
2. Register the namespace `io.github.trinadhthatakula`.
3. Verify it: the portal gives you a verification token. Create a **public** GitHub repository named
   exactly that token under the `trinadhthatakula` GitHub account, click verify, then delete the repo.

### 2. User token
In the portal, generate a user token. It yields a username + password pair used as
`mavenCentralUsername` / `mavenCentralPassword`.

### 3. GPG signing key
```bash
gpg --gen-key                                  # create a key (RSA 4096)
gpg --list-secret-keys --keyid-format=long     # note the KEY_ID
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>   # publish the public key
gpg --armor --export-secret-keys <KEY_ID>      # copy the ascii-armored private key
```

### 4. Local credentials (never commit these)
Add to `~/.gradle/gradle.properties`:
```properties
mavenCentralUsername=<token username>
mavenCentralPassword=<token password>
signingInMemoryKey=<ascii-armored private key, newlines as \n>
signingInMemoryKeyPassword=<key passphrase>
```

## Per-release

1. Bump `VERSION_NAME` in `gradle.properties` (SemVer). Bump the runtime contract integer
   `thor.extension.api.version` only on a breaking contract change (and the library MAJOR with it).
2. Publish:
   ```bash
   ./gradlew publishToMavenCentral --no-configuration-cache
   ```
3. With `automaticRelease = true` the deployment promotes itself; otherwise confirm it in the portal.
4. Verify the artifact appears at
   https://repo1.maven.org/maven2/io/github/trinadhthatakula/thor-extension-api/ (allow time for sync).
````

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -q -m "docs: add consumer README and maintainer publishing runbook"
```

---

### Task 5: Rewire the Thor app to consume the published artifact

**Working dir:** `/Users/trinadhthatakula/StudioProjects/Thor`

**Files:**
- Modify: `settings.gradle.kts` (remove `include(":extension-api")`, add composite-build toggle)
- Modify: `gradle/libs.versions.toml` (add coordinate)
- Modify: `app/build.gradle.kts:207`
- Delete: `extension-api/` module directory

**Interfaces:**
- Consumes: the published coordinate `io.github.trinadhthatakula:thor-extension-api:1.0.0` (or the local included build).
- Produces: a Thor app that compiles against the external API artifact; `libs.thor.extension.api` catalog accessor.

- [ ] **Step 1: Add the coordinate to Thor's version catalog**

In `/Users/trinadhthatakula/StudioProjects/Thor/gradle/libs.versions.toml`, add under `[versions]`:
```toml
thorExtensionApi = "1.0.0"
```
and under `[libraries]`:
```toml
thor-extension-api = { module = "io.github.trinadhthatakula:thor-extension-api", version.ref = "thorExtensionApi" }
```

- [ ] **Step 2: Swap the app dependency**

In `/Users/trinadhthatakula/StudioProjects/Thor/app/build.gradle.kts`, replace line 207:
```kotlin
    implementation(project(":extension-api"))
```
with:
```kotlin
    implementation(libs.thor.extension.api)
```

- [ ] **Step 3: Remove the module include and add the composite-build toggle**

In `/Users/trinadhthatakula/StudioProjects/Thor/settings.gradle.kts`, delete the line:
```kotlin
include(":extension-api")
```
Then add, immediately after the `buildCache { ... }` block (top level, outside any other block):
```kotlin
// Local cross-repo development: set `thorExtensionApiDir` (in ~/.gradle/gradle.properties or
// local.properties) to a local thor-extension-api checkout to build against its source without
// publishing. Gradle substitutes the published io.github.trinadhthatakula:thor-extension-api
// dependency with the local build automatically. Leave it unset to use the pinned published version.
val thorExtensionApiDir = providers.gradleProperty("thorExtensionApiDir").orNull
if (thorExtensionApiDir != null) {
    includeBuild(thorExtensionApiDir)
}
```

- [ ] **Step 4: Delete the now-migrated local module**

```bash
cd /Users/trinadhthatakula/StudioProjects/Thor
git rm -r -q extension-api
```

- [ ] **Step 5: Point Thor at the local API build and verify it compiles against external source**

Add to `/Users/trinadhthatakula/StudioProjects/Thor/local.properties` (untracked):
```properties
thorExtensionApiDir=/Users/trinadhthatakula/StudioProjects/thor-extension-api
```
Run:
```bash
./gradlew :app:assembleFossDebug --no-configuration-cache
```
Expected: BUILD SUCCESSFUL. Gradle reports it is building `:extension-api` from the included build `thor-extension-api`. This proves the host still compiles against the externalized API.

> If `local.properties` is not honored for settings-level properties on this Gradle version, set the property in `~/.gradle/gradle.properties` instead, or run with `-PthorExtensionApiDir=/Users/trinadhthatakula/StudioProjects/thor-extension-api`.

- [ ] **Step 6: Commit (Thor repo)**

```bash
git add -A
git commit -m "refactor: consume externalized thor-extension-api dependency

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: First publish to Maven Central and verify the published path (maintainer, manual)

**Working dir:** `/Users/trinadhthatakula/StudioProjects/thor-extension-api`

**Files:** none (uses credentials in `~/.gradle/gradle.properties`).

**Interfaces:**
- Consumes: the publishing config from Task 3; the runbook from Task 4.
- Produces: `io.github.trinadhthatakula:thor-extension-api:1.0.0` live on Maven Central, resolvable by Thor without the composite build.

- [ ] **Step 1: Complete one-time Central Portal + GPG setup**

Follow `docs/PUBLISHING.md` sections "1. Central Portal account + namespace" through "4. Local credentials". This is a manual maintainer action (account creation, namespace verification via a throwaway GitHub repo, GPG key generation, writing credentials to `~/.gradle/gradle.properties`).

- [ ] **Step 2: Publish to Maven Central**

Run:
```bash
cd /Users/trinadhthatakula/StudioProjects/thor-extension-api
./gradlew publishToMavenCentral --no-configuration-cache
```
Expected: BUILD SUCCESSFUL; the deployment appears (and, with `automaticRelease = true`, promotes) in the Central Portal.

- [ ] **Step 3: Verify the artifact is live**

After Maven Central sync (can take up to ~30 min), check:
```bash
curl -sI https://repo1.maven.org/maven2/io/github/trinadhthatakula/thor-extension-api/1.0.0/thor-extension-api-1.0.0.pom | head -1
```
Expected: `HTTP/2 200`.

- [ ] **Step 4: Verify Thor resolves the published artifact (no composite build)**

```bash
cd /Users/trinadhthatakula/StudioProjects/Thor
# Temporarily disable the local override:
./gradlew :app:assembleFossDebug -PthorExtensionApiDir= --no-configuration-cache
```
(Alternatively, comment out `thorExtensionApiDir` in `local.properties`.)
Expected: BUILD SUCCESSFUL, with the dependency resolved from Maven Central (no included build line in the output).

> Until this task completes, other developers must keep `thorExtensionApiDir` set (composite build) or the Thor build cannot resolve `1.0.0`.

---

### Task 7 (optional, requires a device): Extension-loading smoke test

**Working dir:** `/Users/trinadhthatakula/StudioProjects/Thor`

**Files:** none (runtime verification).

**Interfaces:**
- Consumes: a debug Thor build and any sample extension APK built against the published API.
- Produces: confirmation that `ExtensionManager.loadExtensions()` still discovers and instantiates extensions after the migration.

- [ ] **Step 1: Build and install debug Thor**

```bash
./gradlew :app:assembleFossDebug --no-configuration-cache
adb install -r app/build/outputs/apk/foss/debug/foss-debug.apk
```
Expected: `Success`.

- [ ] **Step 2: Build a sample extension APK and install it**

Build any minimal extension whose package starts with `com.valhalla.thor.ext.` and whose manifest declares `thor.extension.class` (see the README example from Task 4). Install it:
```bash
adb install -r <sample-extension>.apk
```
Expected: `Success`. (Debug Thor relaxes the signature check, so a self-signed sample loads.)

- [ ] **Step 3: Verify discovery at runtime**

Open Thor → Extension Manager screen. Confirm the sample extension appears in the loaded list.
Expected: the sample's `name`/`description` render, proving `PathClassLoader` instantiation against the externalized API still works.

---

## Self-Review

**Spec coverage:**
- Extract to standalone repo → Tasks 1–2. ✅
- Vanniktech publishing + Central Portal + POM → Task 3. ✅
- Independent SemVer `1.0.0` → Task 1 (`gradle.properties`), Task 3 (coordinates). ✅
- Dependency-scope fix (Compose/Coil `api`, core-ktx `implementation`) → Task 2 Step 2. ✅
- `compileOnly` consumer pattern → README, Task 4. ✅
- Local publishing setup (no secrets) → Task 4 runbook, Task 6. ✅
- Rewire Thor + composite-build toggle → Task 5. ✅
- Consumer README + maintainer runbook → Task 4. ✅
- First local publish then Central → Tasks 3 (local), 6 (Central). ✅
- Deferred trust redesign → noted in README + plan (out of scope, no task). ✅
- Verification (build, POM inspect, round-trip via Thor compile, extension load) → Tasks 2–7. ✅

**Type/naming consistency:** namespace `com.valhalla.thor.extension.api` unchanged across all tasks; coordinate `io.github.trinadhthatakula:thor-extension-api:1.0.0` identical in catalog, POM, README, runbook; catalog accessor `libs.thor.extension.api` matches the `thor-extension-api` library key; property name `thorExtensionApiDir` identical in settings, local.properties, and verification commands. ✅

**Placeholder scan:** no TBD/TODO; the only soft value is the Vanniktech version `0.34.0`, which Task 1 Step 7 explicitly verifies and bumps if needed. ✅
