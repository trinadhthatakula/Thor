# Odin Phase 2 — Standalone Repo + Basic `:suCore` Port — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a standalone `Odin` repo at `/Users/trinadhthatakula/StudioProjects/Odin` and verbatim-port Thor's `:suCore` into a `:odin` module so it builds standalone, is publish-ready as `com.trinadhthatakula:odin:1.0.0-SNAPSHOT`, and is pushed to a new GitHub remote (`main` only).

**Architecture:** Copy `thor-extension-api`'s Android-library publishing scaffold (vanniktech, AGP-9 built-in Kotlin, CI on `production`), swap in Odin's coordinates + Apache-2.0, then fresh-copy the `com.valhalla.superuser` sources/AIDL/assets/proguard verbatim. Only two build-config deltas vs the current `:suCore`: drop the empty `foss_release` build type, and change the coroutines dependency scope from `implementation` to `api`.

**Tech Stack:** Gradle 9.6.0 (JDK 21), AGP 9.4.0-alpha05 (built-in Kotlin), `com.vanniktech.maven.publish` 0.34.0, `gh` CLI.

## Global Constraints

- **Verbatim port:** the `com.valhalla.superuser` sources, AIDL, assets, and `proguard-rules.pro` are copied UNCHANGED. No code/behavior edits. Namespace stays `com.valhalla.superuser`.
- **Only two build-config deltas** vs `Thor/suCore/build.gradle.kts`: (1) no `foss_release` build type; (2) coroutines `implementation` → `api`.
- **AGP-9 built-in Kotlin** — do NOT apply `org.jetbrains.kotlin.android`; use the `kotlin { compilerOptions { jvmTarget } }` DSL exactly as `:suCore` does today.
- **Pinned versions:** `agp = 9.4.0-alpha05`, `kotlin = 2.4.10`, `compileSdk = 37`, `minSdk = 24`, JVM 21, `coreKtx = 1.19.0`, `kotlinxCoroutines = 1.10.2`, `vanniktechMavenPublish = 0.34.0`.
- **Coordinates/license:** `com.trinadhthatakula:odin`, `VERSION_NAME = 1.0.0-SNAPSHOT`, Apache-2.0.
- **Publish safety:** CI publishes ONLY on push to `production`. This phase pushes `main` ONLY — NEVER `production`. No real Maven Central release happens here.
- **Build tool:** run Gradle in the new repo via the `mcp__plugin_context-mode_context-mode__ctx_execute` MCP tool (`language: "shell"`, e.g. `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew …`). A context-mode hook intercepts `./gradlew` run via the Bash tool. Load the tool via `ToolSearch "select:mcp__plugin_context-mode_context-mode__ctx_execute"` if absent. Use Bash for git/`gh`/file ops.
- **All git operations happen in the new `Odin` repo** (`/Users/trinadhthatakula/StudioProjects/Odin`), NOT in Thor. This plan document lives in Thor; the code it creates does not.
- **No TDD unit tests** are added — this is a build-config port of existing, already-shipped code with no injection seam. Verification is: standalone `assembleRelease` green, `publishToMavenLocal` produces a correct artifact + sources + javadoc + POM, and the push succeeds. Do not fabricate mock-only tests.
- **Out of scope (do NOT implement):** shell-execution modernization, `explicitApi()`/binary-compatibility-validator, the `ShellResult`/`exec()` reshape, the real Central release (`main→production`), Thor's Phase 3 migration, any sample/demo app.

## File Structure (created in `/Users/trinadhthatakula/StudioProjects/Odin`)

