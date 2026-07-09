# Strombringer CorePatch Signature/Digest Bypass — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a default-OFF, per-operation-confirmed, root-only capability for Thor to perform installs Android normally blocks — different-signature overwrite and tampered/bad-digest install — by forking a hardened subset of CorePatch's `system_server` hooks into the Strombringer LSPosed module, while downgrade and Play-Protect-disable stay as hook-free gateway shell commands.

**Architecture:** Two repos, communicating only over one exported `ContentProvider` (arm's-length IPC, never linked). Thor holds an in-memory "arm-state" that authorizes exactly one install (package + expected new-signer SHA-256 + capability + deadline), exposes it to `system_server` via `CorePatchBridgeProvider`, and arms/disarms it around each user-confirmed install. Strombringer's forked CorePatch hooks, injected into `system_server`, produce a signature/digest bypass **only** when the in-flight install matches the armed state.

**Tech Stack:** Kotlin + Jetpack Compose + Koin + DataStore (Thor); Java + legacy Xposed API (`de.robv.android.xposed:api:82`) for the lifted CorePatch hooks (Strombringer); JUnit + Robolectric for JVM/instrumented tests; device testing (root + LSPosed) for the hooks.

## Global Constraints

Every task's requirements implicitly include this section. Values are copied verbatim from the design spec (`docs/superpowers/specs/2026-07-07-strombringer-corepatch-sig-bypass-design.md`).

- **Default-OFF.** `UserPreferences.corePatchEnabled` defaults `false`. No hook bypass can occur unless the user has completed the one-time type-to-confirm opt-in AND a per-operation confirm.
- **Root + LSPosed only.** The two hook capabilities are offered ONLY when `PrivilegeMode == ROOT` and the Strombringer LSPosed module is active. Shizuku/Dhizuku cannot hook `system_server`; show them as unavailable. Downgrade + Play-Protect-disable remain available under any privileged gateway.
- **Arm-state Bundle contract** (returned by `CorePatchBridgeProvider.call("getArmState", null, null)` and consumed by the hook — this is the ONE cross-repo interface, keep it byte-identical on both sides):
  - `"armed"`: `Boolean` — true iff a bypass is currently authorized.
  - `"pkg"`: `String` — the exact package name authorized (absent when not armed).
  - `"signerSha256"`: `String` — uppercase hex SHA-256 of the approved new-APK signer certificate, no separators (absent when not armed).
  - `"capability"`: `String` — `"sig"` or `"digest"` (absent when not armed).
  - `"deadlineMillis"`: `Long` — epoch millis after which the arm is void.
- **Authorization predicate** (identical semantics on Thor's Kotlin holder and the Java hook): a bypass is allowed iff `armed == true` AND `now <= deadlineMillis` AND `candidatePkg == pkg` AND `candidateSignerSha256` equals `signerSha256` case-insensitively AND `candidateCapability == capability`.
- **Clock domain.** `deadlineMillis` and every `now` compared to it are **epoch wall-clock millis** (`System.currentTimeMillis()`) on BOTH the Thor holder AND the Java hook. NEVER `SystemClock.elapsedRealtime/uptimeMillis` (boot-relative — comparing it to an epoch deadline makes expiry a no-op).
- **Per-install thread-scoped enforcement.** A single **coarse per-install entry hook** (fires once per install, sees the target package + initiating installer UID) refreshes arm-state and, on a match, sets a **`ThreadLocal` arm token**; the fine sig/digest hooks bypass ONLY when that thread token is present (sig hooks additionally re-check the signer they receive). The fine digest hooks (`MessageDigest#isEqual`, `StrictJarVerifier`, verity) receive no package/signer, so they MUST rely on the thread token, never a process-global boolean — otherwise a concurrent different-package install in the same window is also bypassed.
- **Root-synchronous scoping.** CorePatch authorizations attach ONLY to the **synchronous root `pm install-create/write/commit` shell path** (which blocks through PMS verification, so arm-state is naturally held while the hook fires). This matches the root-only constraint. The async `PackageInstaller` path (`performPackageInstallerInstall`) NEVER carries a `CorePatchAuthorization`. `disarm` + Play-Protect-restore + audit run in a `finally` after the blocking install call; the TTL is a crash-only backstop, not the primary window.
- **Never persisted.** Arm-state lives only in a process-memory singleton — never DataStore, never disk. Process death or reboot ⇒ disarmed ⇒ fail-safe. (Play-Protect state IS reconciled at startup — see Task 9 — because that setting is durable/global.)
- **DI pattern.** Thor uses **Koin Annotations** (`@Module @ComponentScan` `AppModule` + `@Single`) — NOT the `module { single { } }` DSL. New singletons are registered with `@Single` annotations (verify the exact pattern in `di/Modules.kt` at execution).
- **Install mode enum.** `InstallerRepository.installPackage(uri: Uri, mode: InstallMode, canDowngrade: Boolean = false, ...)` uses `InstallMode` (`NORMAL/SHIZUKU/DHIZUKU/ROOT/EXTERNAL`), which is DISTINCT from `PrivilegeMode` (`NONE/ROOT/SHIZUKU/DHIZUKU`). Do not conflate them.
- **Preserve the carve-out.** `SigningDetails#checkCapability` bypass must remain FALSE for `PERMISSION(4)` and `AUTH(16)`. Never expose or hook shared-UID paths.
- **Fail-safe hooks.** Every `system_server` hook body is wrapped in try/catch; on ANY exception or any unmet guard it does nothing and lets normal verification proceed. A throw into `system_server` = bootloop. Pin `Build.VERSION.SDK_INT`; bail (verification intact) on unrecognized frameworks.
- **License.** Strombringer is relicensed **GPLv2** (add `LICENSE` + headers; it currently has none). CorePatch code is lifted verbatim as Java; preserve attribution (weishu / LSPosed / yujincheng08). Verify CorePatch source is GPLv2 with NO "or later" before copying (Task 13).
- **Versioning.** Any Thor change that ships bumps `versionCode` in `gradle.properties` (never edit `versionName`). Strombringer version bump lives in its `gradle/libs.versions.toml`.
- **Kept hooks only.** Forked classes install ONLY the signature (`digestCreak`) and digest (`authcreak`) hooks. STRIP every `checkDowngrade`, `isVerificationEnabled`, shared-UID, OEM, and hidden-api hook.

---

## Phase A — Thor: hook-free Play-Protect capability (no Strombringer dependency)

Ships independently. Zero bootloop risk. Downgrade already exists (`RootSystemGateway` `-d` session builder, `InstallerRepositoryImpl.installWithShizuku/Dhizuku` `-d`) — no work needed there; this phase adds only the transient Play-Protect toggle.

### Task 1: Play-Protect gateway methods (interface + Root impl)

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/gateway/SystemGateway.kt` (add two methods to the interface, near the existing install/uninstall signatures ~line 24-26)
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt` (implement both)
- Test: `app/src/test/java/com/valhalla/thor/data/gateway/PackageVerifierParsingTest.kt`

**Interfaces:**
- Produces:
  - `suspend fun SystemGateway.setPackageVerifierEnabled(enabled: Boolean): Result<Unit>`
  - `suspend fun SystemGateway.isPackageVerifierEnabled(): Result<Boolean>` (unset/`null`/error-free-but-empty ⇒ `true`, the Android default)
  - `internal fun parsePackageVerifierValue(raw: String?): Boolean` (pure parser, shared by impls; unset/`"null"`/blank ⇒ `true`, `"0"` ⇒ `false`, else `true`)

- [ ] **Step 1: Write the failing test** for the pure parser.

```kotlin
// PackageVerifierParsingTest.kt
package com.valhalla.thor.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class PackageVerifierParsingTest {
    @Test fun `zero means disabled`() = assertFalse(parsePackageVerifierValue("0"))
    @Test fun `one means enabled`() = assertTrue(parsePackageVerifierValue("1"))
    @Test fun `null string means default enabled`() = assertTrue(parsePackageVerifierValue("null"))
    @Test fun `blank means default enabled`() = assertTrue(parsePackageVerifierValue("  "))
    @Test fun `actual null means default enabled`() = assertTrue(parsePackageVerifierValue(null))
    @Test fun `garbage means enabled`() = assertTrue(parsePackageVerifierValue("banana"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.gateway.PackageVerifierParsingTest"`
Expected: FAIL — `parsePackageVerifierValue` unresolved.

- [ ] **Step 3: Write the parser + interface + Root impl.**

In `RootSystemGateway.kt` (top-level, file scope), add:

```kotlin
internal fun parsePackageVerifierValue(raw: String?): Boolean {
    val v = raw?.trim()
    if (v.isNullOrEmpty() || v.equals("null", ignoreCase = true)) return true // Android default = enabled
    return v != "0"
}
```

In `SystemGateway.kt` interface, add near the other install methods:

```kotlin
/** Global Play Protect / package verifier toggle. Used transiently around bypass installs. */
suspend fun setPackageVerifierEnabled(enabled: Boolean): Result<Unit>
suspend fun isPackageVerifierEnabled(): Result<Boolean>
```

In `RootSystemGateway`, implement using the existing `executeShellCommand(...)` helper (it already returns `Result<Pair<Int, String?>>`):

```kotlin
override suspend fun setPackageVerifierEnabled(enabled: Boolean): Result<Unit> =
    executeShellCommand("settings put global package_verifier_enable ${if (enabled) 1 else 0}")
        .map { }

override suspend fun isPackageVerifierEnabled(): Result<Boolean> =
    executeShellCommand("settings get global package_verifier_enable")
        .map { (_, out) -> parsePackageVerifierValue(out) }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.gateway.PackageVerifierParsingTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Verify the interface compiles across all gateways** — this will FAIL to compile until Task 2 (Shizuku/Dhizuku don't yet implement the new methods). That is expected; do NOT commit yet. Proceed to Task 2, then commit both together.

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: FAIL — `ShizukuSystemGateway`/`DhizukuSystemGateway` are not abstract and do not implement `setPackageVerifierEnabled`.

### Task 2: Play-Protect impls for Shizuku + Dhizuku, commit Phase A

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/ShizukuSystemGateway.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/DhizukuSystemGateway.kt`

**Interfaces:**
- Consumes: `parsePackageVerifierValue`, `executeShellCommand` (both gateways already have an `executeShellCommand` used by `reinstallAppWithGoogle`).

- [ ] **Step 1: Implement in both gateways** (identical bodies, using each gateway's own `executeShellCommand`):

```kotlin
override suspend fun setPackageVerifierEnabled(enabled: Boolean): Result<Unit> =
    executeShellCommand("settings put global package_verifier_enable ${if (enabled) 1 else 0}").map { }

override suspend fun isPackageVerifierEnabled(): Result<Boolean> =
    executeShellCommand("settings get global package_verifier_enable")
        .map { (_, out) -> parsePackageVerifierValue(out) }
```

- [ ] **Step 2: Compile the whole module**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 3: Run the parser test again**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.gateway.PackageVerifierParsingTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/gateway/SystemGateway.kt \
        app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt \
        app/src/main/java/com/valhalla/thor/data/gateway/ShizukuSystemGateway.kt \
        app/src/main/java/com/valhalla/thor/data/gateway/DhizukuSystemGateway.kt \
        app/src/test/java/com/valhalla/thor/data/gateway/PackageVerifierParsingTest.kt
git commit -m "feat(gateway): transient package-verifier toggle for bypass installs"
```

---

## Phase B — Thor: CorePatch orchestration substrate

The machinery that authorizes exactly one install and serves that authorization to `system_server`. All JVM/Robolectric-testable; safe even before the hook exists (arming is simply inert without a hook reading it).

### Task 3: `ArmState` model + authorization predicate (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/CorePatchArmState.kt`
- Test: `app/src/test/java/com/valhalla/thor/domain/model/ArmStateAuthorizationTest.kt`

**Interfaces:**
- Produces:
  - `data class ArmState(val armed: Boolean, val pkg: String?, val signerSha256: String?, val capability: String?, val deadlineMillis: Long)`
  - `val ArmState.Companion` `DISARMED` constant: `ArmState(false, null, null, null, 0L)`
  - `fun ArmState.authorizes(nowMillis: Long, candidatePkg: String, candidateSignerSha256: String, candidateCapability: String): Boolean`

- [ ] **Step 1: Write the failing test.**

```kotlin
// ArmStateAuthorizationTest.kt
package com.valhalla.thor.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmStateAuthorizationTest {
    private val armed = ArmState(
        armed = true, pkg = "com.foo",
        signerSha256 = "AABBCC", capability = "sig", deadlineMillis = 1_000L,
    )

    @Test fun `authorizes exact match before deadline`() =
        assertTrue(armed.authorizes(900L, "com.foo", "aabbcc", "sig")) // case-insensitive signer

    @Test fun `rejects when disarmed`() =
        assertFalse(ArmState.DISARMED.authorizes(900L, "com.foo", "AABBCC", "sig"))

    @Test fun `rejects after deadline`() =
        assertFalse(armed.authorizes(1_001L, "com.foo", "AABBCC", "sig"))

    @Test fun `rejects wrong package`() =
        assertFalse(armed.authorizes(900L, "com.bar", "AABBCC", "sig"))

    @Test fun `rejects wrong signer`() =
        assertFalse(armed.authorizes(900L, "com.foo", "DDEEFF", "sig"))

    @Test fun `rejects wrong capability`() =
        assertFalse(armed.authorizes(900L, "com.foo", "AABBCC", "digest"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.ArmStateAuthorizationTest"`
Expected: FAIL — unresolved `ArmState`.

- [ ] **Step 3: Implement.**

```kotlin
// CorePatchArmState.kt
package com.valhalla.thor.domain.model

data class ArmState(
    val armed: Boolean,
    val pkg: String?,
    val signerSha256: String?,
    val capability: String?,
    val deadlineMillis: Long,
) {
    companion object {
        val DISARMED = ArmState(false, null, null, null, 0L)
    }
}

fun ArmState.authorizes(
    nowMillis: Long,
    candidatePkg: String,
    candidateSignerSha256: String,
    candidateCapability: String,
): Boolean =
    armed &&
        nowMillis <= deadlineMillis &&
        pkg == candidatePkg &&
        signerSha256?.equals(candidateSignerSha256, ignoreCase = true) == true &&
        capability == candidateCapability
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.ArmStateAuthorizationTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/CorePatchArmState.kt \
        app/src/test/java/com/valhalla/thor/domain/model/ArmStateAuthorizationTest.kt
git commit -m "feat(corepatch): arm-state model + authorization predicate"
```

### Task 4: In-memory arm-state holder with watchdog (TDD)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/corepatch/CorePatchArmStateHolder.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/corepatch/CorePatchArmStateHolderTest.kt`

**Interfaces:**
- Consumes: `ArmState`, `ArmState.DISARMED` (Task 3).
- Produces:
  - `class CorePatchArmStateHolder(private val clock: () -> Long = System::currentTimeMillis)`
  - `fun arm(pkg: String, signerSha256: String, capability: String, ttlMillis: Long): ArmState`
  - `fun disarm()`
  - `fun current(): ArmState` — returns `DISARMED` if never armed OR past deadline (lazy expiry, no coroutine needed for correctness; watchdog is defense-in-depth).
  - `fun toBundleMap(): Map<String, Any>` — the exact keys of the Global-Constraints Bundle contract, computed from `current()`.

- [ ] **Step 1: Write the failing test.**

```kotlin
// CorePatchArmStateHolderTest.kt
package com.valhalla.thor.data.corepatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePatchArmStateHolderTest {
    private var now = 0L
    private val holder = CorePatchArmStateHolder(clock = { now })

    @Test fun `starts disarmed`() = assertFalse(holder.current().armed)

    @Test fun `arm then current is armed with fields`() {
        holder.arm("com.foo", "AABB", "sig", ttlMillis = 100)
        val s = holder.current()
        assertTrue(s.armed)
        assertEquals("com.foo", s.pkg)
        assertEquals("AABB", s.signerSha256)
        assertEquals("sig", s.capability)
        assertEquals(100L, s.deadlineMillis)
    }

    @Test fun `expires after deadline via lazy clock`() {
        holder.arm("com.foo", "AABB", "sig", ttlMillis = 100)
        now = 101
        assertFalse(holder.current().armed)
    }

    @Test fun `disarm clears immediately`() {
        holder.arm("com.foo", "AABB", "sig", ttlMillis = 100)
        holder.disarm()
        assertFalse(holder.current().armed)
    }

    @Test fun `bundle map matches contract when armed`() {
        holder.arm("com.foo", "AABB", "sig", ttlMillis = 100)
        val m = holder.toBundleMap()
        assertEquals(true, m["armed"])
        assertEquals("com.foo", m["pkg"])
        assertEquals("AABB", m["signerSha256"])
        assertEquals("sig", m["capability"])
        assertEquals(100L, m["deadlineMillis"])
    }

    @Test fun `bundle map is disarmed-only when not armed`() {
        val m = holder.toBundleMap()
        assertEquals(false, m["armed"])
        assertFalse(m.containsKey("pkg"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.corepatch.CorePatchArmStateHolderTest"`
Expected: FAIL — unresolved `CorePatchArmStateHolder`.

- [ ] **Step 3: Implement.**

```kotlin
// CorePatchArmStateHolder.kt
package com.valhalla.thor.data.corepatch

import com.valhalla.thor.domain.model.ArmState
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory ONLY. Never persisted — process death / reboot => disarmed => fail-safe.
 * Lazy expiry against the injected clock guarantees correctness even if a watchdog is missed.
 */
class CorePatchArmStateHolder(private val clock: () -> Long = System::currentTimeMillis) {
    private val ref = AtomicReference(ArmState.DISARMED)

    fun arm(pkg: String, signerSha256: String, capability: String, ttlMillis: Long): ArmState {
        val s = ArmState(true, pkg, signerSha256, capability, clock() + ttlMillis)
        ref.set(s)
        return s
    }

    fun disarm() = ref.set(ArmState.DISARMED)

    fun current(): ArmState {
        val s = ref.get()
        return if (s.armed && clock() <= s.deadlineMillis) s else ArmState.DISARMED
    }

    fun toBundleMap(): Map<String, Any> {
        val s = current()
        if (!s.armed) return mapOf("armed" to false)
        return mapOf(
            "armed" to true,
            "pkg" to s.pkg!!,
            "signerSha256" to s.signerSha256!!,
            "capability" to s.capability!!,
            "deadlineMillis" to s.deadlineMillis,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.corepatch.CorePatchArmStateHolderTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/corepatch/CorePatchArmStateHolder.kt \
        app/src/test/java/com/valhalla/thor/data/corepatch/CorePatchArmStateHolderTest.kt
git commit -m "feat(corepatch): in-memory arm-state holder with lazy expiry"
```

### Task 5: Signer SHA-256 helper (TDD on the pure formatter; APK read is thin)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/corepatch/ApkSignerSha.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/corepatch/SignerShaFormatTest.kt`

**Interfaces:**
- Produces:
  - `fun ByteArray.toSignerSha256Hex(): String` (pure — SHA-256 the bytes, uppercase hex, no separators)
  - `object ApkSignerSha { fun ofApk(context: Context, apkPath: String): String?; fun ofInstalled(context: Context, packageName: String): String? }` (uses `PackageManager.getPackageArchiveInfo` / `getPackageInfo` with `GET_SIGNING_CERTIFICATES`; returns null on any failure)

- [ ] **Step 1: Write the failing test** (pure hex-of-digest; known SHA-256 of the empty byte array).

```kotlin
// SignerShaFormatTest.kt
package com.valhalla.thor.data.corepatch

import org.junit.Assert.assertEquals
import org.junit.Test

class SignerShaFormatTest {
    @Test fun `sha256 of empty cert bytes is known uppercase hex no separators`() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val hex = ByteArray(0).toSignerSha256Hex()
        assertEquals("E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855", hex)
        assertEquals(64, hex.length)
    }

    @Test fun `hex has no colons`() {
        assertEquals(false, byteArrayOf(1, 2, 3).toSignerSha256Hex().contains(":"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.corepatch.SignerShaFormatTest"`
Expected: FAIL — unresolved `toSignerSha256Hex`.

- [ ] **Step 3: Implement.**

```kotlin
// ApkSignerSha.kt
package com.valhalla.thor.data.corepatch

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

fun ByteArray.toSignerSha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02X".format(it) }

object ApkSignerSha {

    private fun firstSignerSha(signatures: Array<Signature>?): String? =
        signatures?.firstOrNull()?.toByteArray()?.toSignerSha256Hex()

    @Suppress("DEPRECATION")
    fun ofApk(context: Context, apkPath: String): String? = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return null
        val sigs = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures
        firstSignerSha(sigs)
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun ofInstalled(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageInfo(packageName, flags)
        val sigs = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures
        firstSignerSha(sigs)
    }.getOrNull()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.corepatch.SignerShaFormatTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/corepatch/ApkSignerSha.kt \
        app/src/test/java/com/valhalla/thor/data/corepatch/SignerShaFormatTest.kt
git commit -m "feat(corepatch): APK/installed signer SHA-256 helper"
```

### Task 6: `CorePatchBridgeProvider` (exported, SYSTEM-uid guarded)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/provider/CorePatchBridgeProvider.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add provider next to the existing `FreezerBridgeProvider` at ~line 333)
- Test: `app/src/test/java/com/valhalla/thor/data/provider/CorePatchCallerGuardTest.kt`

**Interfaces:**
- Consumes: `CorePatchArmStateHolder.toBundleMap()` (Task 4), Koin `get()` like `FreezerBridgeProvider`.
- Produces:
  - Authority `${applicationId}.corepatchbridge`; `call("getArmState", null, null): Bundle` (the contract map, built via `Bundle`).
  - `internal fun isSystemCaller(callingUid: Int): Boolean` (pure: `callingUid == android.os.Process.SYSTEM_UID`) — the guard, unit-tested in isolation.

- [ ] **Step 1: Write the failing test** on the pure guard.

```kotlin
// CorePatchCallerGuardTest.kt
package com.valhalla.thor.data.provider

import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePatchCallerGuardTest {
    @Test fun `system uid is allowed`() = assertTrue(isSystemCaller(Process.SYSTEM_UID))
    @Test fun `app uid is rejected`() = assertFalse(isSystemCaller(10234))
    @Test fun `root uid is rejected`() = assertFalse(isSystemCaller(0))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.provider.CorePatchCallerGuardTest"`
Expected: FAIL — unresolved `isSystemCaller`. NOTE: the repo has **no Robolectric** dependency. `android.os.Process.SYSTEM_UID` is a compile-time constant (`1000`) that inlines under plain JVM, so this test runs as-is; if the Android stub throws "not mocked", hardcode `1000` in the test rather than adding Robolectric.

- [ ] **Step 3: Implement.**

```kotlin
// CorePatchBridgeProvider.kt
package com.valhalla.thor.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.valhalla.thor.data.corepatch.CorePatchArmStateHolder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

internal fun isSystemCaller(callingUid: Int): Boolean = callingUid == Process.SYSTEM_UID

class CorePatchBridgeProvider : ContentProvider(), KoinComponent {
    private val armState: CorePatchArmStateHolder by inject()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        // Only system_server may read arm-state. Any other caller sees "disarmed".
        if (!isSystemCaller(Binder.getCallingUid())) {
            result.putBoolean("armed", false)
            return result
        }
        if (method != "getArmState") {
            result.putBoolean("armed", false)
            return result
        }
        val map = armState.toBundleMap()
        result.putBoolean("armed", map["armed"] as Boolean)
        (map["pkg"] as? String)?.let { result.putString("pkg", it) }
        (map["signerSha256"] as? String)?.let { result.putString("signerSha256", it) }
        (map["capability"] as? String)?.let { result.putString("capability", it) }
        (map["deadlineMillis"] as? Long)?.let { result.putLong("deadlineMillis", it) }
        return result
    }

    // CRUD unused.
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
```

In `AndroidManifest.xml`, directly after the `FreezerBridgeProvider` provider block:

```xml
<provider
    android:name=".data.provider.CorePatchBridgeProvider"
    android:authorities="${applicationId}.corepatchbridge"
    android:exported="true" />
```

- [ ] **Step 4: Run test + compile**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.provider.CorePatchCallerGuardTest"`
Expected: PASS (3 tests).
Run: `./gradlew :app:compileFossDebugKotlin`
Expected: SUCCESS (Koin `inject` for `CorePatchArmStateHolder` resolves once Task 8 registers it — if compile is fine but runtime DI is missing, Task 8 covers it; provider is not instantiated in unit tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/provider/CorePatchBridgeProvider.kt \
        app/src/main/AndroidManifest.xml \
        app/src/test/java/com/valhalla/thor/data/provider/CorePatchCallerGuardTest.kt
git commit -m "feat(corepatch): system-uid-guarded arm-state bridge provider"
```

### Task 7: Audit log (TDD)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/corepatch/CorePatchAudit.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/corepatch/CorePatchAuditTest.kt`

**Interfaces:**
- Produces:
  - `data class CorePatchAuditEntry(val timestampMillis: Long, val pkg: String, val oldSigner: String?, val newSigner: String, val capability: String, val downgrade: Boolean, val result: String)`
  - `interface CorePatchAudit { fun append(entry: CorePatchAuditEntry); fun all(): List<CorePatchAuditEntry> }`
  - `class InMemoryCorePatchAudit(private val max: Int = 200) : CorePatchAudit` (ring-buffer cap; newest last)

- [ ] **Step 1: Write the failing test.**

```kotlin
// CorePatchAuditTest.kt
package com.valhalla.thor.data.corepatch

import org.junit.Assert.assertEquals
import org.junit.Test

class CorePatchAuditTest {
    private fun entry(i: Int) = CorePatchAuditEntry(i.toLong(), "com.foo", "OLD", "NEW", "sig", false, "SUCCESS")

    @Test fun `append then all returns in order`() {
        val a = InMemoryCorePatchAudit()
        a.append(entry(1)); a.append(entry(2))
        assertEquals(listOf(1L, 2L), a.all().map { it.timestampMillis })
    }

    @Test fun `caps at max keeping newest`() {
        val a = InMemoryCorePatchAudit(max = 2)
        a.append(entry(1)); a.append(entry(2)); a.append(entry(3))
        assertEquals(listOf(2L, 3L), a.all().map { it.timestampMillis })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.corepatch.CorePatchAuditTest"`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement.**

```kotlin
// CorePatchAudit.kt
package com.valhalla.thor.data.corepatch

data class CorePatchAuditEntry(
    val timestampMillis: Long,
    val pkg: String,
    val oldSigner: String?,
    val newSigner: String,
    val capability: String,
    val downgrade: Boolean,
    val result: String,
)

interface CorePatchAudit {
    fun append(entry: CorePatchAuditEntry)
    fun all(): List<CorePatchAuditEntry>
}

class InMemoryCorePatchAudit(private val max: Int = 200) : CorePatchAudit {
    private val entries = ArrayDeque<CorePatchAuditEntry>()

    @Synchronized override fun append(entry: CorePatchAuditEntry) {
        entries.addLast(entry)
        while (entries.size > max) entries.removeFirst()
    }

    @Synchronized override fun all(): List<CorePatchAuditEntry> = entries.toList()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.corepatch.CorePatchAuditTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/corepatch/CorePatchAudit.kt \
        app/src/test/java/com/valhalla/thor/data/corepatch/CorePatchAuditTest.kt
git commit -m "feat(corepatch): in-memory bypass audit log"
```

### Task 8: `corePatchEnabled` preference + Koin wiring

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/model/UserPreferences.kt` (add field)
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/PreferenceRepository.kt` (add setter signature)
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/PreferenceRepositoryImpl.kt` (key + **extract pure mapping** + setter — mirror `setBiometricLock`/`BIOMETRIC_LOCK`)
- Modify: `app/src/main/java/com/valhalla/thor/di/Modules.kt` (register `CorePatchArmStateHolder` + `CorePatchAudit` via **Koin Annotations**)
- Test: `app/src/test/java/com/valhalla/thor/data/repository/CorePatchPreferenceMappingTest.kt` (**pure JVM**, no Robolectric — tests the extracted `Preferences.toUserPreferences()` with `emptyPreferences()` / `mutablePreferencesOf(...)`, which are pure JVM classes)

**Interfaces:**
- Consumes: `CorePatchArmStateHolder` (Task 4), `CorePatchAudit`/`InMemoryCorePatchAudit` (Task 7).
- Produces:
  - `UserPreferences.corePatchEnabled: Boolean = false`
  - `suspend fun PreferenceRepository.setCorePatchEnabled(enabled: Boolean)`
  - `internal fun Preferences.toUserPreferences(): UserPreferences` (extracted from the existing inline Flow mapping so it is unit-testable on plain JVM)
  - `@Single`-annotated `CorePatchArmStateHolder` and `@Single(binds = [CorePatchAudit::class]) InMemoryCorePatchAudit`

- [ ] **Step 1: Add the field** to `UserPreferences` data class (default false):

```kotlin
val corePatchEnabled: Boolean = false,
```

- [ ] **Step 2: Extract the mapping + add key + setter.** In `PreferenceRepositoryImpl`, move the existing inline `prefs -> UserPreferences(...)` mapping in the `userPreferences` Flow into an internal extension (pure, no `context`/DataStore access — takes an already-read `Preferences`), and have the Flow call it. Add the new field to it:

```kotlin
// in object Keys:
val CORE_PATCH_ENABLED = booleanPreferencesKey("core_patch_enabled")

// extracted pure mapping (move ALL existing fields here verbatim, add the new one):
internal fun Preferences.toUserPreferences(): UserPreferences = UserPreferences(
    // ...all existing fields mapped exactly as before...
    corePatchEnabled = this[Keys.CORE_PATCH_ENABLED] ?: false,
)

// the Flow becomes: context.dataStore.data.map { it.toUserPreferences() }

// new setter:
override suspend fun setCorePatchEnabled(enabled: Boolean) {
    context.dataStore.edit { it[Keys.CORE_PATCH_ENABLED] = enabled }
}
```

Add `suspend fun setCorePatchEnabled(enabled: Boolean)` to the `PreferenceRepository` interface.

- [ ] **Step 3: Write the failing pure-mapping test FIRST** (TDD order — before Step 4 wiring compiles the DI):

```kotlin
// CorePatchPreferenceMappingTest.kt
package com.valhalla.thor.data.repository

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorePatchPreferenceMappingTest {
    @Test fun `defaults corePatchEnabled to false`() =
        assertFalse(emptyPreferences().toUserPreferences().corePatchEnabled)

    @Test fun `reads corePatchEnabled true`() {
        val prefs = mutablePreferencesOf(
            PreferenceRepositoryImpl.Keys.CORE_PATCH_ENABLED to true,
        )
        assertTrue(prefs.toUserPreferences().corePatchEnabled)
    }
}
```

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.repository.CorePatchPreferenceMappingTest"`
Expected: FAIL (unresolved `toUserPreferences`/`CORE_PATCH_ENABLED`) → PASS after Step 2. (If `Keys` is private, make the key `internal` or expose it for the test.)

- [ ] **Step 4: Register the singletons via Koin Annotations.** Follow the existing pattern in `di/Modules.kt` (the `@Module @ComponentScan` `AppModule`). Prefer annotating the classes so component-scan picks them up:

```kotlin
// on the class in CorePatchArmStateHolder.kt:
@Single
class CorePatchArmStateHolder(...)

// on the impl in CorePatchAudit.kt:
@Single(binds = [CorePatchAudit::class])
class InMemoryCorePatchAudit(...) : CorePatchAudit
```

If their package is outside `AppModule`'s component-scan root, instead add provider methods to `AppModule`:

```kotlin
@Single fun provideCorePatchArmStateHolder() = CorePatchArmStateHolder()
@Single fun provideCorePatchAudit(): CorePatchAudit = InMemoryCorePatchAudit()
```

Do NOT introduce `module { single { } }` DSL — the project does not use it.

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/UserPreferences.kt \
        app/src/main/java/com/valhalla/thor/domain/repository/PreferenceRepository.kt \
        app/src/main/java/com/valhalla/thor/data/repository/PreferenceRepositoryImpl.kt \
        app/src/main/java/com/valhalla/thor/data/corepatch/CorePatchArmStateHolder.kt \
        app/src/main/java/com/valhalla/thor/data/corepatch/CorePatchAudit.kt \
        app/src/main/java/com/valhalla/thor/di/Modules.kt \
        app/src/test/java/com/valhalla/thor/data/repository/CorePatchPreferenceMappingTest.kt
git commit -m "feat(corepatch): corePatchEnabled pref (pure mapping) + annotated DI"
```

### Task 9: Wrap the SYNCHRONOUS root install path (arm → blocking install → disarm + transient Play-Protect + audit)

> **Blocker fix:** arm/disarm attaches ONLY to the synchronous root `pm install-create/write/commit` shell path (blocks through PMS verification, so arm-state is held while the hook fires). The async `PackageInstaller` path never carries a `CorePatchAuthorization` (see Global Constraints → Root-synchronous scoping). CorePatch is root-only, so this is the only path it ever uses.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/InstallerRepository.kt` (add optional `corePatch` param to `installPackage`)
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/InstallerRepositoryImpl.kt` (dispatch in `installPackage`; route `corePatch` ONLY to the ROOT synchronous path — the `RootSystemGateway` session install)
- Modify: `app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt` (accept a pre-install/post-install hook, or expose the synchronous install so the bracket can wrap it)
- Create: `app/src/main/java/com/valhalla/thor/domain/model/CorePatchAuthorization.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/corepatch/CorePatchVerifierReconciler.kt` (Play-Protect self-heal) + wire into the Application/Koin startup
- Test: `app/src/test/java/com/valhalla/thor/data/repository/CorePatchArmBracketTest.kt` (drives the extracted bracket with fakes — this is the real test, not the no-op from before)

**Interfaces:**
- Consumes: `CorePatchArmStateHolder` (arm/disarm), `SystemGateway.isPackageVerifierEnabled`/`setPackageVerifierEnabled` (Task 1-2), `CorePatchAudit` (Task 7), `PreferenceRepository` (master gate + durable verifier marker, Task 8).
- Produces:
  - `data class CorePatchAuthorization(val pkg: String, val capability: String, val expectedNewSignerSha256: String, val disablePlayProtect: Boolean, val downgrade: Boolean)` — **`pkg` included** so the armed package is byte-identical to what the user confirmed (Task 11 passes `CorePatchConfirmState.pkg`); NEVER re-derived.
  - `suspend fun InstallerRepository.installPackage(uri: Uri, mode: InstallMode, canDowngrade: Boolean = false, corePatch: CorePatchAuthorization? = null)` — **`InstallMode`, not `PrivilegeMode`**; default null keeps all current callers unaffected.
  - `internal suspend fun <T> withCorePatchArmed(auth: CorePatchAuthorization, prior: () -> Boolean, block: suspend () -> T): T` (extracted, testable):
    1. If `disablePlayProtect`: write durable marker `setVerifierIntentionallyDisabled(true)`, then `gateway.setPackageVerifierEnabled(false)`. (Flip + marker BEFORE the try's arm so a throw here never leaves armed.)
    2. `try {` — `armState.arm(auth.pkg, auth.expectedNewSignerSha256, auth.capability, ttlMillis = 30_000)` as the FIRST statement inside try; run `block()` (the blocking root install).
    3. `finally {` — `armState.disarm()`; if flipped, `gateway.setPackageVerifierEnabled(priorValue)` (idempotent) + `setVerifierIntentionallyDisabled(false)`; append a `CorePatchAuditEntry`.
  - `CorePatchVerifierReconciler.reconcile()` — at app startup: if `verifierIntentionallyDisabled == true` (durable marker survived a crash/kill), force `setPackageVerifierEnabled(true)` + clear marker (nothing is in flight in a fresh process). Also new pref key + getter/setter `verifierIntentionallyDisabled` (Task 8-style, in `PreferenceRepository`).
  - Whole bracket gated behind `preferenceRepository.userPreferences.first().corePatchEnabled` — if false, ignore `corePatch` and install normally (fail-safe).

- [ ] **Step 1: Write the failing bracket test** (real coverage — fakes for holder/gateway/audit; asserts armed-inside, disarmed-after, disarmed-on-throw, verifier restored on BOTH paths, marker set-then-cleared, audit appended).

```kotlin
// CorePatchArmBracketTest.kt
package com.valhalla.thor.data.repository

import com.valhalla.thor.data.corepatch.CorePatchArmStateHolder
import com.valhalla.thor.data.corepatch.CorePatchAuditEntry
import com.valhalla.thor.data.corepatch.InMemoryCorePatchAudit
import com.valhalla.thor.domain.model.CorePatchAuthorization
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CorePatchArmBracketTest {
    private val holder = CorePatchArmStateHolder()
    private val audit = InMemoryCorePatchAudit()
    private val verifierWrites = mutableListOf<Boolean>()
    private var markerCleared = false

    // Extracted free function mirroring InstallerRepositoryImpl.withCorePatchArmed, injected with fakes.
    private suspend fun <T> bracket(auth: CorePatchAuthorization, block: suspend () -> Boolean): T? {
        if (auth.disablePlayProtect) verifierWrites.add(false) // marker+flip
        return try {
            holder.arm(auth.pkg, auth.expectedNewSignerSha256, auth.capability, 30_000)
            assertTrue(holder.current().armed) // armed INSIDE
            @Suppress("UNCHECKED_CAST")
            block() as T
        } finally {
            holder.disarm()
            if (auth.disablePlayProtect) { verifierWrites.add(true); markerCleared = true }
            audit.append(CorePatchAuditEntry(0, auth.pkg, "OLD", auth.expectedNewSignerSha256, auth.capability, auth.downgrade, "DONE"))
        }
    }

    private val auth = CorePatchAuthorization("com.foo", "sig", "AABB", disablePlayProtect = true, downgrade = false)

    @Test fun `disarms and restores after normal completion`() = runBlocking {
        bracket<Boolean>(auth) { true }
        assertFalse(holder.current().armed)
        assertEquals(listOf(false, true), verifierWrites) // off then on
        assertTrue(markerCleared)
        assertEquals(1, audit.all().size)
    }

    @Test fun `disarms and restores even when install throws`() = runBlocking {
        runCatching { bracket<Boolean>(auth) { throw RuntimeException("boom") } }
        assertFalse(holder.current().armed)
        assertEquals(listOf(false, true), verifierWrites)
        assertTrue(markerCleared)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.repository.CorePatchArmBracketTest"`
Expected: FAIL — unresolved `CorePatchAuthorization`.

- [ ] **Step 3: Implement** the `CorePatchAuthorization` model (with `pkg`), the durable `verifierIntentionallyDisabled` pref (getter/setter), `CorePatchVerifierReconciler` (+ call it from the Application/Koin startup), extend `installPackage` with the `corePatch: CorePatchAuthorization?` param, and add `withCorePatchArmed(...)` wrapping ONLY the `RootSystemGateway` synchronous install. Structure exactly as the Interfaces block: flip+marker before `try`, `arm()` first inside `try`, disarm+restore+clear-marker+audit in `finally`. Compute `oldSigner` via `ApkSignerSha.ofInstalled(...)` for the audit. Gate behind `corePatchEnabled`.

- [ ] **Step 4: Run test + compile**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.data.repository.CorePatchArmBracketTest"`
Expected: PASS (2 tests).
Run: `./gradlew :app:compileFossDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/InstallerRepository.kt \
        app/src/main/java/com/valhalla/thor/data/repository/InstallerRepositoryImpl.kt \
        app/src/main/java/com/valhalla/thor/data/gateway/RootSystemGateway.kt \
        app/src/main/java/com/valhalla/thor/domain/model/CorePatchAuthorization.kt \
        app/src/main/java/com/valhalla/thor/data/corepatch/CorePatchVerifierReconciler.kt \
        app/src/test/java/com/valhalla/thor/data/repository/CorePatchArmBracketTest.kt
git commit -m "feat(corepatch): root-synchronous arm bracket + play-protect self-heal"
```

---

## Phase C — Thor: UI (master opt-in, per-op confirm, audit + kill-switch)

### Task 10: Master opt-in — type-to-confirm gate

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchOptInDialog.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchAvailability.kt` (pure: `fun corePatchAvailable(mode: PrivilegeMode, lsposedActive: Boolean): Boolean = mode == PrivilegeMode.ROOT && lsposedActive`)
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt` (add a "Danger zone / CorePatch" section, disabled unless available) + `SettingsViewModel.kt`
- Test: `app/src/test/java/com/valhalla/thor/presentation/corepatch/CorePatchAvailabilityTest.kt` + `CorePatchConfirmMatchTest.kt`

**Interfaces:**
- Consumes: `PreferenceRepository.setCorePatchEnabled` (Task 8), `corePatchAvailable` (this task).
- Produces:
  - `fun corePatchAvailable(mode: PrivilegeMode, lsposedActive: Boolean): Boolean`
  - `fun confirmPhraseMatches(input: String): Boolean` (pure — required phrase, e.g. `input.trim() == "I understand the risk"`)
  - `@Composable fun CorePatchOptInDialog(onConfirm: () -> Unit, onDismiss: () -> Unit)` — a text field enabling the confirm button only when `confirmPhraseMatches`.

- [ ] **Step 1: Write failing tests** for the two pure functions.

```kotlin
// CorePatchAvailabilityTest.kt
package com.valhalla.thor.presentation.corepatch
import com.valhalla.thor.domain.model.PrivilegeMode
import org.junit.Assert.*
import org.junit.Test
class CorePatchAvailabilityTest {
    @Test fun `available only for root + lsposed`() {
        assertTrue(corePatchAvailable(PrivilegeMode.ROOT, true))
        assertFalse(corePatchAvailable(PrivilegeMode.ROOT, false))
        assertFalse(corePatchAvailable(PrivilegeMode.SHIZUKU, true))
        assertFalse(corePatchAvailable(PrivilegeMode.DHIZUKU, true))
        assertFalse(corePatchAvailable(PrivilegeMode.NONE, true))
    }
}
// CorePatchConfirmMatchTest.kt
package com.valhalla.thor.presentation.corepatch
import org.junit.Assert.*
import org.junit.Test
class CorePatchConfirmMatchTest {
    @Test fun `exact phrase matches, trimmed`() = assertTrue(confirmPhraseMatches("  I understand the risk "))
    @Test fun `wrong phrase rejected`() = assertFalse(confirmPhraseMatches("yes"))
    @Test fun `empty rejected`() = assertFalse(confirmPhraseMatches(""))
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.presentation.corepatch.*"`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement** `CorePatchAvailability.kt` (both pure functions) and `CorePatchOptInDialog.kt` (Compose, Asgard/Material3 to match the app; blunt copy per spec §6.1; confirm button enabled only when `confirmPhraseMatches(text)`; on confirm call the VM which calls `setCorePatchEnabled(true)`). Wire a section into `SettingsScreen`: when `!corePatchAvailable(...)` render it disabled with the explanation.

- [ ] **Step 4: Run tests + compile**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.presentation.corepatch.*"`
Expected: PASS (4 tests).
Run: `./gradlew :app:compileFossDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchOptInDialog.kt \
        app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchAvailability.kt \
        app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt \
        app/src/main/java/com/valhalla/thor/presentation/settings/SettingsViewModel.kt \
        app/src/test/java/com/valhalla/thor/presentation/corepatch/
git commit -m "feat(corepatch): default-off master opt-in with type-to-confirm"
```

### Task 11: Per-operation confirm dialog (side-by-side signer diff)

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchConfirmDialog.kt`
- Create: `app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchConfirmState.kt` (pure model builder)
- Modify: `app/src/main/java/com/valhalla/thor/presentation/installer/InstallerViewModel.kt` (before a hook-backed install, build the confirm state and show the dialog; on confirm, call `installPackage(..., corePatch = CorePatchAuthorization(...))`)
- Test: `app/src/test/java/com/valhalla/thor/presentation/corepatch/CorePatchConfirmStateTest.kt`

**Interfaces:**
- Consumes: `ApkSignerSha.ofApk`/`ofInstalled` (Task 5), `CorePatchAuthorization` (Task 9), `corePatchAvailable` (Task 10).
- Produces:
  - `data class CorePatchConfirmState(val pkg: String, val installedSignerSha256: String?, val newSignerSha256: String, val capability: String, val isDowngrade: Boolean)`
  - `fun buildCorePatchConfirmState(pkg, installed, new, capability, isDowngrade): CorePatchConfirmState`
  - `fun capabilityFor(installedSigner: String?, newSigner: String): String` — `"sig"` when `installedSigner != null && !installedSigner.equals(newSigner, ignoreCase = true)`, else `"digest"` (Kotlin `String` has no `equalsIgnoreCase`; use `equals(..., ignoreCase = true)` — the idiom Task 3 already uses).

- [ ] **Step 1: Write the failing test** for `capabilityFor`.

```kotlin
// CorePatchConfirmStateTest.kt
package com.valhalla.thor.presentation.corepatch
import org.junit.Assert.assertEquals
import org.junit.Test
class CorePatchConfirmStateTest {
    @Test fun `different signer => sig capability`() =
        assertEquals("sig", capabilityFor("AAAA", "BBBB"))
    @Test fun `same signer (case-insensitive) => digest capability`() =
        assertEquals("digest", capabilityFor("aaaa", "AAAA"))
    @Test fun `no installed signer (fresh install path) => digest`() =
        assertEquals("digest", capabilityFor(null, "BBBB"))
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.presentation.corepatch.CorePatchConfirmStateTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** the pure builders + the Compose dialog: package, capability label, **installed vs new SHA-256 side by side** (highlight when they differ), downgrade y/n, blunt warning, affirmative "Bypass and install" button (optionally behind `BiometricPromptHandler` when `biometricLockEnabled`). On confirm, the VM calls `installPackage(uri, mode /* InstallMode */, canDowngrade = isDowngrade, corePatch = CorePatchAuthorization(pkg = state.pkg, capability = capability, expectedNewSignerSha256 = newSignerSha256, disablePlayProtect = <user toggle>, downgrade = isDowngrade))` — note `pkg` is passed from `CorePatchConfirmState.pkg` so the armed package matches exactly what was shown.

- [ ] **Step 4: Run test + compile**

Run: `./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.presentation.corepatch.CorePatchConfirmStateTest"`
Expected: PASS (3 tests).
Run: `./gradlew :app:compileFossDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchConfirmDialog.kt \
        app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchConfirmState.kt \
        app/src/main/java/com/valhalla/thor/presentation/installer/InstallerViewModel.kt \
        app/src/test/java/com/valhalla/thor/presentation/corepatch/CorePatchConfirmStateTest.kt
git commit -m "feat(corepatch): per-op confirm dialog with signer diff"
```

### Task 12: Audit viewer + kill-switch

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchAuditScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/settings/SettingsScreen.kt` (link to audit; add "Disable all bypasses now" button)
- Modify: `SettingsViewModel.kt` (kill-switch → `setCorePatchEnabled(false)` + `armStateHolder.disarm()`; expose `audit.all()`)

- [ ] **Step 1: Implement** the audit list (read `CorePatchAudit.all()`, newest first: pkg, capability, old→new signer, downgrade, time, result), an **export action** (share/copy the entries as text via `Intent.ACTION_SEND`, satisfying spec §6.3 "viewable/exportable"), and a kill-switch button that sets `corePatchEnabled=false` and disarms.
- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileFossDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 3: Build the debug APK to confirm the whole Thor side assembles**

Run: `./gradlew assembleFossDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Bump version + commit**

Bump `versionCode` in `gradle.properties`. Then:

```bash
git add app/src/main/java/com/valhalla/thor/presentation/corepatch/CorePatchAuditScreen.kt \
        app/src/main/java/com/valhalla/thor/presentation/settings/ gradle.properties
git commit -m "feat(corepatch): audit viewer + one-tap kill-switch; bump versionCode"
```

---

## Phase D — Strombringer: forked CorePatch hooks (device-tested)

Repo: `path/to/Strombringer`. This is the bootloop-risk phase — the Global-Constraints fail-safe rules are acceptance criteria, not suggestions.

### Task 13: License — add GPLv2, verify CorePatch clause, attribution

**Files (Strombringer):**
- Create: `LICENSE` (full GPLv2 text)
- Create: `NOTICE` (attribution: weishu / LSPosed / yujincheng08, CorePatch upstream URL + commit/tag `v4.9`)
- Modify: `README.md` (state GPLv2 + attribution)

- [ ] **Step 1: Verify the CorePatch license clause.** Open CorePatch v4.9 source `LICENSE` and several `.java` headers. Confirm "GNU General Public License version 2" with **no "or (at your option) any later version"**. Record the finding in `NOTICE`. If any file is GPLv2-**or-later**, note it (it only widens options; GPLv2 combined work remains valid).
- [ ] **Step 2: Add** the verbatim GPLv2 text as `LICENSE`, write `NOTICE`, update `README.md`.
- [ ] **Step 3: Commit** (in the Strombringer repo).

```bash
cd path/to/Strombringer
git add LICENSE NOTICE README.md
git commit -m "chore(license): adopt GPLv2 ahead of CorePatch hook fork"
```

### Task 14: `Config` — system_server-safe arm-state fetch + cache

**Files (Strombringer):**
- Create: `app/src/main/java/com/valhalla/thor/ext/strombringer/corepatch/CorePatchConfig.kt`

**Interfaces:**
- Consumes: the Global-Constraints arm-state Bundle from Thor's `CorePatchBridgeProvider` (authority `com.valhalla.thor.corepatchbridge` and `com.valhalla.thor.debug.corepatchbridge` — mirror `Config.THOR_PACKAGES`).
- Produces:
  - `object CorePatchConfig` with:
    - `fun refreshBlockingBounded(): ArmStateSnapshot` — the ContentProvider IPC, run on a worker thread with a **hard timeout (e.g. 800ms)**; on timeout/exception/null returns a **DISARMED** snapshot. NEVER called under a PMS lock (only from the Task 16 coarse entry hook, off-lock).
    - `fun armThreadToken(token: ArmToken)` / `fun currentThreadToken(): ArmToken?` / `fun clearThreadToken()` — a `ThreadLocal<ArmToken?>`. The coarse hook sets it after a match; the fine hooks read it; the coarse hook clears it in `finally`.
    - `fun authorizes(snapshot: ArmStateSnapshot, nowMillis: Long, candidatePkg: String, candidateSignerSha256: String?, candidateCapability: String, installerUid: Int, thorUids: Set<Int>): Boolean` — the Global-Constraints predicate, using **`System.currentTimeMillis()`** (epoch), plus `installerUid in thorUids`. Signer is checked when available (coarse hook may pass null → sig hooks re-check later).
  - `data class ArmStateSnapshot(...)` mirroring the arm-state Bundle; `data class ArmToken(val pkg: String, val signerSha256: String, val capability: String, val deadlineMillis: Long)`.

- [ ] **Step 1: Implement** a Context acquisition that works in `system_server`:

```java
// NOT p.thisObject (that Context does not exist in system_server).
private static Context systemContext() {
    try {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object cur = at.getMethod("currentActivityThread").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(cur);
    } catch (Throwable t) { return null; }
}
```

`refreshBlockingBounded()` calls `ctx.getContentResolver().call(Uri.parse("content://" + thor + ".corepatchbridge"), "getArmState", null, null)` for each Thor package **on a worker thread with an 800ms timeout** (e.g. a single-thread executor + `Future.get(800, MS)`); parses the Bundle into `ArmStateSnapshot`; any timeout/null/throw ⇒ **DISARMED**. `authorizes(...)` uses **epoch `System.currentTimeMillis()`** (NEVER `SystemClock` — boot-relative vs epoch deadline makes expiry a no-op) and requires `installerUid` ∈ Thor's UIDs. **Fail-safe:** any exception ⇒ DISARMED ⇒ false.

- [ ] **Step 2: Compile Strombringer**

Run: `cd path/to/Strombringer && ./gradlew :app:compileDebugKotlin`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/ext/strombringer/corepatch/CorePatchConfig.kt
git commit -m "feat(corepatch): system_server-safe arm-state fetch with fail-safe cache"
```

### Task 15: Lift CorePatch hook classes (Java, verbatim) + strip + rewire

**Files (Strombringer, all new under `app/src/main/java/com/valhalla/thor/ext/strombringer/corepatch/`):**
- Create: `CorePatchForQ.java`, `CorePatchForR.java`, `CorePatchForS.java`, `CorePatchForT.java`, `CorePatchForU.java`, `CorePatchForV.java` (copied verbatim from CorePatch v4.9)
- Create: `XposedHelper.java`, `ReturnConstant.java` (copied, then rewired)

- [ ] **Step 1: Copy** the eight classes verbatim from CorePatch v4.9 into the `corepatch/` package. Add the GPLv2 header to each. Change only the `package` declaration to `com.valhalla.thor.ext.strombringer.corepatch`.
- [ ] **Step 2: STRIP** (delete the hook-install blocks — per Global Constraints "Kept hooks only"): every `checkDowngrade` hook; Q's `mVersionCode`/`mVersionCodeMajor` zeroing; `isVerificationEnabled` (`disableVerificationAgent`); all shared-UID / permission hooks (`hasCommonAncestor`, `ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS` FINAL-strip, `reconcilePackages` deopt, `doesSignatureMatchForPermissions`); OEM hooks (`NtConfigListServiceImpl`, Flyme); hidden-api-whitelist hooks. **Keep** the signature cluster (`checkCapability` except caps 4/16, `signaturesMatchExactly`, KeySet upgrade checks, `verifySignatures` deopt/false) and the digest cluster (`StrictJarVerifier`, `MessageDigest#isEqual`, `getMinimumSignatureSchemeVersionForTargetSdk`, `assertMinSignatureSchemeIsValid`, verity, `verifyV1Signature` fabrication — but DROP the hardcoded platform `SIGNATURE` fallback constant unless Task 18 shows a v1-fallback needs it).
- [ ] **Step 3: REWIRE off XSharedPreferences → thread-scoped token.** Delete every `XSharedPreferences` field/load and every `prefs.getBoolean("digestCreak"/"authcreak", …)` gate. Replace with: read `ArmToken t = CorePatchConfig.currentThreadToken()`; **if `t == null` → do nothing (verification proceeds).** Then:
  - **Sig-cluster hooks** (`checkCapability`, `signaturesMatchExactly`, KeySet checks, `verifySignatures`) — these DO receive a `SigningDetails`/cert, so additionally require `t.capability == "sig"` AND the signer they are inspecting matches `t.signerSha256` (case-insensitive). Only then bypass. (Preserve the caps 4/16 carve-out inside `checkCapability`.)
  - **Digest-cluster hooks** (`MessageDigest#isEqual`, `StrictJarVerifier`, verity, `getMinimumSignatureSchemeVersionForTargetSdk`, etc.) — these receive NO package/signer, so they bypass iff `t != null && t.capability == "digest"`. The token being thread-scoped (set only for the matched install by Task 16) is what confines them to that one install.
  - Preserve `deoptimizeMethod` calls exactly. The token is SET/CLEARED by the Task 16 coarse entry hook, NOT here.
- [ ] **Step 4: Wrap every kept hook body in try/catch that fails safe** (do nothing on throw; never rethrow into `system_server`), if the upstream code does not already.
- [ ] **Step 5: Compile**

Run: `cd path/to/Strombringer && ./gradlew :app:compileDebugJavaWithJavac`
Expected: SUCCESS (Java sources compile; Xposed API is `compileOnly`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/ext/strombringer/corepatch/
git commit -m "feat(corepatch): lift v4.9 sig/digest hooks (stripped, rewired off prefs)"
```

### Task 16: Coarse per-install entry hook (refresh + match + set thread token)

> **Blocker fix:** without this, `refresh()` runs only at boot (reads DISARMED) and the digest hooks would rely on a process-global flag that leaks to concurrent installs. This hook is the single per-install decision point.

**Files (Strombringer):**
- Create: `app/src/main/java/com/valhalla/thor/ext/strombringer/corepatch/CorePatchEntryHook.java`

**Interfaces:**
- Consumes: `CorePatchConfig.refreshBlockingBounded()`, `armThreadToken`/`clearThreadToken`, `authorizes(...)` (Task 14).
- Produces: `class CorePatchEntryHook { void install(ClassLoader cl); }` — hooks ONE PMS method that fires once per install BEFORE the tight verify path and exposes the target package + initiating installer UID.

- [ ] **Step 1: Pick + hook the coarse entry method.** Candidates (pin the correct one per SDK on-device in Task 19): `com.android.server.pm.PackageManagerService#installStage`, or the `VerifyingSession` / `InstallParams` constructor, or `InstallPackageHelper#installPackagesTraced`. Requirements: fires once per install, is OFF the ART/PMS verify lock, and its args expose the target package + `installerUid`. In `beforeHookedMethod`, wrapped in try/catch that fails safe:
  1. Extract `targetPkg` and `installerUid` from the method args/session.
  2. `snapshot = CorePatchConfig.refreshBlockingBounded()` (off-lock, 800ms bounded).
  3. If `authorizes(snapshot, System.currentTimeMillis(), targetPkg, /*signer*/ null, snapshot.capability, installerUid, thorUids)` → `armThreadToken(new ArmToken(snapshot.pkg, snapshot.signerSha256, snapshot.capability, snapshot.deadlineMillis))`.
- [ ] **Step 2: Clear the token in `afterHookedMethod`** (and on throw) via `clearThreadToken()`, so the token never outlives the install on that thread. If Task 19 finds the coarse entry and the fine verify run on DIFFERENT threads, switch the token store from `ThreadLocal` to a map keyed on the session/install object identity (documented fallback).
- [ ] **Step 3: Resolve `thorUids`** — the coarse hook must know Thor's UID(s) to satisfy the installer-UID guard; obtain via `PackageManager.getPackageUid("com.valhalla.thor"/".debug")` using the system context, cached, refreshed on failure.
- [ ] **Step 4: Compile**

Run: `cd path/to/Strombringer && ./gradlew :app:compileDebugJavaWithJavac`
Expected: SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/ext/strombringer/corepatch/CorePatchEntryHook.java
git commit -m "feat(corepatch): coarse per-install entry hook sets thread-scoped arm token"
```

### Task 17: `CorePatchHook` entry + SDK dispatch + guards

**Files (Strombringer):**
- Create: `app/src/main/java/com/valhalla/thor/ext/strombringer/corepatch/CorePatchHook.java`

**Interfaces:**
- Consumes: `CorePatchForQ..V` fine hooks (Task 15), `CorePatchEntryHook` (Task 16), `CorePatchConfig` (Task 14).
- Produces: `class CorePatchHook { CorePatchHook(ClassLoader cl); void start(); }` — folds CorePatch's `MainHook` SDK_INT dispatch (Q 28-29 / R 30 / S 31-32 / T 33 / U 34 / V 35-36, unknown-newer ⇒ V). `start()`: (1) installs the **coarse entry hook** (`new CorePatchEntryHook().install(cl)`); (2) installs the version-matched **fine sig/digest hooks**. It does **NOT** call `refresh()` — refresh happens per-install inside the coarse entry hook. Pin SDK; on unrecognized/failed resolution, install nothing (verification intact).

- [ ] **Step 1: Implement** the SDK dispatch + install(coarse) + install(fine) + fail-safe SDK pinning. Every dispatch branch AND each `install(...)` call wrapped in its own try/catch (a failure to install one hook must not abort the others or throw out of `start()`). Do NOT call `refresh()` here.
- [ ] **Step 2: Compile**

Run: `cd path/to/Strombringer && ./gradlew :app:compileDebugJavaWithJavac`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/ext/strombringer/corepatch/CorePatchHook.java
git commit -m "feat(corepatch): SDK-dispatched system_server hook entry with fail-safe pinning"
```

### Task 18: Wire the `"android"` branch + expand Xposed scope

**Files (Strombringer):**
- Modify: `app/src/main/java/com/valhalla/thor/ext/strombringer/XposedEntry.kt`
- Modify: `app/src/main/res/values/arrays.xml` (add `<item>android</item>` to `xposed_scope`)

- [ ] **Step 1: Add the system_server branch** in `XposedEntry.handleLoadPackage`, **wrapped in try/catch** so any load-time throw (SDK dispatch, `findClass`, `deoptimizeMethod`, `getSystemContext` reflection) degrades to "install nothing, verification intact" instead of bootlooping `system_server`:

```kotlin
override fun handleLoadPackage(lpp: LoadPackageParam) {
    if (lpp.packageName == "com.valhalla.thor.ext.strombringer") return
    if (lpp.packageName == "android") {          // system_server
        try {
            CorePatchHook(lpp.classLoader).start()
        } catch (t: Throwable) {
            // FAIL SAFE: never propagate out of system_server class-load.
            XposedBridge.log("Strombringer[corepatch] disabled: $t")
        }
        return
    }
    LaunchAppHook(lpp.classLoader).start()        // launcher packages
}
```

- [ ] **Step 2: Add scope item** to `res/values/arrays.xml`:

```xml
<item>android</item>
```

- [ ] **Step 3: Build the Strombringer APK**

Run: `cd path/to/Strombringer && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/ext/strombringer/XposedEntry.kt \
        app/src/main/res/values/arrays.xml
git commit -m "feat(corepatch): inject sig/digest hooks into system_server scope"
```

---

## Phase E — End-to-end device verification (root + LSPosed)

### Task 19: Device verification protocol + bootloop-safety gate

**Prereqs:** rooted device with LSPosed, Android 16 (and a 14/15 device if available). Three self-signed builds of a throwaway test app: same `applicationId`, **three different keystores** (`testapp-keyA.apk`, `testapp-keyB.apk`, `testapp-keyC.apk`).

- [ ] **Step 1: Install** the updated Thor foss-debug + updated Strombringer; enable the Strombringer module in LSPosed with scope including **System Framework (`android`)** and the launcher; **reboot**. **Bootloop gate #1:** device must boot normally with the hooks present but disarmed. If it bootloops, disable the module via recovery/root and fix Task 15/16 fail-safe wrapping before proceeding.
- [ ] **Step 2: Baseline (disarmed):** install `testapp-keyA.apk`. Then attempt to overwrite-install `testapp-keyB.apk` through Thor **without** enabling CorePatch. Expected: normal `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch) — proves hooks are inert while disarmed.
- [ ] **Step 3: Opt-in + confirm:** enable CorePatch (type-to-confirm), retry the keyB overwrite through Thor's per-op confirm (verify the dialog shows keyA vs keyB SHA-256 side by side). Expected: install SUCCEEDS, data preserved, audit entry written.
- [ ] **Step 4: Scope proof (mandatory-guard):** while nothing is armed, trigger a normal Play Store / `pm install` update of a *different* app. Expected: it still gets full verification (no bypass leaks outside the armed package). Then confirm arm-state is disarmed immediately after Step 3 completes (`adb shell content call --uri content://com.valhalla.thor.debug.corepatchbridge --method getArmState` from a system context, or add a temporary log).
- [ ] **Step 5: Signer-guard proof:** arm for keyB (start a keyB confirm) but attempt to commit a *keyC* build of the same package in the window. Expected: REJECTED (signer mismatch) — the new-signer-SHA-256 guard holds.
- [ ] **Step 6: Digest capability:** install a deliberately tampered (post-sign modified) same-key APK with `authcreak` capability confirmed. Expected: SUCCEEDS only when armed for `"digest"`.
- [ ] **Step 6b: Thread-scope proof (the blocker-2 acceptance gate):** add temporary logging in `CorePatchEntryHook` (coarse) and one digest hook to print `Thread.currentThread().getId()`. Confirm they log the SAME thread id for a given install (else switch the token store to session-identity per Task 16 Step 2). Then, while a `"digest"` install of `testapp` is armed/in-flight, trigger a normal `pm install` of a **different** app; confirm the different app still fails/gets full verification (the digest bypass did NOT leak to it). This is the concrete proof the thread token confines the digest hooks.
- [ ] **Step 7: Play-Protect transient + self-heal:** confirm `settings get global package_verifier_enable` returns to its prior value after an install with Play-Protect-disable toggled on. Then simulate a crash: set the durable marker + flip verifier off, force-stop Thor, relaunch; confirm the `CorePatchVerifierReconciler` restored `package_verifier_enable=1` and cleared the marker.
- [ ] **Step 8: Bootloop-safety fault injection (runtime AND load-time):** (a) temporarily force one kept hook to throw at RUNTIME; confirm the device still boots and install verification remains ON. (b) force `CorePatchHook.start()` to throw at LOAD time; confirm `system_server` still boots (the Task 18 try/catch holds) with hooks simply absent. Revert both.
- [ ] **Step 8b: Watchdog proof:** arm a bypass, then freeze/force-stop Thor while an unrelated install runs; confirm the coarse hook's bounded 800ms refresh times out to DISARMED and there is NO `system_server` watchdog reboot.
- [ ] **Step 9:** Record all results in a short `docs/superpowers/verification/2026-07-07-corepatch-device-verification.md` (Thor repo). Commit.

---

## Self-Review

**1. Spec coverage.**
- §2 routing table → Phase A (Play-Protect gateway) + existing downgrade + Phase D (sig/digest hooks). ✓
- §4 forked hook set (keep sig+digest, strip rest, lift Q..V verbatim, GPLv2) → Tasks 13, 15. ✓
- §5 arm/disarm safety (in-memory, pkg+signer match, lazy-expiry+finally-disarm, coarse per-install IPC, thread-scoped token, epoch clock, ActivityThread ctx, SYSTEM-uid guard, root-synchronous scoping) → Tasks 3, 4, 6, 9, 14, 16, 17. ✓
- §6 UX (master type-to-confirm, per-op signer diff, audit+export, kill-switch) → Tasks 10, 11, 12. ✓
- §7 transient Play-Protect + self-heal reconciler → Tasks 1-2, 9. ✓
- §8 legacy Xposed base + scope + entry branch → Tasks 17, 18. ✓ (existing `de.robv…api:82` dep — no task needed.)
- §9 license → Task 13. ✓
- §10 fail-safe/pinning → Tasks 14, 15, 16, 17, 18 + Global Constraints. ✓
- §11 test strategy (unit / provider guard / device incl. bootloop) → Tasks 3-9 unit, Task 6 guard, Task 19 device. ✓
- §12 file-by-file → covered across tasks. ✓
- §13 open items (coarse hook site, GPLv2 clause) → Task 16 (coarse hook site pinned on-device in Task 19), Task 13 (clause). ✓

**Adversarial review applied (`wf_00a2c535-1f4`, 5 lenses):** fixed 2 blockers (async-window → root-synchronous scoping; boot-time refresh + global-flag → coarse per-install entry hook + thread token) and all majors/minors (InstallMode vs PrivilegeMode, Koin Annotations vs DSL, no-Robolectric pure mapping, epoch vs SystemClock clock domain, XposedEntry try/catch, Play-Protect self-heal, bounded off-lock IPC, CorePatchAuthorization.pkg, real arm-bracket test, audit export).

**2. Placeholder scan.** No "TBD"/"handle edge cases"/"similar to Task N". The Strombringer lift tasks reference upstream CorePatch v4.9 as source material (correct — that is the copied artifact) with explicit keep/strip/rewire instructions rather than pasted GPLv2 source. No un-actionable steps.

**3. Type consistency.** The arm-state Bundle keys (`armed`/`pkg`/`signerSha256`/`capability`/`deadlineMillis`) are identical in Global Constraints, `toBundleMap` (Task 4), `CorePatchBridgeProvider.call` (Task 6), and `CorePatchConfig` (Task 14). `authorizes(...)` predicate signature and `capability` values (`"sig"`/`"digest"`) match across Tasks 3, 9, 11, 14, 15. `CorePatchAuthorization` fields match between Tasks 9 and 11. `corePatchAvailable`/`confirmPhraseMatches` names match between Tasks 10 and its tests.

**Open risks carried into execution:** the exact coarse PMS entry point for `CorePatchConfig.refresh()` (Task 16) is pinned on-device in Task 18; the platform `SIGNATURE` fallback drop (Task 15) is revisited only if Task 18 surfaces a v1-fallback failure.
