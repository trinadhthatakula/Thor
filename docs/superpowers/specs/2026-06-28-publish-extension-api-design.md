# Publish `thor-extension-api` to Maven Central — Design

**Date:** 2026-06-28
**Status:** Approved (pending spec review)
**Author:** trinadhthatakula

## Goal

Extract the existing `:extension-api` module into a **standalone GitHub repo** and publish it to
**Maven Central**, so third-party developers can add it as a normal Gradle dependency and build Thor
extensions in their own projects.

```kotlin
// Consumer (extension author) build.gradle.kts
dependencies {
    compileOnly("io.github.trinadhthatakula:thor-extension-api:1.0.0")
}
```

## Scope

### In scope
- Extract `:extension-api` into a new standalone repo **`thor-extension-api`** (GitHub).
- Configure it for publishing to Maven Central via the **Central Portal** (Vanniktech plugin).
- Independent SemVer (start `1.0.0`), decoupled from the Thor app `versionCode`.
- Correct dependency scoping in the published POM.
- Local publishing workflow (run from the maintainer's machine).
- Rewire the **Thor** app to consume the published artifact, with a Gradle **composite-build** toggle
  for local cross-repo development.
- Consumer-facing README + maintainer runbook.

### Out of scope (explicit follow-ups)
- **Third-party trust / signature redesign.** Today `ExtensionManager.verifySignature()` only loads
  extensions signed with Thor's exact signing key on release builds (relaxed in `DEBUG`). Third-party
  extensions signed with their own keys will NOT load on the official Thor release. Publishing the API
  unblocks *compilation* and testing on debug/self-built Thor; loading third-party-signed extensions on
  the official build is a separate effort.
- A standalone `thor-extension-template` starter repo.
- CI-automated publishing (added after the first successful local publish).

## Decisions (locked)

| Decision | Choice |
|---|---|
| Trust model | Publish API only; defer trust redesign |
| Repo strategy | **Option B** — standalone `thor-extension-api` GitHub repo |
| App consumption | Pinned published version + Gradle composite build (`includeBuild`) for local dev |
| Group ID | `io.github.trinadhthatakula` (Central Portal auto-verifies via a GitHub repo) |
| Artifact ID | `thor-extension-api` |
| Versioning | Independent SemVer, start `1.0.0` |
| Release flow | Local publish first; add CI later |
| Tooling | Vanniktech `com.vanniktech.maven.publish` plugin (native Central Portal support) |

## Background: how extensions load (why dependency scoping matters)

Extensions are separate APKs (`com.valhalla.thor.ext.*`) loaded **into Thor's own process** via
`PathClassLoader(app.sourceDir, context.classLoader)`. Because the loader's parent is Thor's
classloader, the runtime classes the API touches — **Compose, Coil, Kotlin stdlib, AndroidX core** —
are all **provided by the host app at runtime** and resolve parent-first.

Consequence: extension authors should declare the dependency as **`compileOnly`** — they compile
against the contract but must not bundle it (host provides it). This keeps extension APKs tiny and
avoids duplicate-class / version-skew issues.

## Target architecture (two repos)

```
thor-extension-api/  (NEW — GitHub, standalone Gradle project, public SDK)
├── settings.gradle.kts          # rootProject.name, single library module
├── build.gradle.kts             # plugins { ... } apply false
├── gradle.properties            # group + version (SemVer)
├── gradle/
│   ├── wrapper/                  # same Gradle version as Thor
│   └── libs.versions.toml        # AGP, Kotlin, Compose compiler, Compose BOM, Coil, Vanniktech
├── LICENSE                       # copied from Thor
├── README.md                    # consumer-facing usage
├── docs/PUBLISHING.md           # maintainer runbook
└── extension-api/               # the library module (or root-level module)
    └── src/main/java/com/valhalla/thor/extension/api/
        ├── ThorExtension.kt, DebloatExtension.kt, AutomationExtension.kt
        ├── ShellExecutor.kt, ExtensionDataStore.kt, Logger.kt, AppIcon.kt

Thor/  (EXISTING — Codeberg, consumes the published artifact)
├── settings.gradle.kts          # remove include(":extension-api"); add optional includeBuild
├── gradle/libs.versions.toml     # add thor-extension-api coordinate
└── app/build.gradle.kts          # project(":extension-api") -> libs.thor.extension.api
```

## Design

### 1. New repo scaffold — `thor-extension-api`

- Standalone Gradle project mirroring Thor's relevant toolchain (JDK 21, Kotlin 2.4.0, Compose
  compiler, `compileSdk 37`, `minSdk 28`).
