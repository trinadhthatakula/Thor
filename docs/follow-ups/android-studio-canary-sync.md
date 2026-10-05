# Android Studio Canary sync compatibility

Verified 2026-10-05 with Android Studio Rabbit 2 2026.2.2 Canary 3
(`AI-262.10968.63.2622.16488178`), AGP 9.5.0-alpha08, Gradle 9.8.0,
Kotlin 2.4.20, and JDK 21. There were two independent failures.

## Compose Preview task creation

Studio collects the Gradle task model during sync, which realizes tasks that an
ordinary APK build does not need. AGP registers Compose Preview tasks for all
Compose variants, but enables the unit-test component only for the tested build
type (debug by default). Creating `generateStoreReleaseComposePreviewRunfiles`
therefore failed because its unit-test resource APK was unavailable. The error
mentions `enableUnitTest = false in beforeVariants`, even though Thor did not
explicitly disable it.

`app/build.gradle.kts` now enables the `HostTestBuilder.UNIT_TEST_TYPE` component
for all app variants. The existing `unitTests.isIncludeAndroidResources = true`
supplies preview resources. The existing `fossBenchmark` exclusion still applies.
Instrumentation tests and APK packaging are unaffected. The shared Java/Kotlin
sources under `src/test` are explicitly assigned to `testDebug`, preserving the existing FOSS
Debug and Store Debug test suite. The new release and benchmark host components
provide preview resources without expanding the test matrix. Aggregate `test`
can still require additional main-variant compilation for those components.

This scope is intentional: Compose UI tests launch the activity supplied by
`debugImplementation(ui-test-manifest)`. Running them against release initially
produced 83 missing-activity failures. Adding the manifest as a test dependency
merged it into the host-test text manifest, but Robolectric still read the
application's binary resource APK, where the activity is correctly absent.
Keeping these fixtures on debug avoids adding a test activity to shipped APKs
or introducing custom binary resource transformations.

The relevant AGP source is `BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE`,
`VariantManager`'s host-test defaults, and
`GenerateComposePreviewRunfilesTask.CreationAction.configure`.

## Canary reports failure after a successful Gradle invocation

After the preview fix, Canary still reported an unexpected sync exception while
the Gradle console said `BUILD SUCCESSFUL`. Its resilient model-fetch path treats
unsupported optional models for Compose Hot Reload, Kotlin Multiplatform, and
CocoaPods as errors. The IDE suppresses their messages but still marks the
resolution as failed, ending in `ExternalSystemPartialResolutionException`.
This also reproduced with Gradle 9.7.1; changing the wrapper to that version did
not help.

Local IDE workaround, verified through an actual successful project sync:

1. Open **Find Action → Registry** in the affected Canary installation.
2. Uncheck `gradle.use.resilient.model.fetch` (default: checked).
3. Keep `gradle.use.resilient.model.fetch.unstable` unchecked (its default).
4. Sync the project again.

This restores the IDE's `findModel` path, where unsupported optional models can
return null. It does not disable parallel builds, parallel model fetch, or the
configuration cache. The registry preference is local to the IDE installation;
the repository cannot apply it for other contributors. Gradle remains 9.8.0.

The installed IDE's `GradleModelControllerImpl.sendModelFetchFailures`,
`GradleSyncFailureHandler`, `GradleExecutionReporterImpl`, and
`GradleProjectResolver` establish the failure path. In this Canary, the stable
resilient-fetch flag is used from Gradle 9.7 onward.

## Verification and follow-up

- The original preview task lookup failed before the build-script change and
  passed afterward; root and app `tasks --all` also passed.
- `generateStoreReleaseComposePreviewRunfiles` executed successfully with an
  absolute `--compose-preview-manifest-file` output path. Its resource APK
  contains `resources.arsc`; the compiled project outputs and all 306 dependency
  classpath entries exist. Two optional KSP output directories are absent
  because this build does not generate files there.
- Actual Canary sync completed after applying both fixes. The last worktree sync, including
  the non-deprecated `directories` API, completed in 4.510 seconds.
  The failure banner cleared and the app Run/Debug controls became available.
- Temporary debug log categories were removed after diagnosis.
- Final validation passed all 3,584 FOSS Debug and 3,584 Store Debug tests, with
  no failures or skipped tests. Both lint gates reported zero errors/warnings,
  including zero `MissingTranslation` or `SyntheticAccessor` findings. Existing
  informational hints remain.

The successful final invocation used one worker and a temporary 6 GB Kotlin
compiler heap, and also executed the Store Release preview-generation task:

```bash
./gradlew test lintFossDebug lintStoreRelease \
  :app:generateStoreReleaseComposePreviewRunfiles \
  --compose-preview-manifest-file=/absolute/path/store-release-preview-runfiles.json \
  --max-workers=1 '-Pkotlin.daemon.jvmargs=-Xmx6g -XX:+UseG1GC'
```

The default two-worker run exhausted the 4 GB Kotlin compiler heap during Store
Debug/Release compilation. A fresh single-worker run also exhausted it compiling
Store Release. Thus the default resource settings are not a verified passing
gate for this full invocation; the final successful run used the override above.
The repository's heap and worker settings were not changed. These compilation
failures are separate from the two sync defects.

Recheck both workarounds on future AGP/Studio updates. Restore the registry flag
to its default once the optional-model failure handling is fixed upstream and a
real sync succeeds. Remove the explicit host-test enablement only when task-model
collection and preview resource generation work without it.
