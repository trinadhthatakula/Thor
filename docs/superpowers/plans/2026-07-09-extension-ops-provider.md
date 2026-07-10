# Extension Ops ContentProvider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken broadcast→`onTrigger` extension trigger with a caller-verified Thor `ContentProvider` that performs privileged package ops, so the automation "freeze cluster" works.

**Architecture:** Thor exposes `ExtensionOpsProvider` (`content://…extensionops`, methods `freeze`/`unfreeze`/`toggle` over a `String[] packages`). It verifies the caller is a pinned-signer extension, runs the op under `Binder.clearCallingIdentity()` via Thor's own `ManageAppUseCase` (mode-aware), and returns `{ok,count}`. The automation extension drops `AutomationExtension`/`onTrigger` and calls the provider from its `ConfigActivity` + `AlarmReceiver`. The old `ExtensionTriggerReceiver` + `TRIGGER_EXTENSION` permission are deleted. Mirrors the existing `FreezerBridgeProvider`.

**Tech Stack:** Kotlin, Android `ContentProvider`, Koin (`KoinComponent`/`by inject()`), kotlinx.coroutines (`runBlocking`, `Flow.first`), JUnit4.

## Global Constraints

- Repos: `Thor` (branch `feat/extension-ops-provider`, off `dev`) and `Thor-Extensions` (branch off `main`).
- **No `thor-extension-api` change, no Maven Central release** (only 2 Central slots left this month).
- Thor version: bump `versionCode` in `Thor/gradle.properties` only (never edit `versionName`). Current `1912`.
- Automation extension version: bump `versionCode` **and** `versionName` in `verified/thor-automation-extension/app/build.gradle.kts` (CI keys releases on the versionName tag). Current `1003` / `1.00.3`.
- Extension package prefix: `com.valhalla.thor.ext.`. Thor packages: `com.valhalla.thor`, `com.valhalla.thor.debug`.
- Ops contract (verbatim strings, shared, hardcoded both sides): authority suffix `.extensionops`; methods `freeze` / `unfreeze` / `toggle`; extras key `packages` (`String[]`); result keys `ok` (`Boolean`) / `count` (`Int`).
- Build check (Thor): `./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug :app:compileStoreReleaseKotlin`.
- Build check (extension): `./gradlew :app:assembleRelease` in the extension dir (the EXT signing key is configured locally, so this produces a pin-signed APK).

---

### Task 1: `ExtensionOpsGate` — pure authorization + target filtering

**Files:**
- Create: `Thor/app/src/main/java/com/valhalla/thor/domain/model/ExtensionOpsGate.kt`
- Test: `Thor/app/src/test/java/com/valhalla/thor/domain/model/ExtensionOpsGateTest.kt`

**Interfaces:**
- Produces (used by Task 2):
  - `fun isAuthorizedExtensionCaller(caller: String?, ownPackage: String, isPinnedSigner: Boolean, isDebug: Boolean): Boolean`
  - `fun opTargets(requested: List<String>, guarded: Set<String>): List<String>`
  - `const val EXTENSION_OPS_PREFIX = "com.valhalla.thor.ext."`

- [ ] **Step 1: Write the failing test**

Create `Thor/app/src/test/java/com/valhalla/thor/domain/model/ExtensionOpsGateTest.kt`:

```kotlin
package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionOpsGateTest {

    // --- isAuthorizedExtensionCaller ---
    @Test fun `same-process (null caller) is allowed`() {
        assertTrue(isAuthorizedExtensionCaller(null, "com.valhalla.thor", isPinnedSigner = false, isDebug = false))
    }
    @Test fun `own package is allowed`() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor", "com.valhalla.thor", isPinnedSigner = false, isDebug = false))
    }
    @Test fun `pinned-signer extension is allowed`() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = true, isDebug = false))
    }
    @Test fun `ext-prefixed but not pinned is refused in release`() {
        assertFalse(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = false, isDebug = false))
    }
    @Test fun `ext-prefixed unpinned is allowed in debug`() {
        assertTrue(isAuthorizedExtensionCaller("com.valhalla.thor.ext.automation", "com.valhalla.thor", isPinnedSigner = false, isDebug = true))
    }
    @Test fun `arbitrary app is refused even in debug`() {
        assertFalse(isAuthorizedExtensionCaller("com.evil.app", "com.valhalla.thor", isPinnedSigner = false, isDebug = true))
    }

    // --- opTargets ---
    @Test fun `filters guarded and blank, dedups, preserves order`() {
        val out = opTargets(
            requested = listOf("com.a", "", "com.valhalla.thor", "com.b", "com.a"),
            guarded = setOf("com.valhalla.thor")
        )
        assertEquals(listOf("com.a", "com.b"), out)
    }
    @Test fun `empty when all guarded`() {
        assertEquals(emptyList<String>(), opTargets(listOf("com.valhalla.thor"), setOf("com.valhalla.thor")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd Thor && ./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.ExtensionOpsGateTest"`