- `gradle/libs.versions.toml` carries only what the library needs: AGP (android-library), Kotlin,
  `kotlin.compose` plugin, `androidx.core.ktx`, Compose BOM, `androidx.ui`, `coil-compose`, and the
  Vanniktech publish plugin.
- Single Android library module, namespace `com.valhalla.thor.extension.api` (unchanged), holding the
  seven source files moved verbatim from Thor's current `:extension-api`.
- `LICENSE` copied from Thor so the POM license matches the actual license.

### 2. Coordinates & versioning

- **GAV:** `io.github.trinadhthatakula:thor-extension-api:1.0.0`.
- In the new repo's `gradle.properties`:
  ```properties
  GROUP=io.github.trinadhthatakula
  VERSION_NAME=1.0.0
  ```
- **Runtime contract version** stays the integer `thor.extension.api.version` (currently `1`) declared
  in extension manifests and checked by the host. Convention: library `1.x.y` ↔ contract `1`. Bump the
  integer only on a breaking contract change; the library MAJOR bumps with it.

### 3. Dependency scoping (correctness fix in the published POM)

Types that appear in **public API signatures** must be `api`; internal-only deps stay `implementation`.

| Dependency | Current | Target | Reason |
|---|---|---|---|
| `androidx.compose` runtime/ui | `implementation` | **`api`** | `@Composable ConfigurationScreen(...)`, `Modifier`, `ColorFilter` appear in public signatures (`AutomationExtension`, `AppIcon`). |
| `coil-compose` | `api` | **`api`** (keep) | Backs the public `AppIcon` helper; keeps it usable out of the box. |
| `androidx.core.ktx` | `implementation` | `implementation` (keep) | Not exposed in public signatures. |

Compose is mandatory in the contract (the `AutomationExtension.ConfigurationScreen` Composable), so the
published artifact necessarily carries a Compose dependency. Splitting into a pure-interface module +
a compose-ui module is NOT pursued (would require an API change; out of scope).

### 4. Publishing config (Vanniktech) — in the new repo's library `build.gradle.kts`

- Apply `alias(libs.plugins.vanniktech.maven.publish)` and `signing`.
- `mavenPublishing { }`:
  - `coordinates("io.github.trinadhthatakula", "thor-extension-api", VERSION_NAME)`
  - `configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))`
  - `publishToMavenCentral(automaticRelease = true)` (Central Portal)
  - `signAllPublications()`
  - Full POM: `name`, `description`, project `url` (the GitHub repo), `licenses` (match `LICENSE`),
    `developers`, `scm` (connection / devConnection / url).
- Pin a recent Vanniktech version with native Central Portal support (no `nmcp` helper plugin needed).

### 5. Rewire Thor to consume the published artifact

- `Thor/settings.gradle.kts`: remove `include(":extension-api")`; delete the local module directory.
- Add the optional composite-build toggle:
  ```kotlin
  // Local cross-repo development: point at a local checkout to edit both repos without publishing.
  val extApiDir = providers.gradleProperty("thorExtensionApiDir").orNull
  if (extApiDir != null) includeBuild(extApiDir)
  ```
- `Thor/gradle/libs.versions.toml`:
  ```toml
  [versions]
  thorExtensionApi = "1.0.0"
  [libraries]
  thor-extension-api = { module = "io.github.trinadhthatakula:thor-extension-api", version.ref = "thorExtensionApi" }
  ```
