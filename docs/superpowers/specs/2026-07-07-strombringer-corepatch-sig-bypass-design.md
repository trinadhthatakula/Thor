# Strombringer Plan 3 — CorePatch-derived Signature/Digest Bypass: Design

> **Status:** Design (brainstormed 2026-07-07). Supersedes the open questions in
> `docs/superpowers/research/2026-07-07-corepatch-research-brief.md`. Grounded against the live
> code in both repos (Thor `@ /Users/trinadhthatakula/StudioProjects/Thor`, Strombringer
> `@ /Users/trinadhthatakula/StudioProjects/Strombringer`). This is the spec; the implementation
> plan follows separately.

## 1. Goal & non-goals

**Goal.** Give Thor the ability to perform installs that Android's `PackageManagerService` (PMS)
normally blocks — specifically **different-signature overwrite** (install a resigned/patched build
over an existing package *without uninstalling*, preserving data) and **tampered/bad-digest
install** — by adding a forked, hardened subset of CorePatch's `system_server` hooks to the
existing **Strombringer** LSPosed module. All four CorePatch capabilities remain available to the
user, but each is routed to the *least-privileged* mechanism that can accomplish it; the
`system_server` hook is used only when it is the sole option, always behind a default-OFF master
gate and a per-operation frictioned confirm.

**Non-goals.**
- Not shipping any of this in the base Thor app. Strombringer stays a **separate, optional APK**
  (`com.valhalla.thor.ext.strombringer`); base Thor remains Xposed-free and Play-safe.
- Not spoofing system/privileged/shared-UID/platform-signed installs (CorePatch's own shared-UID
  and `PERMISSION`/`AUTH` capability paths are **deliberately excluded** — see §4).
- Not chasing the unreleased libxposed-API-101 rewrite (see §10).
- Not a general "disable all Android security" toggle. Every capability is scoped, confirmed, and
  audited.

## 2. Capability-routing model (the core principle)

The user directive: *implement full CorePatch parity, but for anything Thor can do **without**
hooking `system_server`, prefer that path and tell the user; escalate to the hook only when it is
the only way, or the user insists — and then confirm.* Combined with the **Hybrid** fork decision
(only fork the hook-only capabilities), this yields:

| CorePatch capability | Hook-free path in Thor? | Route | Forked hook? |
|---|---|---|---|
| **Downgrade** | ✅ `pm install … -d` (root session / Shizuku / Dhizuku — *already implemented*) | Gateway, no hook | **No** |
| **Disable Play Protect** (`disableVerificationAgent`) | ✅ *transient* `settings put global package_verifier_enable 0` → install → restore to `1` (**net-new**, root/Shizuku) | Gateway, no hook | **No** |
| **Different-signature overwrite** (`digestCreak` signature cluster) | ❌ only root + LSPosed can neuter `checkCapability` in `system_server` | **Hook only** | **Yes** |
| **Tampered-APK / bad digest** (`authcreak`) | ❌ PMS verifies digests regardless of installer privilege | **Hook only** | **Yes** |

**Why downgrade & Play-Protect are *not* forked as hooks:** the hook variants add **zero
capability** over the gateway paths (identical result) while adding bootloop surface. For
Play-Protect the gateway path is also made *transient* (restored in a `finally`), so it is not even
a persistent global weakening. There is therefore nothing to "escalate to a hook" for these two —
the safe path *is* the full-feature path, and Thor surfaces that to the user. "Insist → confirm →
allow" governs the two genuinely hook-only capabilities via the master gate + per-op confirm.

**Hard availability constraint:** the two hook capabilities require **PrivilegeMode = ROOT** *and*
an active LSPosed install with the Strombringer module enabled (Shizuku is UID 2000 and cannot hook
`system_server`; Dhizuku likewise). Under Shizuku/Dhizuku the CorePatch capabilities are shown as
**unavailable**, with an explanation. Downgrade + Play-Protect remain available under any privileged
gateway.

## 3. Architecture overview