Expected: FAIL — unresolved reference `isAuthorizedExtensionCaller` / `opTargets`.

- [ ] **Step 3: Write minimal implementation**

Create `Thor/app/src/main/java/com/valhalla/thor/domain/model/ExtensionOpsGate.kt`:

```kotlin
package com.valhalla.thor.domain.model

/** Package-name prefix every Thor extension shares. */
const val EXTENSION_OPS_PREFIX = "com.valhalla.thor.ext."

/**
 * True iff [caller] may invoke the extension-ops provider. A null caller (same-process) or Thor's own
 * package ([ownPackage]) is always allowed. A cross-process caller must be an extension
 * ([EXTENSION_OPS_PREFIX]) that is either a pinned signer ([isPinnedSigner]) or — in [isDebug] builds —
 * any ext-prefixed package (so self-built extensions work locally). Everything else is refused.
 */
fun isAuthorizedExtensionCaller(
    caller: String?,
    ownPackage: String,
    isPinnedSigner: Boolean,
    isDebug: Boolean,
): Boolean {
    if (caller == null || caller == ownPackage) return true
    if (!caller.startsWith(EXTENSION_OPS_PREFIX)) return false
    return isPinnedSigner || isDebug
}

/**
 * The packages an op should actually touch: [requested] minus [guarded] and blanks, de-duplicated,
 * original order preserved.
 */
fun opTargets(requested: List<String>, guarded: Set<String>): List<String> =
    requested.filter { it.isNotBlank() && it !in guarded }.distinct()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd Thor && ./gradlew :app:testFossDebugUnitTest --tests "com.valhalla.thor.domain.model.ExtensionOpsGateTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
cd Thor
git add app/src/main/java/com/valhalla/thor/domain/model/ExtensionOpsGate.kt \
        app/src/test/java/com/valhalla/thor/domain/model/ExtensionOpsGateTest.kt
git commit -m "feat(extensions): pure gate for the extension-ops provider (auth + target filter)"
```

---

### Task 2: `ExtensionOpsProvider` + manifest registration

**Files:**
- Create: `Thor/app/src/main/java/com/valhalla/thor/data/provider/ExtensionOpsProvider.kt`
- Modify: `Thor/app/src/main/AndroidManifest.xml` (add `<provider>` next to `FreezerBridgeProvider`, ~line 341)

**Interfaces:**
- Consumes (Task 1): `isAuthorizedExtensionCaller`, `opTargets`.
- Consumes (existing): `ManageAppUseCase.setAppDisabled/setAppSuspended/forceUnfreeze` (all `suspend … : Result<Unit>`), `PreferenceRepository.userPreferences: Flow<UserPreferences>` with `UserPreferences.freezerMode: FreezerMode` (`FREEZE`/`SUSPEND`), `ExtensionManager.isSignatureVerified(pkg): Boolean`.
- Produces (used by Task 4/5): provider authority `${applicationId}.extensionops`, methods `freeze`/`unfreeze`/`toggle`, extras `String[] "packages"`, result `Boolean "ok"` + `Int "count"`.

- [ ] **Step 1: Write the provider**

Create `Thor/app/src/main/java/com/valhalla/thor/data/provider/ExtensionOpsProvider.kt`:

```kotlin
package com.valhalla.thor.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.valhalla.thor.BuildConfig
import com.valhalla.thor.data.manager.ExtensionManager
import com.valhalla.thor.domain.model.FreezerMode
import com.valhalla.thor.domain.model.isAuthorizedExtensionCaller
import com.valhalla.thor.domain.model.opTargets
import com.valhalla.thor.domain.repository.PreferenceRepository
import com.valhalla.thor.domain.usecase.ManageAppUseCase
import com.valhalla.thor.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Privileged package operations for trusted extensions. An extension (its own process) hands Thor a
 * list of packages and an op; Thor — which holds root/Shizuku/Dhizuku — performs it. This REPLACES the
 * old broadcast→onTrigger path: no extension code runs in Thor's process (so the minified-kotlin
 * classloader coupling that broke `onTrigger` can't happen), and a provider access reliably cold-starts
 * Thor (so it works even when Thor was killed — unlike the broadcast on MIUI).
 *
 * Security (mirrors [FreezerBridgeProvider]): the caller is UID-attested via getCallingPackage() and must
 * be a pinned-signer extension; the privileged work runs under Thor's OWN identity
 * (Binder.clearCallingIdentity()); Thor's own package and the caller are never operated on.
 */
class ExtensionOpsProvider : ContentProvider(), KoinComponent {
    private val manageAppUseCase: ManageAppUseCase by inject()
    private val preferenceRepository: PreferenceRepository by inject()
    private val extensionManager: ExtensionManager by inject()

    private enum class Op { FREEZE, UNFREEZE, TOGGLE }

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle().apply { putBoolean("ok", false); putInt("count", 0) }
        val ctx = context ?: return result
        val op = when (method) {
            "freeze" -> Op.FREEZE
            "unfreeze" -> Op.UNFREEZE
            "toggle" -> Op.TOGGLE
            else -> return result
        }

        val caller = callingPackage
        val pinned = caller != null && runCatching { extensionManager.isSignatureVerified(caller) }.getOrDefault(false)
        if (!isAuthorizedExtensionCaller(caller, ctx.packageName, isPinnedSigner = pinned, isDebug = BuildConfig.DEBUG)) {
            Logger.d("ExtensionOps", "op '$method' refused (unauthorized caller): $caller / uid ${Binder.getCallingUid()}")
            return result
        }

        val guarded = setOfNotNull(ctx.packageName, "com.valhalla.thor", "com.valhalla.thor.debug", caller)
        val targets = opTargets(extras?.getStringArray("packages")?.toList().orEmpty(), guarded)
        if (targets.isEmpty()) { result.putBoolean("ok", true); return result }

        val token = Binder.clearCallingIdentity()
        val count = try {
            runCatching {
                runBlocking {
                    val suspendMode = preferenceRepository.userPreferences.first().freezerMode == FreezerMode.SUSPEND
                    val effective = if (op == Op.TOGGLE) {
                        if (anyFrozen(ctx.packageManager, targets)) Op.UNFREEZE else Op.FREEZE
                    } else op
                    targets.count { pkg ->
                        when (effective) {
                            Op.FREEZE ->
                                if (suspendMode) manageAppUseCase.setAppSuspended(pkg, true)
                                else manageAppUseCase.setAppDisabled(pkg, true)
                            Op.UNFREEZE -> manageAppUseCase.forceUnfreeze(pkg)
                            Op.TOGGLE -> Result.failure(IllegalStateException()) // resolved above
                        }.isSuccess
                    }
                }
            }.getOrElse { Logger.e("ExtensionOps", "op '$method' failed", it); 0 }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        result.putBoolean("ok", count == targets.size)
        result.putInt("count", count)
        return result
    }

    /** True if any of [pkgs] is currently frozen (disabled OR suspended). MATCH_DISABLED so we can read a disabled app. */
    private fun anyFrozen(pm: PackageManager, pkgs: List<String>): Boolean = pkgs.any { pkg ->
        runCatching {
            val info = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
            !info.enabled || (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
        }.getOrDefault(false)
    }

    // Unused CRUD surface.
    override fun query(u: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
```

- [ ] **Step 2: Register the provider in the manifest**

In `Thor/app/src/main/AndroidManifest.xml`, immediately after the existing `FreezerBridgeProvider` `<provider .../>` (the one with `android:authorities="${applicationId}.freezerbridge"`), add:

```xml
        <provider
            android:name=".data.provider.ExtensionOpsProvider"
            android:authorities="${applicationId}.extensionops"
            android:exported="true" />
```

- [ ] **Step 3: Compile (both flavors)**

Run: `cd Thor && ./gradlew :app:compileFossDebugKotlin :app:compileStoreReleaseKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd Thor
git add app/src/main/java/com/valhalla/thor/data/provider/ExtensionOpsProvider.kt app/src/main/AndroidManifest.xml
git commit -m "feat(extensions): ExtensionOpsProvider — caller-verified privileged package ops"
```

---

### Task 3: Retire the broadcast trigger path (Thor)

**Files:**
- Delete: `Thor/app/src/main/java/com/valhalla/thor/data/receivers/ExtensionTriggerReceiver.kt`
- Modify: `Thor/app/src/main/AndroidManifest.xml` (remove the `<receiver ExtensionTriggerReceiver>` block ~lines 325-332 and the `<permission …TRIGGER_EXTENSION>` block ~lines 37-39)
- Modify: `Thor/gradle.properties` (`versionCode` 1912 → 1913)

**Interfaces:** none produced. (`RoomExtensionDataStore` becomes unused but is LEFT IN PLACE — removing it would need a Room table drop/migration; out of scope. `ThorShellExecutor` stays — `RootSystemGateway` also uses it.)

- [ ] **Step 1: Delete the receiver class**

```bash
cd Thor
git rm app/src/main/java/com/valhalla/thor/data/receivers/ExtensionTriggerReceiver.kt
```

- [ ] **Step 2: Remove the manifest receiver block**

In `Thor/app/src/main/AndroidManifest.xml`, delete the entire `<receiver>` element for `ExtensionTriggerReceiver`:

```xml
        <receiver
            android:name=".data.receivers.ExtensionTriggerReceiver"
            android:permission="com.valhalla.thor.permission.TRIGGER_EXTENSION"
            android:exported="true">
            <intent-filter>
                <action android:name="com.valhalla.thor.action.TRIGGER_EXTENSION" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 3: Remove the permission declaration**

In `Thor/app/src/main/AndroidManifest.xml`, delete the `<permission>` block (and the stray "confused deputy" comment above the receiver, if present):

```xml
    <permission
        android:name="com.valhalla.thor.permission.TRIGGER_EXTENSION"
        android:protectionLevel="signature" />
```

- [ ] **Step 4: Bump versionCode**

In `Thor/gradle.properties` change `versionCode=1912` to `versionCode=1913`.

- [ ] **Step 5: Verify no dangling references + build**

Run:
```bash
cd Thor
grep -rn "ExtensionTriggerReceiver\|TRIGGER_EXTENSION" app/src && echo "STILL REFERENCED — fix before continuing" || echo "clean"
./gradlew :app:testFossDebugUnitTest :app:assembleFossDebug :app:compileStoreReleaseKotlin
```
Expected: `clean`, then BUILD SUCCESSFUL (all unit tests pass, both flavors compile).

- [ ] **Step 6: Commit**

```bash
cd Thor
git add app/src/main/AndroidManifest.xml gradle.properties
git commit -m "refactor(extensions): retire broadcast trigger + TRIGGER_EXTENSION permission (superseded by ops provider); bump 1913"
```

---

### Task 4: Automation extension — `ThorOps` client + drop `AutomationExtension`

**Files (in `Thor-Extensions/verified/thor-automation-extension/app/src/main/java/com/valhalla/thor/ext/automation/`):**
- Create: `ThorOps.kt`
- Modify: `AutomationCluster.kt` (drop `AutomationExtension`/`onTrigger`/`readSavedClusters` → plain `ThorExtension`)

**Interfaces:**
- Consumes: Thor's ops contract (Global Constraints).
- Produces (used by Task 5): `object ThorOps { fun run(context: Context, action: String, packages: List<String>): Boolean }`.

- [ ] **Step 1: Create the ops client**

Create `.../automation/ThorOps.kt`:

```kotlin
package com.valhalla.thor.ext.automation

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Calls Thor's ExtensionOpsProvider to perform a privileged package op. Runs a synchronous IPC — call
 * OFF the main thread. Tries each known Thor package (release, then debug) and uses the first that
 * resolves. Returns true when Thor reports the op succeeded.
 */