```
Odin/
  .gitignore                              (authored — matches thor-extension-api)
  LICENSE                                 (copied from Thor/suCore/LICENSE — Apache-2.0)
  README.md                               (authored fresh for Odin)
  settings.gradle.kts                     (authored)
  build.gradle.kts                        (authored — root, plugins apply false)
  gradle.properties                       (authored)
  gradlew, gradlew.bat                    (copied from thor-extension-api)
  gradle/
    libs.versions.toml                    (authored)
    gradle-daemon-jvm.properties          (copied from thor-extension-api)
    wrapper/gradle-wrapper.jar            (copied from thor-extension-api)
    wrapper/gradle-wrapper.properties     (copied from thor-extension-api — Gradle 9.6.0)
  docs/PUBLISHING.md                      (copied from thor-extension-api + adapted)
  .github/workflows/publish.yml           (authored — verbatim from thor-extension-api)
  .github/workflows/pr-ci.yml             (authored — minimal build gate)
  odin/
    build.gradle.kts                      (authored — the 2 deltas baked in)
    proguard-rules.pro                    (copied from Thor/suCore/proguard-rules.pro)
    src/                                  (copied WHOLESALE from Thor/suCore/src — java + aidl + assets)
```

Path shorthand used below: `OD = /Users/trinadhthatakula/StudioProjects/Odin`, `SU = /Users/trinadhthatakula/StudioProjects/Thor/suCore`, `TEA = /Users/trinadhthatakula/StudioProjects/thor-extension-api`.

---

### Task 1: Standalone repo that builds + is publish-ready to mavenLocal

**Files:**
- Create: `OD/` (git repo), `OD/settings.gradle.kts`, `OD/build.gradle.kts`, `OD/gradle.properties`, `OD/gradle/libs.versions.toml`, `OD/.gitignore`, `OD/odin/build.gradle.kts`
- Copy: gradle wrapper (`OD/gradlew`, `OD/gradlew.bat`, `OD/gradle/wrapper/*`, `OD/gradle/gradle-daemon-jvm.properties`) from `TEA`; `OD/LICENSE` + `OD/odin/proguard-rules.pro` from `SU`; `OD/odin/src/**` wholesale from `SU/src`

**Interfaces:**
- Produces: a Gradle project `Odin` with module `:odin` (namespace `com.valhalla.superuser`, artifactId `odin`) that `assembleRelease`s green and publishes `com.trinadhthatakula:odin:1.0.0-SNAPSHOT` to `~/.m2` with sources + javadoc + an Apache-2.0 POM.

- [ ] **Step 1: Create the repo directory + git init (default branch `main`)**

```bash
mkdir -p /Users/trinadhthatakula/StudioProjects/Odin
git -C /Users/trinadhthatakula/StudioProjects/Odin init -b main
```

- [ ] **Step 2: Copy the Gradle wrapper + daemon toolchain from thor-extension-api**

```bash
OD=/Users/trinadhthatakula/StudioProjects/Odin
TEA=/Users/trinadhthatakula/StudioProjects/thor-extension-api
mkdir -p "$OD/gradle/wrapper"
cp "$TEA/gradlew" "$TEA/gradlew.bat" "$OD/"
cp "$TEA/gradle/wrapper/gradle-wrapper.jar" "$TEA/gradle/wrapper/gradle-wrapper.properties" "$OD/gradle/wrapper/"
cp "$TEA/gradle/gradle-daemon-jvm.properties" "$OD/gradle/"
chmod +x "$OD/gradlew"
```
Expected `OD/gradle/wrapper/gradle-wrapper.properties` contents (verify):
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionSha256Sum=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip
networkTimeout=10000
retries=0
retryBackOffMs=500
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

- [ ] **Step 3: Write `OD/.gitignore`**

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

- [ ] **Step 4: Write `OD/settings.gradle.kts`**

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