Two repos change. They communicate **only** over an exported `ContentProvider` (arm's-length IPC),
never linked — which is what keeps the license split clean (§9) and keeps base Thor Play-safe.

```
┌───────────────────────────── Thor app (base, unchanged license) ─────────────────────────────┐
│ presentation/                                                                                 │
│   corepatch/  ── master opt-in (type-to-confirm)  +  per-op confirm dialog (signer diff)      │
│ domain/model/ ── UserPreferences (+ corePatchEnabled)                                         │
│ data/                                                                                          │
│   installer/InstallerRepositoryImpl ── arm(pkg,newSignerSha,deadline) → commit → disarm       │
│   gateway/* ── new transient Play-Protect disable; existing -d downgrade                      │
│   provider/CorePatchBridgeProvider ── exported, SYSTEM-uid-guarded, serves in-memory arm-state │
│   corepatch/CorePatchArmState ── in-memory holder (never persisted) + watchdog                │
│   corepatch/CorePatchAudit ── append-only audit log                                          │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
                              ▲ ContentResolver.call("getArmState")   (one IPC per install, coarse)
                              │
┌───────────────────────── Strombringer LSPosed module (relicensed GPLv2) ─────────────────────┐
│ XposedEntry.handleLoadPackage:                                                                 │
│    lpp.packageName == "android"  → CorePatchHook(classLoader).start()   [NEW system_server]   │
│    else (launcher pkgs)          → LaunchAppHook(...)                    [existing]            │
│ corepatch/  (Java, verbatim-lifted + stripped)                                                │
│    MainHook dispatch (SDK_INT) │ CorePatchForQ..V │ XposedHelper │ ReturnConstant             │
│    Config  ── booleans {digestCreak, authcreak}  ← cached from CorePatchBridgeProvider         │
│ res/values/arrays.xml: xposed_scope += <item>android</item>                                    │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 4. The forked hook set

**Base:** CorePatch legacy `main` (v4.9, the last legacy-Xposed-API release). Classes lifted
**verbatim as Java** (not ported to Kotlin) into
`com.valhalla.thor.ext.strombringer.corepatch` — verbatim preserves the proven per-Android-version
dispatch and the `deoptimizeMethod` calls, where a reimplementation bug = bootloop.

**LIFT (keep):**
- `CorePatchForQ / R / S / T / U / V` — the version-dispatched PMS hook classes. Cover **Q(28–29)
  through V(35/36)** because Thor's minSdk is 28. (`V` resolves the renamed
  `com.android.internal.pm.parsing.pkg.ParsedPackage`; `R` is the base for `R←S←T←U←V`; `Q` is
  standalone.)
- `XposedHelper` (null-safe hook wrappers), `ReturnConstant` — **rewired off XSharedPreferences**.
- The `MainHook` SDK_INT dispatch + the `packageName=="android" && processName=="android"` guard,
  folded into Strombringer's single `XposedEntry`/`CorePatchHook` (not a second entry class).

**Within the lifted classes, KEEP only these hook installations:**
- **Signature (`digestCreak`)**: `SigningDetails#checkCapability` → true **except caps
  `PERMISSION(4)` and `AUTH(16)`** (carve-out preserved — security-critical);
  `SigningDetails#signaturesMatchExactly` → true;
  `KeySetManagerService#shouldCheckUpgradeKeySetLocked` / `#checkUpgradeKeySetLocked` → true (only
  when a stack frame's method `startsWith("preparePackage")`);
  `PackageManagerServiceUtils#verifySignatures` → deoptimized (R+) / hooked→false (Q).
- **Digest (`authcreak`)**: `StrictJarVerifier#verify` / `#verifyMessageDigest` → true;
  `MessageDigest#isEqual` → true; `ApkSignatureVerifier#getMinimumSignatureSchemeVersionForTargetSdk`
  → 0; `ScanPackageUtils#assertMinSignatureSchemeIsValid` → null;
  `ApkSigningBlockUtils#parseVerityDigestAndVerifySourceLength` / `#verifyIntegrityForVerityBasedAlgorithm`;
  `StrictJarVerifier` ctor rollback flag; `AssetManager#containsAllocatedTable` → false;
  `ApkSignatureVerifier#verifyV1Signature` → `-103` fabrication path.

**STRIP (delete the hook installations entirely — smaller `system_server` surface):**
- All `checkDowngrade` hooks + Q's `mVersionCode` zeroing → **downgrade goes via gateway `-d`**.
- `isVerificationEnabled` (`disableVerificationAgent`) → **Play-Protect goes via gateway**.
- All shared-UID / permission hooks (`hasCommonAncestor`, `ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS`
  FINAL-strip, `reconcilePackages` deopt, `doesSignatureMatchForPermissions`).
- OEM hooks (Nothing `NtConfigListServiceImpl`, Flyme), hidden-api-whitelist hooks.
- The hardcoded platform `SIGNATURE` fallback constant in `verifyV1Signature` is **dropped** unless
  device testing shows a v1-fallback path needs it; prefer the installed-app-cert / re-read-APK-cert
  branches.

**Config surface** (what the user can toggle): `digestCreak` (sig overwrite) and `authcreak`
(tampered/digest). Both **default OFF**. `downgrade` and `disableVerificationAgent` are **not** hook
config — they don't exist as hooks here.

## 5. Runtime safety model — the arm/disarm window (decision: defense-in-depth)

The hooks are globally installed in `system_server` but produce a bypass **only** inside a narrow,
Thor-controlled window that is additionally guarded per-install. Layers, all must pass:

1. **Master gate.** `UserPreferences.corePatchEnabled` must be true (default false, one-time
   type-to-confirm to enable). If false, arm-state is never written; hooks read "disarmed" and
   no-op.
2. **Armed flag + deadline.** Thor writes arm-state
   `{armed=true, pkg, expectedNewSignerSha256, capability∈{sig,digest}, deadlineMillis}` to an
   **in-memory** holder (`CorePatchArmState`) immediately before the install and clears it in a
   `finally` after the install result callback. A watchdog coroutine force-clears at `deadline`.
   **In-memory only ⇒ never survives process death or reboot ⇒ fail-safe by construction.**
3. **Mandatory package-name match.** The hook bypasses only when the in-flight package name equals
   `armed.pkg` (CorePatch's `UsePreSig` name-match, made mandatory for *every* bypass path). A
   background update of any *other* package during the window keeps full verification.
4. **Mandatory signer match.** The hook is inspecting the very `SigningDetails` it is about to
   approve, so it also requires the in-flight APK's signer SHA-256 to equal
   `armed.expectedNewSignerSha256`. This closes the residual "concurrent *same-package* install"
   hole: an attacker would need to install the *exact* package **and** the *exact* signer the user
   just approved, in the sub-second window.
5. **Best-effort installer-UID check.** Where the framework exposes it at the hook site, require the
   initiating installer UID to be Thor's. Additive only — design does not depend on it.
6. **Fail-safe hooks.** Every hook body is wrapped in try/catch that, on *any* exception or any
   unmet guard, **skips the bypass and lets normal verification proceed** — it never throws into
   `system_server` (a throw there = bootloop). The SDK is pinned/verified before hooking; on an
   unrecognized framework/OEM shape the class bails with verification intact (§11).

**Config IPC topology (how the hook learns arm-state without deadlocking).** The brief's hard
constraint: never do IPC on the tight PMS verify path. Resolution:
- Thor exposes `CorePatchBridgeProvider` (authority `${applicationId}.corepatchbridge`,
  `exported=true`), `call("getArmState")` → `Bundle` of the arm-state, **guarded by
  `Binder.getCallingUid() == Process.SYSTEM_UID`** (only `system_server` may read it). The provider
  reads the same-process in-memory `CorePatchArmState` — no disk, no cross-process work on Thor's
  side.
- The hook obtains a `Context` in `system_server` via
  `ActivityThread.currentActivityThread().getSystemContext()` (⚠ **net-new**: the existing
  `LaunchAppHook` uses `p.thisObject as Context`, which does not exist in `system_server`; and
  `AndroidAppHelper` is not yet used in the repo). It calls `getArmState` **once per install at a
  coarse hook point** (install-session entry, *before* the verify lock — candidate sites:
  `PackageManagerService#installStage` / `VerifyingSession` ctor / `InstallParams` construction, to
  be pinned during implementation), caches the result in-memory, and the fine sig/digest hooks read
  only that cache. If the provider query returns null (Thor stopped/uninstalled) the hook stays
  disarmed → fail-safe.

## 6. UX

1. **Master opt-in (net-new, `presentation/corepatch/` + Settings).** A **type-to-confirm** gate
   (net-new — Thor has only yes/no `AlertDialog`s today) that enables `corePatchEnabled`. Copy is
   blunt about disabling Android's tamper/impersonation protections and Thor's non-responsibility.
   Only shown when PrivilegeMode = ROOT + LSPosed/Strombringer active; otherwise the whole section
   is disabled with an explanation.
2. **Per-operation confirm (net-new).** Before any hook-backed install, a dialog shows the
   installed vs new **SHA-256 signer fingerprints side-by-side**, the package, the capability being
   used (sig overwrite / tampered), and a blunt warning. Affirmative action (not a pre-checked
   box); optionally gated behind the existing `BiometricPromptHandler` when `biometricLockEnabled`.
   The confirmed new-signer SHA-256 becomes `armed.expectedNewSignerSha256` (§5.4).
3. **Audit log (`CorePatchAudit`).** Append-only record per bypassed install: package, old signer,
   new signer, capability, downgrade y/n, timestamp, result. Viewable/exportable from the CorePatch
   settings section.
4. **Kill-switch.** One tap "Disable all bypasses now" → sets `corePatchEnabled=false`, clears
   arm-state. (Disabling the LSPosed module remains the recovery path if a ROM ever bootloops.)

## 7. Play-Protect & downgrade via gateway (no hook)

- **Downgrade** — already implemented; keep as-is (`RootSystemGateway` `-d` session builder;
  `InstallerRepositoryImpl.installWithShizuku/Dhizuku` `-d`; `performPackageInstallerInstall`
  `setRequestDowngrade`).
- **Play-Protect disable (net-new)** — add `SystemGateway.setPackageVerifierEnabled(Boolean)` (root
  + Shizuku shell: `settings put global package_verifier_enable 0|1`). Used **transiently**:
  read current value → set 0 → install → restore in `finally`. Surfaced to the user as "done safely
  without a system_server hook."

## 8. Fork base & module wiring

- **Legacy Xposed API** (`de.robv.android.xposed:api:82`, already Strombringer's dep). Matches the
  existing auto-unfreeze hook; no libxposed-101 migration now.
- `res/values/arrays.xml` `xposed_scope` **+= `<item>android</item>`** (system_server injection).
- `XposedEntry.handleLoadPackage`: add, before the launcher path,
  `if (lpp.packageName == "android") { CorePatchHook(lpp.classLoader).start(); return }`.
  **Guard hard** so launcher code never runs in `system_server`.
- New Java sources under `.../strombringer/corepatch/`. Kotlin↔Java interop is fine in one Android
  module; keep hook classes Java (verbatim), keep `CorePatchHook` entry + `Config` in either
  (Kotlin `Config` object querying the provider is natural).

## 9. License

- Strombringer currently has **no LICENSE file and no headers** ("GPLv3" was never expressed
  in-repo). Resolution: **add a `GPLv2` `LICENSE`** to the Strombringer repo and GPLv2 headers to
  the lifted `corepatch/*.java` (and, for single-license clarity, the module's own sources).
  Preserve attribution: weishu / LSPosed / yujincheng08 (`CorePatch`).
- **Verify before copying:** confirm CorePatch's source headers/LICENSE are GPLv2 with **no "or
  later"** clause (assumed but not independently confirmed in research). If any file is GPLv2+, note
  it.
- Base Thor's license is untouched — Strombringer is a separate APK reached only via IPC.

## 10. Error handling, fail-safe, framework pinning

- Pin `Build.VERSION.SDK_INT` and dispatch; unknown-newer falls back to `V`; on any class/method
  resolution failure the specific hook is **skipped**, not fatal.
- Every hooked method body: try/catch → on error, do nothing (verification proceeds).
- Coarse-refresh IPC failures → disarmed → no bypass.
- Watchdog + `finally` guarantee disarm even if the install callback never fires.

## 11. Testing strategy

`system_server` hooks can't be JVM-unit-tested; split verification:
- **Unit (Thor, JVM):** `CorePatchArmState` (arm/disarm/watchdog/never-persist), routing selection,
  `CorePatchBridgeProvider.call` guard (SYSTEM-uid accept / other-uid reject), audit append, signer
  SHA-256 computation.
- **Instrumented (Thor):** per-op confirm produces the exact `expectedNewSignerSha256`; transient
  Play-Protect flip restores on success and on failure.
- **Device (root + LSPosed, Android 16 + a 14/15 if available):** end-to-end sig-overwrite of a
  self-signed test app pair (same package, two different keys); confirm bypass only fires for the
  armed package+signer; confirm a concurrent *other-package* install is unaffected; confirm disarm
  after completion; deliberate bootloop-safety check (force a hook exception → device still boots,
  verification intact).

## 12. File-by-file change list

**Strombringer** (`/Users/trinadhthatakula/StudioProjects/Strombringer`)
- `app/src/main/res/values/arrays.xml` — add `<item>android</item>` to `xposed_scope`.
- `.../strombringer/XposedEntry.kt` — add `"android"` branch → `CorePatchHook`.
- `.../strombringer/corepatch/` (**new**): `CorePatchHook` (entry+dispatch), `CorePatchForQ..V.java`,
  `XposedHelper.java`, `ReturnConstant.java` (rewired), `Config` (queries CorePatchBridgeProvider,
  caches).
- `LICENSE` (**new**, GPLv2) + headers.

**Thor** (`/Users/trinadhthatakula/StudioProjects/Thor`)
- `domain/model/UserPreferences.kt` — `+ corePatchEnabled: Boolean = false`.
- `data/repository/PreferenceRepositoryImpl.kt` (+ `PreferenceRepository.kt`) — key + flow mapping +
  setter.
- `domain/gateway/SystemGateway.kt` (+ Root/Shizuku/Dhizuku impls) —
  `setPackageVerifierEnabled(Boolean)`.
- `data/corepatch/CorePatchArmState.kt` (**new**) — in-memory holder + watchdog.
- `data/corepatch/CorePatchAudit.kt` (**new**) — append-only log.
- `data/provider/CorePatchBridgeProvider.kt` (**new**) + `AndroidManifest.xml` provider entry
  (`exported=true`, SYSTEM-uid guard in code, mirroring `FreezerBridgeProvider`).
- `data/repository/InstallerRepositoryImpl.kt` — wrap `performPackageInstallerInstall` (and the root
  session path) with arm → commit → disarm; transient Play-Protect flip; audit write.
- `presentation/corepatch/` (**new**) — master opt-in (type-to-confirm) + per-op confirm dialog
  (signer diff) + audit viewer; wired into `presentation/settings/`.
- `di/Modules.kt` — register new singletons/provider deps (Koin).

## 13. Risks & open items (for the plan)

- **Bootloop** is the dominant risk; §5.6/§10 fail-safe posture + device bootloop test are
  mandatory acceptance gates.
- **v3-only APK / OEM skins** (HyperOS, ColorOS 15, One UI 7) edge failures are community-reported,
  not device-verified here; community forks (`rubenvereecken`, `kiber-io`) have fuller v3 patches to
  cherry-pick if needed.
- **Coarse refresh hook site** (§5) is the one implementation unknown to pin first on-device.
- **Attestation:** using the hook trips Play Integrity — reinforces default-OFF + keeping this out
  of base Thor.
- **libxposed-101** rewrite will eventually obsolete the legacy fork; revisit when it ships (§8).

## 14. Out of scope / future

Shared-UID/platform-signature spoofing; libxposed-101 rebase; a "different-build Fix Store"
(resigned Play-flavored APK over an existing app) is *enabled* by the sig-overwrite capability but
its UX is a separate follow-up, not this spec.

## 15. Post-review refinements (2026-07-07, adversarial review `wf_00a2c535-1f4`)

The design decisions above stand; a 5-lens review of the implementation plan corrected these
mechanics (authoritative detail lives in the plan, `docs/superpowers/plans/2026-07-07-strombringer-corepatch-sig-bypass.md`):

1. **Root-synchronous scoping (refines §5.2/§5.3).** CorePatch arm/disarm attaches ONLY to the
   synchronous root `pm install-commit` shell path (blocks through PMS verification, so arm-state is
   held while the hook fires). The async `PackageInstaller` path never carries an authorization — it
   would disarm before the hook ran. CorePatch is root-only, so this is its only path anyway.
2. **Coarse per-install entry hook + thread-scoped token (refines §5.3-§5.5).** A single coarse hook
   fires once per install (off the verify lock), does the bounded arm-state IPC, matches
   pkg+signer?+capability+deadline+installer-UID, and sets a `ThreadLocal` token that the fine
   sig/digest hooks read. The fine digest hooks (`MessageDigest#isEqual` etc.) get no package/signer,
   so the thread token — not a process-global flag — is what confines them to the one matched
   install.
3. **Epoch clock domain (§5.2).** `deadlineMillis` and all comparisons use `System.currentTimeMillis()`
   on both sides — never `SystemClock` (boot-relative).
4. **Lazy expiry + `finally`-disarm replace the active watchdog (§5.2).** TTL is a crash-only backstop.
5. **Play-Protect self-heal (§7).** A durable marker + startup reconciler force-restores
   `package_verifier_enable=1` if Thor is killed mid-install.
6. **Bounded, off-lock IPC (§5).** The coarse hook's provider call runs on a worker thread with an
   800ms timeout defaulting to DISARMED, so a frozen/ANRing Thor cannot trip the `system_server`
   watchdog.