object ThorOps {
    // Must match Thor's ExtensionOpsProvider contract.
    private const val AUTHORITY_SUFFIX = ".extensionops"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_OK = "ok"

    fun run(context: Context, action: String, packages: List<String>): Boolean {
        if (packages.isEmpty()) return false
        val extras = Bundle().apply { putStringArray(KEY_PACKAGES, packages.toTypedArray()) }
        for (thorPkg in Config.THOR_PACKAGES) {
            val uri = Uri.parse("content://$thorPkg$AUTHORITY_SUFFIX")
            val ok = runCatching {
                context.contentResolver.call(uri, action, null, extras)?.getBoolean(KEY_OK)
            }.getOrNull()
            if (ok != null) return ok // provider resolved (this is the live Thor); use its verdict
        }
        return false // no Thor ops provider resolved
    }
}
```

- [ ] **Step 2: Reduce `AutomationCluster` to a plain `ThorExtension`**

Replace the entire contents of `.../automation/AutomationCluster.kt` with:

```kotlin
@file:Suppress("unused")

package com.valhalla.thor.ext.automation

import com.valhalla.thor.extension.api.ThorExtension
import kotlinx.serialization.Serializable

/**
 * A named group of app packages frozen/unfrozen together. Persisted as JSON by the config UI and read
 * back by the config UI + AlarmReceiver (both in THIS process); the actual freeze runs in Thor via the
 * ExtensionOpsProvider (see [ThorOps]).
 */
@Serializable
data class AppCluster(
    val name: String,
    val packages: List<String>,
    val isScheduled: Boolean = false
)

/**
 * Metadata surface Thor loads (by `thor.extension.class`) to list this extension + offer Configure.
 * It NO LONGER implements AutomationExtension/onTrigger: Thor never runs extension code now — the
 * extension calls Thor's ExtensionOpsProvider instead (see the 2026-07-09 ops-provider design). Kept as
 * a plain ThorExtension (metadata only), like StrombringerExtension.
 */
@Suppress("unused")
class AutomationCluster : ThorExtension {
    override val name: String = "Thor Cluster Automator"
    override val description: String = "Automate freezing and unfreezing of custom app clusters."
    override val version: String = "1.0.0"
    override val author: String = "Thor Team"
}
```

- [ ] **Step 3: Compile**

Run: `cd Thor-Extensions/verified/thor-automation-extension && ./gradlew :app:compileReleaseKotlin`
Expected: BUILD SUCCESSFUL (note: `ConfigActivity`/`AlarmReceiver` still reference the old broadcast helper — they're fixed in Task 5; if this step fails only due to those, proceed to Task 5 and compile there. Prefer doing Task 4 Step 3 as a compile of just this file's dependents — if the module won't compile until Task 5, skip to Task 5 and treat Tasks 4+5 as one compile/commit boundary.)

- [ ] **Step 4: Commit (with Task 5 if the module needs both to compile)**

```bash
cd Thor-Extensions
git add verified/thor-automation-extension/app/src/main/java/com/valhalla/thor/ext/automation/ThorOps.kt \
        verified/thor-automation-extension/app/src/main/java/com/valhalla/thor/ext/automation/AutomationCluster.kt