rootProject.name = "Odin"
include(":odin")
```

- [ ] **Step 5: Write `OD/build.gradle.kts` (root)**

```kotlin
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}
```

- [ ] **Step 6: Write `OD/gradle.properties`**

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

- [ ] **Step 7: Write `OD/gradle/libs.versions.toml`**

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
(The `kotlin` version is unreferenced by any plugin — it is present only so the built-in Kotlin can be pinned in Step 12 if a version-specific compile error surfaces.)

- [ ] **Step 8: Copy the Apache-2.0 LICENSE + consumer proguard rules from suCore**

```bash
OD=/Users/trinadhthatakula/StudioProjects/Odin
SU=/Users/trinadhthatakula/StudioProjects/Thor/suCore
cp "$SU/LICENSE" "$OD/LICENSE"
mkdir -p "$OD/odin"
cp "$SU/proguard-rules.pro" "$OD/odin/proguard-rules.pro"
```

- [ ] **Step 9: Port the sources WHOLESALE (verbatim, fresh copy)**

```bash
OD=/Users/trinadhthatakula/StudioProjects/Odin
SU=/Users/trinadhthatakula/StudioProjects/Thor/suCore
cp -R "$SU/src" "$OD/odin/src"
```
Then verify the copy (expected: 28 Kotlin files, 2 AIDL files, and the assets dir present; package path unchanged):
```bash
echo "kt: $(find "$OD/odin/src/main/java" -name '*.kt' | wc -l | tr -d ' ')  aidl: $(find "$OD/odin/src/main/aidl" -name '*.aidl' | wc -l | tr -d ' ')"
ls -d "$OD/odin/src/main/assets" 2>/dev/null && echo "assets present"
```
Expected: `kt: 28  aidl: 2` and `assets present`. The tree must contain `src/main/java/com/valhalla/superuser/{,internal,ipc,ktx,utils}` and `src/main/aidl/com/valhalla/superuser/{internal,ipc}`.

- [ ] **Step 10: Write `OD/odin/build.gradle.kts` (the two deltas baked in)**

```kotlin
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    namespace = "com.valhalla.superuser"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
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
            developer {
                id.set("trinadhthatakula")
                name.set("Trinadh Thatakula")
                url.set("https://github.com/trinadhthatakula")
            }
        }
        scm {
            url.set("https://github.com/trinadhthatakula/Odin")
            connection.set("scm:git:https://github.com/trinadhthatakula/Odin.git")
            developerConnection.set("scm:git:ssh://git@github.com/trinadhthatakula/Odin.git")
        }
    }
}
```

- [ ] **Step 11: Build the library standalone**

Run via `ctx_execute` (`language: "shell"`):
```
cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:assembleRelease --stacktrace
```
Expected: `BUILD SUCCESSFUL`, producing `odin/build/outputs/aar/odin-release.aar`.

- [ ] **Step 12: If (and only if) Step 11 failed with a Kotlin-version/compilation error — pin the built-in Kotlin version**

Standalone, the built-in Kotlin uses the version AGP `9.4.0-alpha05` bundles, which may differ from the monorepo's shared KGP `2.4.10`. ONLY if Step 11 produced a Kotlin language/compiler error (not an unrelated failure), pin it by applying the Kotlin Android plugin at the catalog `kotlin` version:
1. Add to `libs.versions.toml` `[plugins]`: `kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }`
2. Add to `OD/build.gradle.kts` root plugins: `alias(libs.plugins.kotlin.android) apply false`
3. Add to `OD/odin/build.gradle.kts` plugins: `alias(libs.plugins.kotlin.android)`
4. Re-run Step 11.
If Step 11 already passed, SKIP this step entirely (built-in Kotlin is sufficient — the preferred state).

- [ ] **Step 13: Verify publish-readiness against mavenLocal**

Run via `ctx_execute` (`language: "shell"`):
```
cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew publishToMavenLocal --no-configuration-cache --stacktrace
```
Expected: `BUILD SUCCESSFUL`. Then verify the artifact set + POM:
```
ls -1 ~/.m2/repository/com/trinadhthatakula/odin/1.0.0-SNAPSHOT/
```
Expected to include `odin-1.0.0-SNAPSHOT.aar`, `odin-1.0.0-SNAPSHOT-sources.jar`, `odin-1.0.0-SNAPSHOT-javadoc.jar`, and `odin-1.0.0-SNAPSHOT.pom`. Inspect the `.pom` and confirm: `<groupId>com.trinadhthatakula</groupId>`, `<artifactId>odin</artifactId>`, `<version>1.0.0-SNAPSHOT</version>`, the Apache-2.0 `<license>`, the developer + scm blocks, and that `kotlinx-coroutines-android` appears as a `compile`-scope dependency (proving the `api` scope) while `core-ktx` is `runtime` scope.

- [ ] **Step 14: Commit**

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git add -A
git commit -m "feat: standalone Odin repo + verbatim :suCore port (builds + publishes to mavenLocal)"
```

