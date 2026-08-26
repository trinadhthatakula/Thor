# "0 errors, 0 warnings" is bounded by config, not by code

`:app` reports `0 errors, 0 warnings, 5 hints` on every one of its five variants, and it enforces
that with `abortOnError`/`warningsAsErrors`/`checkReleaseBuilds`. That headline is true and worth
keeping. It is also **not** the same claim as "there is nothing left to find", and this document
exists so nobody reads it as though it were.

Measured on 2026-08-05, during the warning sweep that shipped with v1.93.3, the configuration in
force at that moment left **292 findings switched off** across the three modules: 273 lint issues in
`:app` (measured on `storeDebug`, the superset variant), 18 in `:bypass`, and 1 javac warning in
`:vm-runtime`.

**The `:bypass` figure is 18. It was rewritten to 9 on 2026-08-07 and that rewrite was wrong; 18 is
restored, and this paragraph is kept as the record of how.** The "9" was never measured — it was
*derived*, by disassembling the detector and then enumerating only the cross-class private
references that a misread bail-out left standing (see below), which discarded exactly the 9 that
the misread hid. The story attached to it, that "18 is 9 counted twice across `lintDebug` and
`lintRelease`", was a coincidence of arithmetic dressed as an explanation: the two variants do
analyse identical sources, but each reports **18**, not 9. Re-measured 2026-08-07 by reverting all
six methods to `private` and running `:bypass:lintDebug` — 18 errors, `descriptorString` ×5,
`isAligned` ×4, `componentSize` ×3, `slice` ×2, `roundUp` ×2, `primitiveOrder` ×2. All 18 are now
fixed and pinned by `enable += "SyntheticAccessor"` in `bypass/build.gradle.kts`, where
`warningsAsErrors = true` makes the build itself the check.

The durable lesson is narrower than "count carefully": **a derived count and a measured count are
different kinds of claim, and this file printed the derived one in the measured one's place.** The
detector was disassembled correctly; what failed was reading one `return` as broader than it is,
and nothing downstream could catch that because no number was ever run past lint again.

**The counts are not additive, and anyone reproducing them must say which variant and which flag
combination they used.** `checkAllWarnings` alone gives 253 and `checkTestSources` alone gives 9, but
both together give 266, not 262 — the extra 4 are `SyntheticAccessor` hits inside test sources, a
disabled-by-default check applied to a source set that was itself disabled. The `store` source set
adds a further 7 to reach 273; `foss` (2 files) adds none. `fossDebug` and `storeDebug` differ by 7.

Two of these were closed in that sweep and are recorded here only so the remaining number is
readable: `checkTestSources = true` is now on (61 unit-test files and the `androidTest` tree had been
analysed by nothing at all), and `:bypass` now has a `lint {}` block pinning the clean state it was
already in. A third closed on 2026-08-07: `:bypass` enables `SyntheticAccessor` by id and all 18 of
its findings are fixed, which takes the remaining number to **274**. Everything else below is still
open.

A fourth closed on 2026-08-26: `:app` enables `SyntheticAccessor` in `app/lint.xml` and all of its
findings are fixed. **The running total is deliberately not decremented for it**, because doing so
would mix measurements: the 273/292 base was taken on the 2026-08-05 tree, and re-measuring
`SyntheticAccessor` on today's tree gives 62, not the 51 that went into that base. Subtracting a
fresh number from a stale total produces a figure that was never measured — which is the exact
mistake the paragraph above exists to record. Treat 274 as "as of 2026-08-05, minus the checks since
closed", and re-measure before quoting it.

## What is still off, and what each is worth

