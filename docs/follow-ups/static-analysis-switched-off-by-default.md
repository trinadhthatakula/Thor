# "0 errors, 0 warnings" is bounded by config, not by code

`:app` reports `0 errors, 0 warnings, 5 hints` on every one of its five variants, and it enforces
that with `abortOnError`/`warningsAsErrors`/`checkReleaseBuilds`. That headline is true and worth
keeping. It is also **not** the same claim as "there is nothing left to find", and this document
exists so nobody reads it as though it were.

Measured on 2026-08-05, during the warning sweep that shipped with v1.93.3, the configuration in
force at that moment left **292 findings switched off** across the three modules: 273 lint issues in
`:app` (measured on `storeDebug`, the superset variant), 18 in `:bypass`, and 1 javac warning in
`:vm-runtime`.

**The counts are not additive, and anyone reproducing them must say which variant and which flag
combination they used.** `checkAllWarnings` alone gives 253 and `checkTestSources` alone gives 9, but
both together give 266, not 262 — the extra 4 are `SyntheticAccessor` hits inside test sources, a
disabled-by-default check applied to a source set that was itself disabled. The `store` source set
adds a further 7 to reach 273; `foss` (2 files) adds none. `fossDebug` and `storeDebug` differ by 7.

Two of these were closed in that sweep and are recorded here only so the remaining number is
readable: `checkTestSources = true` is now on (61 unit-test files and the `androidTest` tree had been
analysed by nothing at all), and `:bypass` now has a `lint {}` block pinning the clean state it was
already in. Everything below is still open.

## What is still off, and what each is worth

| Check | Hits | Verdict |
|---|---:|---|
| `SyntheticAccessor` | 43 `:app` main (51 on `storeDebug` incl. test + store), 18 `:bypass` | **The only one with a real payoff.** Method count against the 64K limit, and APK size is a tracked concern in this repo. The 18 in `:bypass` are all in `DexFieldLayout.kt` and all the same shape — an outer class calling a `private` Companion method. Six methods changed from `private` to `internal` closes all 18 in one file, which is the best findings-per-edit ratio anywhere in the repo |
| `TypographyQuotes` | 127 | Largest contributor and the least defensible. It fires on translator-supplied text in `values-fr`/`values-es`/`values-ar`/`values-zh-rCN`; "fixing" it means editing other people's translations to swap `'` for U+2019. Disabled by default for good reason |
| `DuplicateStrings` | 82 | Real but low value here. Several are legitimately the same word in that language — e.g. `values-zh-rCN` `home_desc` == `home` |
| `PermissionNamingConvention` | 1 | **False positive by construction.** Fires on `android.permission.QUERY_ALL_PACKAGES` at `AndroidManifest.xml:22`, a Google-defined platform permission this repo cannot rename. If it is ever enabled it must be `ignore`d, never "fixed" |
| `:vm-runtime` javac | 1 (2) | `-Xlint:all` reports `[missing-explicit-ctor]` on `sun/misc/Unsafe.java:4`, identical on JDK 21 and JDK 26. It is an artifact of `--patch-module` placing the stub in the exported `jdk.unsupported` package. The file-level `@SuppressWarnings({"unused","rawtypes"})` hides a second (`[rawtypes]`, line 16). **Do not add `-Xlint:all` to this module** — the class is a compile-only shadow stub that is never instantiated and never ships, and silencing the warning means adding a constructor to a file whose entire job is to mirror the platform's shape |

The registry holds **512 issue ids, 473 enabled by default and 39 disabled**. Only 4 of those 39 fire
on this codebase at all, which is the argument against ever flipping `checkAllWarnings` globally:
35 of them buy nothing, and the 4 that do fire are dominated by the two cosmetic ones. If
`SyntheticAccessor` is wanted, enable **it** in `app/lint.xml` — `<issue id="SyntheticAccessor"
severity="warning" />` — rather than the global flag, which under the existing `warningsAsErrors`
would turn 253 findings into an instantly red build.

## The related thing that is not a lint setting

Kotlin has no equivalent gate. The obvious symmetry — `allWarningsAsErrors = true`, so compiler
warnings are fatal the way lint warnings already are — **cannot be adopted today**, and not for a
reason in Thor's own code:

```
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

- `BillingProcessorImpl.kt` lines 339, 362, 607, 608, 614 — `.orEmpty()` on `@androidx.annotation.NonNull`
  billing getters (and the trailing `.orEmpty()` on 605, which stays).
- `AppAnalyzerImpl.kt:338` — `require(!archiveInfo.packageName.isNullOrBlank())`, where
  `PackageInfo.packageName` is `@android.annotation.NonNull`, so only the *blank* half can fire.

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
sites were given an explicit `List<PackageInfo>?` local rather than a silent `.orEmpty()` or a
`@Suppress`.