**Acceptance:** `:odin:assembleRelease` green standalone; `publishToMavenLocal` yields the four artifacts above with a correct Apache-2.0 POM at `com.trinadhthatakula:odin:1.0.0-SNAPSHOT`; coroutines is `compile`-scope (api) in the POM.

---

### Task 2: CI workflows + README + PUBLISHING doc

**Files:**
- Create: `OD/.github/workflows/publish.yml`, `OD/.github/workflows/pr-ci.yml`, `OD/README.md`
- Copy + adapt: `OD/docs/PUBLISHING.md` from `TEA/docs/PUBLISHING.md`

**Interfaces:**
- Consumes: the `:odin` module + coordinates from Task 1.
- Produces: a publish workflow gated on `production`, a PR build gate on `main`, and repo docs.

- [ ] **Step 1: Write `OD/.github/workflows/publish.yml` (verbatim from thor-extension-api)**

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
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: '21'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Publish to Maven Central
        run: ./gradlew publishToMavenCentral --no-configuration-cache --stacktrace
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_IN_MEMORY_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_IN_MEMORY_KEY_PASSWORD }}
```

- [ ] **Step 2: Write `OD/.github/workflows/pr-ci.yml` (minimal build gate)**

```yaml
name: PR CI

on:
  pull_request:
    branches: [ main ]

concurrency:
  group: pr-ci-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: zulu
          java-version: '21'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Assemble release
        run: ./gradlew :odin:assembleRelease --stacktrace
```

- [ ] **Step 3: Copy + adapt `docs/PUBLISHING.md`**

```bash
OD=/Users/trinadhthatakula/StudioProjects/Odin
TEA=/Users/trinadhthatakula/StudioProjects/thor-extension-api
mkdir -p "$OD/docs"
cp "$TEA/docs/PUBLISHING.md" "$OD/docs/PUBLISHING.md"
```
Then edit `OD/docs/PUBLISHING.md` so every reference matches Odin: replace the artifact name `thor-extension-api` → `odin`, the coordinate `com.trinadhthatakula:thor-extension-api` → `com.trinadhthatakula:odin`, the repo URL `trinadhthatakula/Thor-extension-api` → `trinadhthatakula/Odin`, and confirm the release flow it documents is "bump `VERSION_NAME` on `main`, then push/merge to `production`". Leave the mechanics (the four `ORG_GRADLE_PROJECT_*` secrets, `--no-configuration-cache`) unchanged. Do not invent new sections.

- [ ] **Step 4: Write `OD/README.md`**

```markdown
# Odin