- `Thor/app/build.gradle.kts`: `implementation(project(":extension-api"))` → `implementation(libs.thor.extension.api)`.
- Because the included build publishes the same `group:name`, Gradle auto-substitutes the dependency
  with local source when `thorExtensionApiDir` is set (in `~/.gradle/gradle.properties` or
  `local.properties`); unset → the pinned published version is used. No code changes to switch modes.

### 6. Local publishing setup (no secrets in repo)

Credentials live ONLY in `~/.gradle/gradle.properties` or environment variables — never committed:

```properties
mavenCentralUsername=<Central Portal token username>
mavenCentralPassword=<Central Portal token password>
signingInMemoryKey=<ascii-armored GPG private key>
signingInMemoryKeyPassword=<GPG key passphrase>
```

Publish command (from the `thor-extension-api` repo):
```bash
./gradlew publishToMavenCentral
```

### 7. Maintainer runbook — `thor-extension-api/docs/PUBLISHING.md`

One-time + per-release steps:
1. **Central Portal account** at central.sonatype.com; register namespace `io.github.trinadhthatakula`;
   complete GitHub verification by creating the *throwaway* public repo named with the token the portal
   provides (under the `trinadhthatakula` GitHub account), then delete it after verifying.
2. **Generate a user token** in the portal → `mavenCentralUsername` / `mavenCentralPassword`.
3. **Generate a GPG key**, publish the public key to a keyserver, export the private key in in-memory
   form → `signingInMemoryKey` / `signingInMemoryKeyPassword`.
4. **Bump** `VERSION_NAME` in `gradle.properties`.
5. **Publish:** `./gradlew publishToMavenCentral`.
6. Confirm the deployment in the Central Portal and that the artifact appears on Maven Central.

### 8. Consumer-facing docs — `thor-extension-api/README.md`

Contains:
- Gradle coordinate + the **`compileOnly`** pattern (with the "why").
- The extension `AndroidManifest.xml` `<meta-data>` block (`thor.extension.class`,
  `thor.extension.api.version`) and the `com.valhalla.thor.ext.*` package-prefix requirement.
- A minimal `DebloatExtension` example.
- A note on the signature-trust limitation (third-party extensions currently load only on
  debug/self-built Thor).

## Error handling / failure modes

- **Signing misconfigured** → publish task fails fast with a clear signing error; runbook covers key
  setup. No partial/unsigned uploads (Central rejects them).
- **Namespace not yet verified** → upload rejected by the portal; runbook step 1 must complete first.
- **Composite-build group:name mismatch** → Gradle won't substitute and the app silently uses the
  published version; verified during rewire (step in plan) by confirming local edits take effect.
- **Wrong dependency scope shipped** → caught by inspecting the generated POM before first release.

## Testing / verification

- **New repo:** `./gradlew assembleRelease` builds; `./gradlew publishToMavenLocal` and inspect the POM
  (`~/.m2/.../thor-extension-api-1.0.0.pom`) for correct coordinates, scopes, and metadata.
- **Round-trip:** consume `mavenLocal()` from a throwaway sample extension module; confirm the
  `compileOnly` coordinate resolves and a sample `DebloatExtension` compiles.
- **Thor rewire:** with `thorExtensionApiDir` unset, `./gradlew :app:assembleFossDebug` resolves the
  published (or mavenLocal) artifact and builds. With it set, confirm a local API edit is picked up.
- **Extension loading smoke test:** build a debug Thor + a sample extension APK, install both, verify
  `ExtensionManager.loadExtensions()` discovers and instantiates it (debug build relaxes signature).
- First real publish goes to a Central Portal staging deployment; validate before release.

## Follow-ups (post-first-release)
- CI workflow (GitHub Actions in `thor-extension-api`) publishing on tag push, with signing key +
  portal token as CI secrets.
- `thor-extension-template` starter repo.
- Third-party trust/signature redesign so the official Thor build can load third-party extensions.
