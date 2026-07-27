# Follow-up: Qodana needs Docker — the Docker-free JVM-community fallback inspects no Kotlin

**Status:** Deferred — blocked on a container runtime on the dev machine (or on moving the scan to CI).
**Severity:** Minor (tooling/coverage gap; no runtime defect) — but it is a **silent** gap, which is
what makes it worth writing down. **Effort:** small once a runtime exists.
**Raised by:** the verification pass on PR #278 (2026-07-27).

## Problem

The intuition "the Android Qodana linter needs Docker, so fall back to the Kotlin/JVM one" does not
hold on this repo. `qodana-jvm-community` exits 0, reports a small problem count, and **never
analyses a single line of Kotlin in `:app`**. Left unchallenged it reads as a passing check.

Evidence, all from one run's `--results-dir`:

| artefact | what it shows |
|---|---|
| `projectStructure/Modules.json` | `Thor.app` and `Thor.bypass` both have `"sourceFolders": []`. Only `Thor.vm-runtime` gets `.main`/`.test` source-set submodules — because it is plain `java-library` and syncs *without* AGP. |
| `projectStructure/Gradle.json` | `"libraries": []` |
| `log/idea.log` | `PerProjectIndexingQueue - Finished for [Thor]. No files to index with loading content.` |
| `qodana.sarif.json` | only two artefact URIs in the whole report — `app/build.gradle.kts`, `settings.gradle.kts`. **Zero `.kt`.** |
| run summary | `Project analysis stage completed in 3s`, for a ~186-file Compose module. |

Root cause: the community linter ships no Android Gradle plugin, so AGP sync yields no source roots
for the Android modules. Gradle Kotlin DSL is still analysed, because script support does not depend
on module source roots — which is exactly why the only findings are `UnstableApiUsage` in the two
build scripts, and why the result looks plausible instead of obviously empty.

The Android linter has no native mode, confirmed directly:

```
$ qodana scan --linter qodana-jvm-android --within-docker=false
✗  Native mode for linter qodana-jvm-android is not supported
```

### Two things to know before re-running the community linter

1. **It writes its failed-sync defaults back into the repo.** After a run, `git status` shows
   `.idea/misc.xml` `JDK_21` → `JDK_1_8`, `.idea/compiler.xml` bytecode `21` → `1.8`, and the
   `KotlinCommonCompilerArguments` api/languageVersion `2.4` block dropped from `.idea/kotlinc.xml`.
   Always follow a run with
   `git checkout -- .idea/compiler.xml .idea/kotlinc.xml .idea/misc.xml`.
2. **This narrows the 2026-07-20 sweep retroactively.** That run's 72 problems were all XML /
   assets / build scripts / `:vm-runtime` — precisely the file classes that do not need `:app`
   source roots. So it also found nothing in `:app` Kotlin, and its "clean per Qodana" conclusion
   was never supported for Kotlin. It is not evidence of Kotlin cleanliness.

## Sketch

1. Install a container runtime — Colima (`brew install colima docker && colima start`) is the
   lighter option on macOS and is enough for Qodana's images.
2. Point `qodana.yaml` at the Android linter:
   ```yaml
   linter: qodana-jvm-android
   profile:
     name: qodana.recommended
   ```
   and run `qodana scan` (Docker is then the default, no `--within-docker` needed). Expect the
   Android XML `URI is not registered` false positives from the 2026-07-20 run to **disappear**,
   since that linter has the Android schema — a useful signal that the sync actually worked.
3. **Verify the run is not vacuous before trusting it**, using the checks in the table above:
   `Modules.json` must show non-empty `sourceFolders` for `Thor.app`, and the SARIF must reference
   `.kt` files. This check is cheap and should be repeated whenever the linter or AGP version moves.
4. Alternatively run it in CI, where a Docker executor already exists, and skip local setup
   entirely — probably the better long-term home given how rarely this needs to run.

## What covers the gap in the meantime

- `./gradlew lint` — genuinely works, Android-aware, currently clean (5 intentional `VectorPath`).
- Kotlin **compiler** warnings from a forced recompile:
  `./gradlew :app:compileFossReleaseKotlin --rerun-tasks | grep -A4 "Problem found: Kotlin compiler warning"`.
- `mcp__ide__getDiagnostics` per file when Android Studio is idle — it times out while Studio is busy.

None of these reproduce the IDE's Info/weak-warning Kotlin idiom inspections ("explicit type
arguments can be inferred", "replace with `isNullOrEmpty()`", and similar). Qodana would not either:
it reports Warning severity and above, and deliberately excludes Info-level idiom inspections. The
faithful source for those is Android Studio's headless `inspect.sh`, which currently crashes on the
installed Canary build (`InspectionsResultUtil.describeInspections`) and needs a **stable** Studio to
run. That class of check therefore stays uncovered regardless of Docker; Docker only fixes the
Warning-and-above Kotlin/Android coverage described above.

## Acceptance

- A Qodana run whose `Modules.json` shows non-empty `sourceFolders` for `Thor.app`, and whose SARIF
  references `.kt` files under `app/src/main`.
- `.idea/` unmodified after the run (or knowingly reverted).
- The resulting findings triaged once, so future runs can be diffed against a baseline instead of
  re-triaged from scratch.