git commit -m "feat(automation): add ThorOps client; AutomationCluster -> plain ThorExtension (no onTrigger)"
```

---

### Task 5: Automation extension — call the ops provider from `ConfigActivity` + `AlarmReceiver`

**Files (same dir as Task 4):**
- Modify: `ConfigActivity.kt` (replace the `triggerThor(...)` calls + remove the `triggerThor` function)
- Modify: `AlarmReceiver.kt` (read cluster packages from prefs, call `ThorOps` instead of broadcasting)
- Modify: `app/src/main/AndroidManifest.xml` (remove `<uses-permission …TRIGGER_EXTENSION>`)
- Modify: `app/build.gradle.kts` (`versionCode` 1003 → 1004, `versionName` "1.00.3" → "1.00.4")

**Interfaces:** Consumes `ThorOps.run(context, action, packages)` (Task 4) and `AppCluster`/`Config` (existing).

- [ ] **Step 1: Rewrite `ConfigActivity` trigger calls**

In `.../automation/ConfigActivity.kt` there are four `triggerThor(...)` call sites (currently ~lines 252, 255, 515, 526): the cluster-list `onFreezeCluster`/`onUnfreezeCluster` handlers (which receive `(name, packages)`) and the detail-screen Freeze/Unfreeze buttons (which have `cluster`/`clusterName` in scope). Each has the cluster's package list available. Replace each with a background call to `ThorOps`. The cluster-list handlers:

```kotlin
onFreezeCluster = { _, packages ->
    scope.launch(Dispatchers.IO) { ThorOps.run(context, "freeze", packages) }
},
onUnfreezeCluster = { _, packages ->
    scope.launch(Dispatchers.IO) { ThorOps.run(context, "unfreeze", packages) }
},
```

And the detail-screen buttons that had `triggerThor(context, "freeze:$clusterName")` — those run inside the `ClusterDetail` composable which has `cluster`/`clusterName` in scope; use the cluster's packages:

```kotlin
// Freeze button onClick:
scope.launch(Dispatchers.IO) { ThorOps.run(context, "freeze", cluster.packages) }
// Unfreeze button onClick:
scope.launch(Dispatchers.IO) { ThorOps.run(context, "unfreeze", cluster.packages) }
```

Use the existing `scope`/`Dispatchers.IO` in the file (the config writes already use `scope.launch(Dispatchers.IO)`); if a given composable lacks a `scope`, add `val scope = rememberCoroutineScope()`. Then **delete** the now-unused `private fun triggerThor(context: Context, triggerId: String)` function.

- [ ] **Step 2: Rewrite `AlarmReceiver`**

Replace the contents of `.../automation/AlarmReceiver.kt` with a version that reads the cluster's packages from the extension's own prefs and calls the ops provider directly (no broadcast):

```kotlin
package com.valhalla.thor.ext.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.Json