Kotlin-first root shell + `RootService` IPC for Android — an in-house fork of [topjohnwu/libsu](https://github.com/topjohnwu/libsu), reimagined around Kotlin and coroutines.

## Install

```kotlin
dependencies {
    implementation("com.trinadhthatakula:odin:1.0.0")
}
```

Requires `minSdk 24`. Published to Maven Central.

## What it provides

- A persistent root shell with a coroutine-friendly API (`suspend` command execution, `Flow` of output).
- A generic `RootService` framework (Binder/AIDL) for running privileged code in a root process.

## License

Odin is licensed under the **Apache License 2.0** (see [`LICENSE`](LICENSE)), inheriting attribution to the original [libsu](https://github.com/topjohnwu/libsu) project (also Apache-2.0).

## Releasing

See [`docs/PUBLISHING.md`](docs/PUBLISHING.md). Publishing is triggered by pushing to the `production` branch.
```
(The install snippet shows `1.0.0` — the intended first release — even though the repo currently carries `1.0.0-SNAPSHOT`; this is documentation of the eventual coordinate, not the current version.)

- [ ] **Step 5: Validate the workflow YAML parses**

Run via `ctx_execute` (`language: "shell"`):
```
cd /Users/trinadhthatakula/StudioProjects/Odin && python3 -c "import yaml,glob,sys; [yaml.safe_load(open(f)) for f in glob.glob('.github/workflows/*.yml')]; print('YAML OK')"
```
Expected: `YAML OK`.

- [ ] **Step 6: Commit**

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git add -A
git commit -m "ci+docs: publish (production) + PR build gate + README + PUBLISHING guide"
```

**Acceptance:** both workflow files parse; `publish.yml` triggers only on `production` with the four `ORG_GRADLE_PROJECT_*` secrets; `pr-ci.yml` runs `:odin:assembleRelease` on PRs to `main`; README + PUBLISHING reference Odin's coordinates.

---

### Task 3: Create the GitHub remote + push `main`

**Files:** none (remote + git plumbing only).

**Interfaces:**
- Consumes: the committed `Odin` repo from Tasks 1–2.
- Produces: `github.com/trinadhthatakula/Odin` with `main` pushed. `production` does NOT exist (no publish can fire).

- [ ] **Step 1: Confirm the working tree is clean and on `main`**

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git status --short && git rev-parse --abbrev-ref HEAD && git --no-pager log --oneline
```
Expected: clean tree, branch `main`, two commits (Task 1 + Task 2).

- [ ] **Step 2: Create the GitHub repo (public) WITHOUT pushing yet**

```bash
gh repo create trinadhthatakula/Odin --public \
  --description "Kotlin-first root shell + RootService IPC for Android (in-house libsu fork)." \
  --disable-wiki
```
This creates an empty remote and does not touch local branches. Do NOT pass `--push` (we push explicitly and controlled in the next step).

- [ ] **Step 3: Add the remote + push `main` ONLY**

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git remote add origin https://github.com/trinadhthatakula/Odin.git
git push -u origin main
```
**Do NOT create or push a `production` branch.** Publishing is gated on `production`; leaving it absent guarantees no release fires.

- [ ] **Step 4: Verify the remote state**

```bash
gh repo view trinadhthatakula/Odin --json name,visibility,defaultBranchRef --jq '{name, visibility, defaultBranch: .defaultBranchRef.name}'
git -C /Users/trinadhthatakula/StudioProjects/Odin ls-remote --heads origin
```
Expected: repo `Odin`, default branch `main`, and `ls-remote` shows ONLY `refs/heads/main` (no `production`).

**Acceptance:** `trinadhthatakula/Odin` exists with `main` pushed and `production` absent; a fresh `git clone` would build via `:odin:assembleRelease`. (Maintainer follow-up, out of scope for this plan: add the four Actions secrets before the eventual `production` release.)

---

## Self-Review

- **Spec coverage:** repo scaffold (Task 1 Steps 1–7) ✓; verbatim source/AIDL/assets/proguard/LICENSE port (Task 1 Steps 8–9) ✓; module build.gradle.kts with the two deltas + full mavenPublishing (Task 1 Step 10) ✓; standalone build + built-in-Kotlin verification/pin (Steps 11–12) ✓; publish-readiness to mavenLocal + POM check (Step 13) ✓; CI publish.yml on `production` + minimal pr-ci.yml (Task 2) ✓; README + PUBLISHING (Task 2) ✓; create remote + push `main` only (Task 3) ✓. All spec sections mapped.
- **Placeholder scan:** none — every file's exact content is inline; the port and copies are precise shell commands; `docs/PUBLISHING.md` is a copy+named-substitutions (not a placeholder). The only conditional (Step 12) is explicitly gated on a Step-11 failure.
- **Type/constraint consistency:** coordinates `com.trinadhthatakula:odin` and `VERSION_NAME=1.0.0-SNAPSHOT` identical across gradle.properties (T1S6), the module `coordinates(...)` (T1S10), the POM check (T1S13), and README/PUBLISHING (T2). Catalog aliases `libs.plugins.android.library`, `libs.plugins.vanniktech.maven.publish`, `libs.androidx.core.ktx`, `libs.kotlinx.coroutines.android` match the `libs.versions.toml` keys (T1S7). Namespace `com.valhalla.superuser` consistent (port + module). The two build deltas (no `foss_release`, coroutines `api`) are both present in the T1S10 file and nowhere contradicted.
