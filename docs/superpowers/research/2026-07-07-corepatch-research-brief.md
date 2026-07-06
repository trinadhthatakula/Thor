# Strombringer Plan 3 — Design-Grounding Brief: Forking CorePatch sig-bypass + downgrade

> **Source:** `corepatch-research` workflow (`wf_a41eef62-229`), 5 opus agents (4 research angles + synthesis), 2026-07-07. Grounded against LSPosed/CorePatch `main` (v4.9, 2026-04) and Thor's existing installer/gateway code. **High confidence** on mechanism/hook points; low-confidence areas flagged explicitly. This brief exists to ground the Plan 3 **brainstorm** — it is not a plan yet.

## 1. How CorePatch works

- **Not crypto-cracking — check-neutering.** It loads only into `system_server` (guard: `packageName=="android" && processName=="android"`) where `PackageManagerService` lives, and hooks the specific Java methods in AOSP's install pipeline that compare signatures, enforce digests/rollback, and block downgrades, forcing them to pass.
- **Version-dispatched.** `MainHook` switches on `Build.VERSION.SDK_INT` into an inheritance chain `CorePatchForQ(28-29) ← R(30) ← S(31-32) ← T(33) ← U(34) ← V(35/36)`; base logic lives in R, later classes only patch AOSP class/method renames. Unknown-newer SDKs fall back to V.
- **Deoptimization is mandatory.** Callers like `verifySignatures`/`reconcilePackages`/`canJoinSharedUserId` are AOT-inlined, so CorePatch calls `XposedBridge.deoptimizeMethod(...)` (LSPosed-only) or the nested hooks silently never fire.
- **Deliberate carve-out.** Signature-comparison bypass forces `checkCapability` true for **every** cap **except** `PERMISSION(4)` and `AUTH(16)` — a resigned imposter can overwrite a package but cannot inherit signature-level permissions or become an account authenticator. Preserving this exclusion is security-critical.
- **Config is XSharedPreferences + `MODE_WORLD_READABLE`** — dead at minSdk 28, so it must be fully replaced for Strombringer (this is the real porting work).

## 2. Exact hook points (class#method → effect)

**Downgrade** (`downgrade`, default ON)
- `PackageManagerServiceUtils#checkDowngrade(...)` / `PackageManagerService#checkDowngrade(...)` → `ReturnConstant(null)` (void methods that throw on downgrade → forced not to throw). Overloads differ per version: T=`AndroidPackage`, U=`pkg.AndroidPackage`, V adds `checkDowngrade(PackageSetting, PackageInfoLite)`.
- Q only: zero `mVersionCode`/`mVersionCodeMajor` on `PackageParser$Package` so any version compares as ≤.