/**
 * Fires on the scheduled alarm. Resolves the cluster's packages from this extension's own prefs and
 * asks Thor's ExtensionOpsProvider to perform the op (which cold-starts Thor if needed). No broadcast.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: "toggle"   // freeze | unfreeze | toggle
        val clusterName = intent.getStringExtra("cluster_name") ?: return

        val json = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
            .getString(Config.KEY_SAVED_CLUSTERS, null) ?: return
        val clusters = runCatching { Json.decodeFromString<List<AppCluster>>(json) }.getOrDefault(emptyList())
        val packages = clusters.firstOrNull { it.name == clusterName }?.packages ?: return
        if (packages.isEmpty()) return

        // BroadcastReceiver.onReceive is on the main thread; the ops call is a synchronous IPC, so hop
        // off it. goAsync keeps the receiver alive while the (fast, local) provider call runs.
        val pending = goAsync()
        Thread {
            try { ThorOps.run(context, action, packages) } finally { pending.finish() }
        }.start()
    }
}
```

- [ ] **Step 3: Remove the extension's TRIGGER_EXTENSION permission**

In `.../thor-automation-extension/app/src/main/AndroidManifest.xml`, delete:

```xml
    <uses-permission android:name="com.valhalla.thor.permission.TRIGGER_EXTENSION" />
```

- [ ] **Step 4: Bump the version**

In `.../thor-automation-extension/app/build.gradle.kts`: `versionCode = 1003` → `versionCode = 1004`, and `versionName = "1.00.3"` → `versionName = "1.00.4"`.

- [ ] **Step 5: Verify no dangling references + build a pin-signed APK**

Run:
```bash
cd Thor-Extensions/verified/thor-automation-extension
grep -rn "triggerThor\|TRIGGER_EXTENSION\|onTrigger\|AutomationExtension" app/src && echo "STILL REFERENCED — fix" || echo "clean"
./gradlew :app:assembleRelease
```
Expected: `clean`, then BUILD SUCCESSFUL. Confirm the APK is pin-signed:
```bash
APKSIGNER="$(find "$HOME/Library/Android/sdk/build-tools" -name apksigner | sort -V | tail -1)"
"$APKSIGNER" verify --print-certs app/build/outputs/apk/release/app-release.apk | grep -i "SHA-256" | head -1
```
Expected: `…762dc455…` (the pinned cert).

- [ ] **Step 6: Commit**

```bash
cd Thor-Extensions
git add verified/thor-automation-extension/
git commit -m "feat(automation): call Thor ExtensionOpsProvider from ConfigActivity + AlarmReceiver; drop broadcast; 1.00.4"
```

---

### Task 6: Document the ops contract for extension authors

**Files:**
- Modify: `Thor-Extensions/CONTRIBUTING.md`

- [ ] **Step 1: Add a "Requesting privileged operations" section**

Append to `Thor-Extensions/CONTRIBUTING.md` (before the Checklist):

```markdown
## Requesting privileged operations from Thor

Extensions don't run code inside Thor. To perform a privileged package action, call Thor's
`ExtensionOpsProvider` (Thor cold-starts if needed):

- **URI:** `content://<thorPackage>.extensionops` — try `com.valhalla.thor`, then `com.valhalla.thor.debug`.
- **method:** `"freeze"` | `"unfreeze"` | `"toggle"`
- **extras:** `Bundle { putStringArray("packages", …) }`
- **returns:** `Bundle { "ok": Boolean, "count": Int }`

Thor verifies the caller is a pinned-signer extension (debug builds relax this), runs the op mode-aware
(honouring the user's Freeze/Suspend setting), and never operates on Thor's own or your package. Call it
OFF the main thread (`ContentResolver.call` is a synchronous IPC). Your app needs no special permission —
just package visibility (declare `<queries><package android:name="com.valhalla.thor"/></queries>` or hold
`QUERY_ALL_PACKAGES`). See `verified/thor-automation-extension` (`ThorOps.kt`) for a reference client.
```

- [ ] **Step 2: Commit**

```bash
cd Thor-Extensions
git add CONTRIBUTING.md
git commit -m "docs: document the ExtensionOpsProvider contract for extension authors"
```

---

### Task 7: Device end-to-end verification

**Preconditions:** device `1da5425f` connected. Install the new Thor release (`cd Thor && ./gradlew :app:assembleFossRelease && adb install -r app/build/outputs/apk/foss/release/app-foss-release.apk`) and the new automation APK (`adb install -r Thor-Extensions/verified/thor-automation-extension/app/build/outputs/apk/release/app-release.apk`).

- [ ] **Step 1: Cleanup prior test artifact** — unfreeze AClock if still frozen: `adb shell pm enable cn.imerge.alock`.

- [ ] **Step 2: Freeze** — open the automation config (`adb shell am start -n com.valhalla.thor.ext.automation/.ConfigActivity`), create/open a cluster with a benign app, tap **Freeze**. Verify: `adb shell dumpsys package <pkg> | grep -m1 "enabled="` shows `enabled=2/3` (disabled) OR `suspended=true`, matching the active Freeze/Suspend mode. Verify no crash: `adb logcat -d | grep -iE "AbstractMethod|NoSuchMethod|ExtensionOps"` shows no error (the ExtensionOps op ran).

- [ ] **Step 3: Unfreeze** — tap **Unfreeze**; verify the app returns to `enabled=0` and `suspended=false`.

- [ ] **Step 4: Toggle** — tap the toggle path; verify it flips state.

- [ ] **Step 5: Dead-process (the key fix)** — `adb shell am force-stop com.valhalla.thor`, then from the still-open extension config tap **Freeze**. Verify the app freezes anyway (the provider cold-started Thor). Confirm `adb shell pidof com.valhalla.thor` is now non-empty.

- [ ] **Step 6: Guardrail** — via a cluster containing `com.valhalla.thor`, confirm Thor itself is never disabled (`adb shell pm list packages -d | grep -c com.valhalla.thor` stays 0 for the Thor package).

- [ ] **Step 7: Report** results; if all pass, the plan is complete and ready for whole-branch review + (a) Thor PR to `dev`, (b) Thor-Extensions push (CI re-releases automation 1.00.4 — GitHub, not Central).