| Check | Hits | Verdict |
|---|---:|---|
| `SyntheticAccessor` | **CLOSED** in both modules — `:app` 62 + 4, `:bypass` 18 | Fixed and pinned everywhere. `:bypass` was done first: all 18 were in `DexFieldLayout.kt`, and widening **six** Companion methods from `private` to `internal` — `descriptorString` ×5, `isAligned` ×4, `componentSize` ×3, `slice` ×2, `roundUp` ×2, `primitiveOrder` ×2 — closed every one. One correction to the original entry survives re-measurement: they are not all "an outer class calling a private Companion method". Only 6 are (`isAligned` ×4 and `roundUp` ×2, from `layoutOf`/`shuffleForward`/`addFieldGap`); 3 are a *nested* class doing it (`ZipReader.openEntry`, `DexReader.getString`, `DexField.componentSize`); and the remaining 9 are the companion calling itself from a `val` initializer or from a lambda inside one, which is **not** exempt (see below). `:app` closed 2026-08-26 — see the section below, **including the correction to this row's old "real payoff" claim** |
| `TypographyQuotes` | 127 | Largest contributor and the least defensible. It fires on translator-supplied text in `values-fr`/`values-es`/`values-ar`/`values-zh-rCN`; "fixing" it means editing other people's translations to swap `'` for U+2019. Disabled by default for good reason |
| `DuplicateStrings` | 82 | Real but low value here. Several are legitimately the same word in that language — e.g. `values-zh-rCN` `home_desc` == `home` |
| `PermissionNamingConvention` | 1 | **False positive by construction.** Fires on `android.permission.QUERY_ALL_PACKAGES` at `AndroidManifest.xml:22`, a Google-defined platform permission this repo cannot rename. If it is ever enabled it must be `ignore`d, never "fixed" |
| `:vm-runtime` javac | 1 (2) | `-Xlint:all` reports `[missing-explicit-ctor]` on `sun/misc/Unsafe.java:4`, identical on JDK 21 and JDK 26. It is an artifact of `--patch-module` placing the stub in the exported `jdk.unsupported` package. The file-level `@SuppressWarnings({"unused","rawtypes"})` hides a second (`[rawtypes]`, line 16). **Do not add `-Xlint:all` to this module** — the class is a compile-only shadow stub that is never instantiated and never ships, and silencing the warning means adding a constructor to a file whose entire job is to mirror the platform's shape |

### Two bail-outs decide what `SyntheticAccessor` can report

Verified by disassembling `SyntheticAccessorDetector` out of `lint-checks-32.4.0-alpha07`, the jar
that pairs with the pinned `agp = "9.4.0-alpha07"`. Anyone re-counting `:app` needs both of these,
because each silently removes a whole category of candidate:

- `visitSimpleNameReferenceExpression` returns immediately when the resolved element
  `is PsiField && isKotlin(language)`, and it does so *before* the `ConstantEvaluator` check.
  **No Kotlin field reference is ever reported.** In `DexFieldLayout.kt` that alone removes the eight
  cross-class `private const val` reads and both `private val` Comparators.
- `visitCallExpression` returns when `getNameFromSource(node.getContainingUClass()) == "Companion"`.
  **This is much narrower than "a call inside the companion object is skipped", and reading it that
  way is what produced the wrong count above.** The containing `UClass` is the class that *hosts*
  the call in UAST, not the source block it is typed in, and Kotlin puts a companion `val` on the
  **outer** class as a static field — so the initializer of `val OBJECT = descriptorString(...)`,
  and a SAM-converted lambda inside `private val FIELD_COMPARATOR`, both report `DexFieldLayout`
  and are **not** exempt. Only a call in a companion *function body* reaches that `return`. This is
  measured, not read: reverting the six methods to `private` makes lint report `descriptorString`
  ×5 (lines 488–492) and `primitiveOrder` ×2 (both on line 513), plus `componentSize` ×2 from the
  same lambda.

The issue is `Category.PERFORMANCE`, priority 2, `Severity.WARNING`, `enabledByDefault = false`,
`androidSpecific = true`, `JAVA_FILE_SCOPE`, aliased `SyntheticAccessorCall` and
`PrivateMemberAccessBetweenOuterAndInnerClass`.

### Enable by id, never `checkAllWarnings`

The registry holds **512 issue ids, 473 enabled by default and 39 disabled**. Only 4 of those 39 fire
on this codebase at all, which is the argument against ever flipping `checkAllWarnings` globally:
35 of them buy nothing, and the 4 that do fire are dominated by the two cosmetic ones. If
`SyntheticAccessor` is wanted, enable **it** by id rather than the global flag, which under the
existing `warningsAsErrors` would turn 253 findings into an instantly red build.

`:bypass` took the Gradle DSL form of that — `enable += "SyntheticAccessor"` inside its existing
`lint {}` block, so the module keeps a single config surface rather than gaining a `bypass/lint.xml`.
`:app` already has a `lint.xml` and used the `<issue id="SyntheticAccessor" severity="warning" />`
form there. Either way, the enable line and the fixes have to be the **same commit**: the check
ships at `Severity.WARNING`, and both modules set `warningsAsErrors = true`, so enabling it on its
own reddens every build of that module.

### `:app`, closed 2026-08-26 — and the correction that came with it

**62 findings from 14 declarations**, on `:app:lintStoreRelease` with `checkTestSources = true` and
the `store` source set in scope. Not the 43/51 this file used to print: that was measured 2026-08-05
and the module has grown. Two shapes, and the split is the useful part:

- **Eight top-level `private` declarations reached from a class in the same file.** Kotlin puts a
  top-level declaration on the file facade (`FooKt`), which is a different JVM class from the one
  below it, so *every* reference costs an `access$` bridge. This is where the volume is:
  `PreferenceRepositoryImpl.dataStore` ×33 and `localState` ×2, `RootSystemGateway.SUSPEND_USER_ID`
  ×8, `PassphraseVault.passphraseVault` ×2, `BundleZip.readAtMost` ×2,
  `ComponentOverrideRepositoryImpl.toDomain` ×2, `UiText.resolved` ×2.
- **Six `private` class members reached from a lambda or coroutine body**, which compiles to its own
  class: `AppBundleFileStoreImpl.displayNameOf`, `ThorRootService.{clearAppData, dumpPackage,
  setAppSuspendedAs}`, `BillingProcessorImpl.{queryProducts, queryActiveSubscriptions,
  scheduleReconnect}`.

All 14 are now `internal`, the same remedy `:bypass` used. A 15th, `stableNames` in
`BackupAppsUseCaseTest.kt` (×4), came with a warning worth writing down: **the debug lint variants
report it and `lintStoreRelease` does not, and on the debug variants it prints as `Warning:` and does
not fail the build even though `warningsAsErrors = true`.** So a test-source finding of this check is
invisible to CI *and* non-fatal locally — it would have rotted with nothing red. Fixed anyway.
Re-verified: all of `lintStoreRelease`, `lintFossDebug` and `lintStoreDebug` are clean.

**The correction.** This file used to call `SyntheticAccessor` "the only one with a real payoff:
method count against the 64K limit, and APK size is a tracked concern in this repo." That was
reasoned, not measured, and the measurement is much smaller. Counting `access$` names in the dex
string pool of the built APKs:

| Artifact | `access$` methods | Total methods |
|---|---:|---:|
| `app-store-debug.apk` (22 dex, no R8) | 3,563 | — |
| `app-store-release.apk` (1 dex, R8) — before | **21** | 30,682 |
| `app-store-release.apk` (1 dex, R8) — after | **18** | 30,679 |

R8 inlines essentially all of them. Of `:app`'s 62 findings, exactly **three** named a bridge that
survives into the shipped APK — `ThorRootService`'s `clearAppData`, `dumpPackage` and
`setAppSuspendedAs`, which survive because that class is kept verbatim for the root daemon. The 18
that remain after the fix are *all* Odin's `RootService`/IPC internals (`enforceCaller`,
`getMDaemon$p`, `unbindServices` and friends), which `checkDependencies = false` puts out of scope
regardless. **So the shipped saving was 3 methods out of 30,682, not 62** — a rounding error against
the 64K limit, and nothing measurable in APK size.

Reproduce it with the dex string pool, which is where method names live:
`unzip -p <apk> classes.dex | strings | grep -c '^access\$'`, and read `method_ids_size` from the dex
header at offset `0x58`. Do it on the **release** APK; doing it on debug is what produces the 62-ish
number and the wrong conclusion.

It is enabled anyway, and the honest reason is not method count: a debug build carries all 3,563 of
those bridges, the fix is a keyword, and pinning at zero costs nothing whereas re-privatising 14
declarations later would be a real change. But **do not reach for this check as an APK-size lever**,
and do not repeat the 64K framing — R8 already did that job. The durable shape is the same one this
file already teaches one section up: the payoff was *derived* from what the bytecode contains before
optimisation, and printed where a measurement of the shipped artifact belonged.

## The related thing that is not a lint setting

Kotlin has no equivalent gate. The obvious symmetry — `allWarningsAsErrors = true`, so compiler
warnings are fatal the way lint warnings already are — **cannot be adopted today**, and not for a
reason in Thor's own code:

```text
Koin compiler plugin: Kotlin 2.4.10 is newer than the newest tested version (2.4.0) —
proceeding with the 2.4.0 adapter. Supported versions: 2.3.20, 2.4.0.
```

That is emitted as a compiler warning on every Kotlin compile task, so `allWarningsAsErrors` would
fail the build on it. It is harmless in itself (the owner's call, 2026-08-05: trivial), but it means
"Thor's Kotlin sources compile with zero warnings" — true as of v1.93.3 — is a state nothing
enforces, and the next warning to appear will be noticed by a human reading build output or not at
all. Closing this needs either a `koin-compiler-plugin` release tested against Kotlin 2.4.x, or a
narrowly-scoped suppression of that one diagnostic. Pinning Kotlin back to 2.4.0 to silence it would
be the wrong trade.

## Dead-by-the-type-system null guards

The same sweep found 11 sites in this class; 4 warned and were fixed in v1.93.3. The other 7 compile
**completely silently**, because `.orEmpty()`, `.isNullOrBlank()`, `.isNullOrEmpty()`,
`filterNotNull()` and `requireNotNull()` on a non-null receiver produce no diagnostic where `?:`,
`?.`, `!!` and `== null` all do:

Referenced by symbol rather than line, because line numbers move and these have already moved once:

- `BillingProcessorImpl.kt`, six `.orEmpty()` calls whose receivers `javap` shows as
  `@androidx.annotation.NonNull` on `billing-9.1.0` — `queryResult.productDetailsList`,
  `queryResult.unfetchedProductList`, `offer.pricingPhases.pricingPhaseList`,
  `phase.formattedPrice`, `phase.billingPeriod`, `phase.priceCurrencyCode`.
- `AppAnalyzerImpl.kt`, `require(!archiveInfo.packageName.isNullOrBlank())` — `PackageInfo.packageName`
  is `@android.annotation.NonNull`, so only the *blank* half can fire.

**One `.orEmpty()` in that file is emphatically not in this class and must not be swept with them:**
`details.subscriptionOfferDetails.orEmpty()`. `ProductDetails.getSubscriptionOfferDetails()` is
`@Nullable` — it returns null for a one-time-purchase product — so that guard is load-bearing and
Kotlin types it nullable. It sits three lines from two of the dead ones, which is the whole hazard:
the sites are visually identical and only the annotation on the receiver tells them apart.

Watch out for how `javap -v` lays this out, since getting it backwards inverts every verdict here:
the resolved annotation name prints on its own line *after* the method's `RuntimeInvisibleAnnotations:`
block, so the name appearing immediately **above** a signature belongs to the **previous** member.
Parsing backwards from the signature shifts the whole table by one method and reports
`getPricingPhases` as `@Nullable` — which would have argued for keeping the `?.` that this sweep
removed.

**These were left alone deliberately, and the reason matters more than the sites.** They are not
elided from the binary — `.orEmpty()` compiles to the same `dup`/`ifnonnull`/`pop` branch that
`?: emptyList()` does, and that branch survives R8 into the shipped DEX. So deleting them is a real
behaviour change in exactly the malformed-response scenario they exist for, not a cleanup. They cost
a few bytes and catch a library that violates its own contract. The genuine hazard here was never the
code — it was a comment at `BillingProcessorImpl.kt:336` asserting these were "Java platform types"
whose "annotations do not guarantee" non-null, which `javap` disproves. That comment is why five more
guards were written on annotated-non-null values and never questioned; it was corrected in v1.93.3.

What is worth watching, if anyone revisits this: rewriting a warned `?: emptyList()` as `.orEmpty()`
"fixes" nothing. The emitted bytecode is byte-identical; only the compiler goes quiet. If a guard is
kept it should keep a shape the compiler can still see — which is why the three `ExtensionManager`
sites were given an explicit nullable declared local rather than a silent `.orEmpty()` or a
`@Suppress`: `List<PackageInfo>?` in `loadExtensions` and `getInstalledExtensionVersionCodes`, and
`List<ApplicationInfo>?` in `getExtensionPackageName`, which calls `getInstalledApplications`.