**Signature comparison** (`digestCreak`, default ON)
- `android.content.pm.SigningDetails#checkCapability(SigningDetails,int)` (pre-T: `PackageParser$SigningDetails`) → true except caps 4/16.
- `SigningDetails#signaturesMatchExactly` → true (`exactSigCheck`, splits with different keys).
- `KeySetManagerService#shouldCheckUpgradeKeySetLocked` / `#checkUpgradeKeySetLocked` → true, **only** when the stack contains a frame whose method name `startsWith("preparePackage")` (survives 16's `preparePackageLI`→`preparePackage` rename).
- `PackageManagerServiceUtils#verifySignatures` → deoptimized (R+); on Q hooked directly → false.

**Digest / integrity** (`authcreak`, default **OFF**)
- `StrictJarVerifier#verify` / `#verifyMessageDigest` → true; `java.security.MessageDigest#isEqual` → true.
- `ApkSignatureVerifier#getMinimumSignatureSchemeVersionForTargetSdk(int)` → 0; `ScanPackageUtils#assertMinSignatureSchemeIsValid` → null.
- `ApkSigningBlockUtils#parseVerityDigestAndVerifySourceLength` → digest[0..32]; `#verifyIntegrityForVerityBasedAlgorithm` → null (T+ v2/v3 verity).
- `StrictJarVerifier` ctor sets `signatureSchemeRollbackProtectionsEnforced=false` (T+); `AssetManager#containsAllocatedTable` → false (resources.arsc alignment).
- `ApkSignatureVerifier#verifyV1Signature` → on error `-103` fabricates a `SigningDetails` (from installed app's certs via `UsePreSig`, re-read APK certs, or a **hardcoded platform Signature** fallback).

**Verification agent** (`disableVerificationAgent`, default ON)
- `isVerificationEnabled` on `PMS`(Q/R) / `VerificationParams`(T) / `VerifyingSession`(U/V) → false (kills Play Protect install scan).

**Shared-UID / permissions** (higher-risk, mostly to DROP)
- `SigningDetails#hasCommonAncestor` → true; `ReconcilePackageUtils.ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS` FINAL-strip → true; `reconcilePackages` deoptimized (`sharedUser`).
- `InstallPackageHelper#doesSignatureMatchForPermissions` → true when package names match (`UsePreSig`).

## 3. Android 14/15/16 viability

**API 36 (Android 16): YES, functional today** on stock/AOSP/Pixel-class ROMs with current CorePatch (v4.9). Concrete A16 upstream work landed: "fix permission signature check bypass on android 16" (#129), "fix downgrade check bypass on android 16" (#144), plus A15+ alternative `checkDowngrade` hooks. `CorePatchForV` handles both `BAKLAVA(36)` and `VANILLA_ICE_CREAM(35)` and resolves the renamed `com.android.internal.pm.parsing.pkg.ParsedPackage`.

**Caveats (the flaky edges, not the whole module):**
- **v3-only APKs** (no v1 fallback) can still fail with `INSTALL_PARSE_FAILED_NO_CERTIFICATES` (open issue #105, seen on A15 ColorOS Dec 2025 despite v4.8's fix). Community forks `rubenvereecken/CorePatch` and `kiber-io/CorePatch` have fuller v3 patches worth cherry-picking.
- **Shared-UID / platform-signed** installs remain broken/v1-limited (#123, #142). Do not assume system/privileged-app sig spoofing works.
- **OEM skins** (HyperOS, ColorOS 15, One UI 7) are hit-or-miss; `UsePreSig` documented as not working on HyperOS/MiUI.
- **Imminent API migration:** v4.9 is explicitly the **last release on the legacy Xposed API**; the next version rebases onto **libxposed API 101**. A fork taken now off `main` is about to diverge from an upstream rewrite — a deliberate choice is required (track frozen-legacy vs wait for the rewrite).

**Confidence: high** on "works on 16," **medium** on OEM/v3 edge behavior (community-report-based, not device-verified here).

## 4. Fork plan into Strombringer

Strombringer is already device-verified on Android 16 and minSdk 28 — matches CorePatch's compileSdk/minSdk. Lift `CorePatchForV` as the primary path (pulls in U→T→S→R via super); keep Q only if 9/10 coverage is wanted. Given Thor is minSdk 28, realistically only **V + U + a fallback** are needed.

**LIFT** (repackage under `com.valhalla.thor.ext.strombringer.corepatch`):
- `CorePatchForR/S/T/U/V.java` — the crown-jewel PMS/signature/downgrade hooks.
- `XposedHelper.java` — null-safe hook wrappers. **Consider dropping the hardcoded platform `SIGNATURE` constant** if you only want downgrade+digest, not blanket sig-spoof.
- `ReturnConstant.java` — but **rewire** off XSharedPreferences.
- The dispatch logic from `MainHook` (SDK_INT switch + `android`/`android` guard) folded **into Strombringer's existing single `IXposedHookLoadPackage`**, not a second entry class.

**DROP:** `SettingsActivity`, `res/xml/prefs.xml`, all resources, the XSharedPreferences dependency, OEM hooks (Nothing `NtConfigListServiceImpl`, Flyme), and the `sharedUser`/`ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS`/hidden-api-whitelist hooks (footguns with no Thor use case).

**xposedscope:** Strombringer's scope array currently lists the launcher package (auto-unfreeze). **Add `<item>android</item>`** so LSPosed injects into system_server. Keep `xposedminversion 93`, Xposed API `compileOnly`.

**Entry wiring:** in `handleLoadPackage`, branch on `lpparam.packageName`: `android`+`android` → run lifted CorePatch dispatch; launcher → existing auto-unfreeze hook. **Guard hard** so launcher code never executes in system_server (a wrong hook there = bootloop).

**Config wiring (the real work):** replace every `prefs.getBoolean(...)` with a small `Config` object (booleans: `downgrade`, `authcreak`, `digestCreak`, `disableVerificationAgent` — the minimal Thor set) populated by querying Thor's **exported ContentProvider** (mirroring the existing `FreezerBridgeProvider` IPC pattern). Because hooks run in restricted system_server, resolve the provider **lazily** via `AndroidAppHelper.currentApplication()`'s `ContentResolver`, **cache**, and refresh only out-of-band (on a Thor-poked signal / next install event) — **never query on the hot verify path** (ANR/deadlock risk).

**License — verify before copying code:** CorePatch is **GPLv2-only** (no "or later" header confirmed in the LICENSE/source). Strombringer is GPLv3. GPLv2-only and GPLv3 are **one-way incompatible** — you cannot fold GPLv2-only code into a GPLv3 work. Resolution paths: (a) relicense the combined module to GPLv2, (b) keep the CorePatch-derived hooks as a **separate GPLv2 unit**, or (c) **clean-room reimplement** the documented hook points (sidesteps the issue). Preserve attribution (weishu/LSPosed/yujincheng08) regardless.

## 5. Risks & required safeguards

**Vectors** (CorePatch is global, pref-gated, no per-install confirm, no installer allowlist — every install path on the device benefits while a toggle is on):
- **Different-signature overwrite → malware impersonation.** Any APK can overwrite an installed app of the same package name; a repackaged bank/authenticator inherits its package identity and deep links.
- **Downgrade / rollback attack.** Re-introduces already-patched vulnerabilities under the original signer.
- **Tampered-APK install** (`authcreak`) + **Play Protect disabled** (`disableVerificationAgent`) removes the last-line scanner.
- **Bootloop / soft-brick.** A wrong hook in system_server crashes boot (real: issue #132 crdroid A13; A17 beta needed FINAL-flag stripping, #147). Recoverable only by disabling the LSPosed module via root/recovery.
- **Attestation/Play-policy:** trips Play Integrity/SafetyNet — reinforces keeping this out of the base app.

**Required safeguards (invert CorePatch's posture — default-safe, scoped):**
1. **Default-OFF master gate.** Ship the whole capability disabled; do NOT inherit CorePatch's `digestCreak`/`downgrade`/`disableVerificationAgent` ON defaults. Require a one-time frictioned opt-in (type-to-confirm).
2. **Per-operation confirm** with SHA-256 signer fingerprints side-by-side (installed vs new), downgrade y/n, and a blunt "Thor is disabling Android's tamper/impersonation protection for this install; Thor is NOT responsible for resulting data loss/malware/damage." Affirmative action, not a pre-checked box. *(CorePatch has none — this is the single most important addition.)*
3. **Arm-then-disarm window.** Flip the pref ON immediately before Thor's `PackageInstaller` call for the user-approved package; the hook verifies the in-flight install matches the expected package name (make CorePatch's `UsePreSig` name-match guard **mandatory for all bypass paths**) and ideally that the initiating installer UID is Thor; flip OFF in a `finally` + watchdog timeout. **Never leave it enabled across reboots.**
4. **Never touch PERMISSION(4)/AUTH(16) or shared-UID.** Preserve CorePatch's carve-out; do not expose `sharedUser` at all.
5. **Kill-switch + fail-safe hooks.** One-tap "Disable all bypasses now." Wrap every system_server hook in try/catch that fails **safe** (skip hook, keep verification ON, never crash boot); pin/verify the exact Android SDK before hooking and bail with verification intact on unrecognized frameworks/OEM ROMs.
6. **Audit log** of every bypassed install (package, old/new signer, downgrade y/n, timestamp).

## 6. Open questions for the brainstorm (design decisions only the user can make)

1. **Downgrade may not need CorePatch at all.** Thor *already* downgrades via `pm install-create -r -g -d` in `InstallerRepositoryImpl`/`RootSystemGateway` (root + Shizuku), with no system_server hook and no boot risk. **Do you want the CorePatch `downgrade` hook at all, or reserve Strombringer strictly for the one thing gateways can't do — different-signature overwrite?** (Shizuku runs as UID 2000 and *cannot* hook system_server; only root+LSPosed can do sig-mismatch.) This meaningfully shrinks scope and bootloop surface.

2. **Fix Store today reinstalls the *same* APK** (`reinstallAppWithGoogle` = `pm install -r -d -i "com.android.vending"`) — it changes the installer attribution, not the signature. **Is the Strombringer goal to enable a *different-build* Fix Store (resigned/patched Play-flavored APK over an existing app), or just to remove downgrade friction?** These are very different risk profiles and drive whether `digestCreak` is even needed.

3. **Scope model: global-window vs true per-package.** Research is **low-confidence** on cleanly scoping a system_server hook to a single install — CorePatch's own model is global-pref-gated, and per-caller-UID/per-package enforcement inside the hook is unproven at this granularity. **Are you comfortable with the arm/confirm/disarm "window" approach (brief global exposure per Thor-initiated install), accepting that other install paths could theoretically slip through the window?**

4. **Reuse vs vendor/fork.** Angle-4 research recommends **reusing CorePatch as a user-installed LSPosed dependency** that Thor merely orchestrates (arm/confirm/disarm), inheriting upstream's per-Android-version maintenance and keeping Thor off the bootloop-ownership hook. **Do you want to own the fork (control, but perpetual per-API-level maintenance + the imminent libxposed-101 rewrite), or orchestrate upstream?**

5. **Legacy vs libxposed-101 base.** If forking: **base on frozen-legacy `main` (works today, will be abandoned) or wait for/track the libxposed-101 rewrite (future-proof, unreleased)?**

6. **License resolution.** GPLv2-only vs GPLv3 is a hard blocker for direct code reuse. **Pick one: relicense the combined module GPLv2, keep the derived hooks as a separate GPLv2 unit, or clean-room reimplement from the documented hook points?** (Note: the exact GPLv2 clause in every CorePatch source header was **not** independently confirmed — verify "or later" absence before copying any code.)
