# Component control in App Info → Components

**Request:** community feature request, relayed by the maintainer — open an activity from the
component list the way Activity Launcher does; force-open the ones that are not exported; restrict
services, broadcast receivers and wakelocks the way other root apps do.
**Date:** 2026-08-25
**Status:** design approved in chat (Section 1 approved explicitly; Sections 2–5 decided under the
maintainer's instruction to proceed). Implementation on `feat/component-control`.
**Branch:** `feat/component-control` → PR against `dev`. No `versionCode` bump (feature branch).

---

## 1. What the request assumed, and what the platform actually does

Four platform facts were verified against raw AOSP sources across `pie-release` …
`android16-release` (API 28–36) before any design was drawn. Three of them contradict a premise in
the request, so they are recorded here rather than buried in a commit message.

### 1.1 Launching a non-exported activity needs uid 0. Not "privilege" — uid 0.

`ActivityManager.canAccessUnexportedComponents(uid)` waives the export check for `ROOT_UID` (0) and
`SYSTEM_UID` (1000) only. The permission that would otherwise buy it, `START_ANY_ACTIVITY`, is
`protectionLevel="signature"` in every release from Android 9 to 16 **and is not present in
`packages/Shell/AndroidManifest.xml` in any of them**. `pm grant` refuses it ("not a changeable
permission type").

Consequence: **Shizuku-over-ADB cannot force-launch a non-exported activity.** Shizuku *started as
root* runs at uid 0 and can. A Device Owner cannot either — `DevicePolicyManagerService` never
references `START_ANY_ACTIVITY`; a DO's only activity-launch privilege is an exemption from
background-activity-launch limits.

The `startActivityAsCaller` back door is also closed: it needs `START_ACTIVITY_AS_CALLER` (also
signature, also absent from Shell) *and* a non-null `resultTo` activity token, which `cmd activity`
never has.

### 1.2 Shell cannot change component state at all — this is not new, and not about SELinux

`PackageManagerService` carries a uid-specific carve-out, unchanged from `pie-release` through
`android16-release`:

```java
if (callingUid == Process.SHELL_UID
        && (pkgSetting.getFlags() & ApplicationInfo.FLAG_TEST_ONLY) == 0) {
    // Shell can only change whole packages between ENABLED and DISABLED_USER states
    if (className == null
            && (oldState in {DISABLED_USER, DEFAULT, ENABLED})
            && (newState in {DISABLED_USER, DEFAULT, ENABLED})) {
        // allowed
    } else {
        throw new SecurityException("Shell cannot change component state for "
                + packageName + "/" + className + " to " + newState);
    }
}
```

`className == null` is a requirement of the allowed branch. So from a shell uid:

- `pm disable-user <pkg>` — allowed. (This is exactly what Thor's existing Shizuku freeze does; the
  codebase has been living inside this rule without naming it.)
- `pm disable <pkg>` — refused. Whole-package `DISABLED` is not in the allowed set.
- `pm disable-user <pkg>/<cls>` — refused. Any non-null class name is refused.

Shell *does* hold `CHANGE_COMPONENT_ENABLED_STATE` (it is `signature|privileged` and
`com.android.shell` is platform-signed and privileged), which is why this surprises people. Holding
the permission is not sufficient; the uid check runs anyway. App Manager encodes the same rule
client-side, with the comment *"Since Oreo, shell can only disable components of test only apps."*

The reflection fallback Thor uses elsewhere buys nothing here: `IPackageManager
.setComponentEnabledSetting` arrives at the same check with the same calling uid.

A Device Owner has no route either — `DevicePolicyManager` exposes zero component-enabled APIs
(`grep -n "ComponentEnabled\|COMPONENT_ENABLED"` over the whole class returns nothing). Its
whole-app hammers are `setApplicationHidden` and `setPackagesSuspended`, and
`setUserControlDisabledPackages` is the *opposite* of a restriction — it marks packages protected
from being disabled or force-stopped.

**Therefore per-component control is uid 0 only:** root, or Shizuku started as root.

### 1.3 Restricting wakelocks through app-ops is a placebo

`PowerManagerService` contains no app-op check anywhere. `Notifier` calls
`mAppOps.startOpNoThrow(OP_WAKE_LOCK, …)` and **discards the result**, directly beneath a standing
AOSP comment reading `// XXX need to deal with disabled operations.` When the wakelock carries a
`WorkSource` the op is not even noted — the `startOpNoThrow` call sits in the `else` branch of
`if (workSource != null)`.

`OP_WAKE_LOCK` is backed by the `WAKE_LOCK` permission, which is `protectionLevel="normal|instant"`
and therefore cannot be revoked by permission machinery either. So `appops set <pkg> WAKE_LOCK
ignore` changes accounting and nothing else.

Genuine partial-wakelock suppression in `PowerManagerService` is uid-level only: cached-process
state, exclusion from the device-idle allowlist, and Low Power Standby. Per-tag blocking exists
only as an Xposed hook on `acquireWakeLockInternal` — and even Amplify throttles (one acquire per
240 s per tag) rather than blocking.

**Wakelock restriction is therefore out of scope for this feature**, and is not merely deferred for
effort reasons. What *is* achievable, and is a different feature with a different name, is listed in
§9.

### 1.4 What the state we write actually survives

- Component-level disabled entries are persisted per user in
  `/data/system/users/<id>/package-restrictions.xml` and **survive reboot and app update** — on
  replace, PMS prunes only the component names that no longer exist in the new APK
  (`pkgSetting.restoreComponentSettings`).
- Whole-*package* state is the opposite: reset to `DEFAULT` on every install/update unless the
  session opted into `setApplicationEnabledSettingPersistent()` (Android 13+; unconditional reset
  before that). A Play update silently re-enables a `pm disable-user`d app. Not our problem here,
  but worth knowing before anyone reasons by analogy from freeze.
- Clearing app data does **not** reset component state unless the target app itself declared
  `android:resetEnabledSettingsOnAppDataCleared`. Thor ships a Clear Data action; users will assume
  otherwise.
- An app can always re-enable **its own** components — `isCallerTargetApp` is an unconditional
  allow in PMS. A determined app undoes anything we write, on its next launch. The only mechanism
  that beats that is Intent Firewall (§9.1), which is not in this cut.

---

## 2. Capability matrix

Keyed on **effective uid**, not on `PrivilegeMode`, because Shizuku has two of them.

| Operation | No privilege | Shizuku @ shell (2000) | Shizuku @ root (0) | Dhizuku (DO) | Root |
|---|---|---|---|---|---|
| Open an **exported** activity with no `android:permission` | ✅ | ✅ | ✅ | ✅ | ✅ |
| Open an exported activity **guarded by a permission** Thor lacks | ❌ | ❌ | ✅ | ❌ | ✅ |
| Force-open a **non-exported** activity | ❌ | ❌ | ✅ | ❌ | ✅ |
| Enable/disable **one component** | ❌ | ❌ | ✅ | ❌ | ✅ |
| `am stopservice` on a non-exported service | ❌ | ❌ | ✅ | ❌ | ✅ |

The first row needs no privilege at all: any app may launch an exported activity, and a user tap
gives Thor a visible window, so background-activity-launch limits do not apply. **"Open" is
therefore available to every user of Thor, including one with no privilege mode configured** — a
point worth making because it is the only part of this feature most users can use.

Row 2 is the case every comparable tool except sdex/ActivityManager gets wrong: an *exported*
activity guarded by an `android:permission` Thor does not hold fails exactly like a non-exported
one. The predicate is `launchRequiresRoot = !exported || permission != null`.

---

## 3. Scope

**In:**

1. Per-component metadata in the model (exported / effective enabled state / manifest default /
   guarding permission).
2. **Open** and **Force Open** on activity rows.
3. **Disable / Enable / Reset to default** on every component type.
4. **Stop now** on service rows (transient; `am stopservice`).
5. A bookkeeping ledger of what Thor disabled, with a **Restore all**. ⚠️ **Shipped wider than this
   line says:** the button is *offered* per app, but it restores **every** row in the ledger, across
   every package — see §6.4.

**Out** (with reasons, so nobody re-derives them): wakelock restriction (§1.3), Intent Firewall
(§9.1), enforcement/re-apply after update or boot (explicitly chosen against — the ledger is
bookkeeping), bulk multi-select component operations, per-component labels (§9.3), cross-user
component state, and any new permission in Thor's manifest.

---

## 4. Data model

### 4.1 `ComponentDetail` replaces `List<String>`

`AppRepositoryImpl.getDetailedAppInfo` already requests `GET_ACTIVITIES | GET_SERVICES |
GET_RECEIVERS | GET_PROVIDERS | … | MATCH_DISABLED_COMPONENTS | MATCH_DISABLED_UNTIL_USED_COMPONENTS`
and then discards every attribute at one line:

```kotlin
val activities = packInfo.activities?.map { it.name } ?: emptyList()
```

That line is the choke point. It becomes a mapper producing:

```kotlin
@Serializable @Immutable
data class ComponentDetail(
    val className: String,
    val exported: Boolean,
    val enabled: Boolean,                 // effective state, right now
    val manifestDefaultEnabled: Boolean,  // android:enabled
    val permission: String? = null,       // android:permission guarding entry
)
```

Two booleans instead of a four-value enum: `enabled != manifestDefaultEnabled` *is* "somebody
overrode this" (what Reset-to-default needs to know), and `!manifestDefaultEnabled` is "off by
design, not by anyone's choice" — a distinction the UI must draw or it invites people to "fix"
components the developer shipped off deliberately.

### 4.2 Reading effective state costs two binder calls, not N

The obvious route — `getComponentEnabledSetting` per row — is hundreds of binder calls for
something like GMS. Instead, call `getPackageInfo` **twice**: once with `MATCH_DISABLED_COMPONENTS |
MATCH_DISABLED_UNTIL_USED_COMPONENTS` (what the code already does) and once without. A component
present in the first list and absent from the second is effectively not enabled;
`ComponentInfo.enabled` then says whether that is the manifest's doing or a person's.

`ComponentInfo.enabled` alone cannot answer this: it only ever reports the manifest attribute, never
the runtime override. That is the trap this avoids.

The diff is computed over a `Set<String>` of class names, because PackageManager returns components
un-deduped — the existing LazyColumn key comment in `AppInfoDetailsScreen.kt` already documents
duplicate class names within one package.

State is **current-user only**, matching what `getDetailedAppInfo` does today.

### 4.3 The ledger — `component_overrides`, schema v7

```kotlin
@Entity(
    tableName = "component_overrides",
    primaryKeys = ["packageName", "className", "userId"],
    indices = [Index("packageName")],
)
data class ComponentOverrideEntity(
    val packageName: String,
    val className: String,
    val userId: Int,
    val componentType: String,      // so a row still renders after the component disappears
    val restoreToEnabled: Boolean,  // the manifest default captured at write time
    val disabledAt: Long,
)
```

A pure table-add, so an `AutoMigration` like every migration since v2.

`restoreToEnabled` is captured **when the override is written**, because a later app version can
change the manifest default, and "Restore" has to mean "put it back the way the developer shipped
it", not "enable it".

`userId` is in the primary key with no precedent to copy — `freezer_apps` and `freeze_profile_apps`
are package-keyed only — because a restore that targets the wrong user is silent and wrong.

Known risk, stated rather than hidden: **there is no Room migration test anywhere in this repo** and
`fallbackToDestructiveMigration` is debug-only. A table-add `AutoMigration` is generated and
schema-validated at build time, so this is as safe as a schema change gets here, but it ships
without an upgrade test like the six before it.

---

## 5. The privileged seam

Chosen approach: **component-scoped verbs become first-class `SystemGateway` methods**, plus one
capability value object. The alternative — a separate port off the interface, following the
`clearCache` / `DataArchiveCapability` precedent — was rejected because it creates a second
privileged-dispatch mechanism that must re-derive the Root → Shizuku → Dhizuku fallback. Riding the
generic `executeShellCommand` seam was rejected outright: no reflection fallback, no per-mode
judgement, and it abandons the codebase's rule that a mutating op is judged by re-reading state
rather than by an exit code.

### 5.1 New gateway methods

```kotlin
suspend fun setComponentEnabled(packageName: String, className: String, enabled: Boolean): Result<Unit>
suspend fun forceLaunchActivity(packageName: String, className: String): Result<Unit>
suspend fun stopService(packageName: String, className: String): Result<Unit>
```

**Root** implements all three through new builders in `ComponentCommands.kt`, mirroring the
`PerUserCommands` contract exactly (internal top-level pure functions, already-escaped package,
explicit `userId: Int`, escaping done exactly once by the caller):

| Verb | Command |
|---|---|
| disable | `pm disable --user N <pkg>/<cls>` |
| enable, manifest default was **on** | `pm default-state --user N <pkg>/<cls>` |
| enable, manifest default was **off** | `pm enable --user N <pkg>/<cls>` |
| force launch | `am start --user N -n <pkg>/<cls>` |
| stop service | `am stopservice --user N <pkg>/<cls>` |

The enable split matters: restoring a component whose manifest default is *enabled* should remove
the override entirely (`default-state`) rather than leave a spurious explicit-ENABLED record behind.

**Shizuku** runs the identical commands **only when `Shizuku.getUid() == 0`**, and otherwise returns
a localized refusal. This is the one place in the codebase where the Shizuku gateway's behaviour
depends on its own uid, so it is commented with §1.2's reason.

**Dhizuku** always refuses, localized. The refusal follows `DhizukuSystemGateway.clearAllCaches`'s
pattern (a `UiText` string resource), *not* the two hardcoded-English refusals that also exist in
the tree.

### 5.2 Judging success

Exit codes are not trusted, per house rule, and here they actively lie:

- `am start` prints `Security exception: …` plus a Java stack trace on **stderr** for a permission
  denial, and from Android 14 the SecurityException path is deliberately converted to
  `START_CLASS_NOT_FOUND` so as not to disclose package existence. A background-launch abort is
  *silent* — `ActivityStarter.getExternalResult` rewrites `START_ABORTED` to `START_SUCCESS`.
- `am force-stop`'s shell command ends in an unconditional `return 0` (existing precedent for why
  Thor re-reads state).

So:

- `setComponentEnabled` is judged by re-reading `getComponentEnabledSetting(ComponentName)` and
  comparing against what was asked for. That read needs no permission.
- `forceLaunchActivity` and `stopService` cannot be judged by re-reading anything, so they are
  judged by exit code **plus** an output scan for the known failure shapes, and the resulting error
  is mapped to a human sentence (§7).

### 5.3 `ComponentCapability` — one place that answers "can this mode do this"

The repo currently spells "mode X can't do this" **eight different ways**, only one of which
(`FreezePolicy.uninstallFreezeFallbackAllowed`) is an exhaustive `when (PrivilegeMode)` that a new
mode would break the build on. This feature adds a ninth idiom unless it is consolidated, so:

```kotlin
enum class ComponentControlBlocker { NONE, NOT_READY, NO_PRIVILEGE, SHIZUKU_NOT_ROOT, DHIZUKU }

data class ComponentCapability(
    val canForceLaunch: Boolean,
    val canSetComponentState: Boolean,
    val canStopService: Boolean,
    val blocker: ComponentControlBlocker,
)

fun componentCapability(mode: PrivilegeMode?, isReady: Boolean, shizukuUid: Int?): ComponentCapability
```

A pure function in `domain/`, exhaustive over `PrivilegeMode`, therefore JVM-testable — the
`FreezePolicy` template. Reading Shizuku's uid is not JVM-testable (`rikka.shizuku.Shizuku`'s static
init builds a Binder and throws "not mocked"), so the uid is *passed in* by a thin data-layer
provider rather than read inside the pure function.

`NOT_READY` is a distinct blocker because `HomeViewModel` already shows the *preferred* mode while
`isReady == false`; a capability that collapses "not ready yet" into "not capable" would tell a root
user during cold start that they cannot do something they can.

---

## 6. UX

### 6.1 The row

Today a component row is the fully-qualified class name in Fira Mono, with the whole row clickable
to copy. It gains a leading state marker, optional badges, an optional trailing action, and an
overflow:

```
┌──────────────────────────────────────────────────────────────┐
│ ● com.example.app.ui.MainActivity              [ Open ]  ⋮   │
│ ○ com.example.app.ui.DebugActivity   NOT EXPORTED  [Force] ⋮ │
│ ⊘ com.example.app.sync.SyncService    DISABLED           ⋮   │
│ ◌ com.example.app.ui.LegacyActivity   OFF BY DEFAULT     ⋮   │
└──────────────────────────────────────────────────────────────┘
```

Row click keeps its current meaning (copy the class name) so nothing existing is taken away.

**Overflow contents**, by type and capability:

- Copy class name — always.
- Disable / Enable — every type, when `canSetComponentState`.
- Reset to default — only when `enabled != manifestDefaultEnabled`.
- Stop now — services only, when `canStopService`.
- When the capability is missing, the destructive items are **replaced by one muted, non-clickable
  line naming the blocker** ("Needs root — Shizuku over ADB cannot change component state"), rather
  than shown as dead controls. Dead switches teach people the feature is broken; a sentence teaches
  them what to change. `NOT_READY` shows "Checking privileges…" instead.

### 6.2 Force Open is visibly different from Open

Non-exported and permission-guarded activities get a distinct label and a distinct colour, following
sdex, which highlights them. Thor does **not** copy sdex's decision to *hide* non-exported
activities behind an opt-in dialog: this list already shows every component, and hiding them would
be a regression on the surface's existing purpose.

### 6.3 First-use disclaimer

The first time a user changes any component state, a one-time dialog explains that disabling
components can break the app, that the app can undo it by itself, and that Thor keeps a list to
restore from. Acceptance persists through the existing preferences seam. This is Activity Launcher's
proven pattern, minus its hard-exit-on-decline behaviour (declining simply cancels the action).

### 6.4 Restore all

When the ledger holds rows for the current package, the Components tab header shows
`N restricted by Thor · Restore all`. Restoring walks the ledger and applies `restoreToEnabled`.

⚠️ **Correction, from the shipped implementation.** The trigger is per-package but the *action* is
not: `ComponentControlUseCase.restoreAll` calls `ComponentOverrideRepository.getAll()`, so it restores
every row in every package — though **not** every Android user, because `getAll()` filters on
`thorUserId`, so another user's or a work profile's rows are untouched. "Cross-app" is exact here and
"device-wide" is not. A per-package restore would be the smaller promise, but the ledger's
reason to exist is that a component disabled weeks ago in a forgotten app is otherwise unfindable, so
the wider scope was chosen deliberately and `component_restore_all_message` names it ("in this app and
in every other") before anything happens. Read the count in the header as *"this app's share of what
the button will undo"*, not as its scope. Anything written against the narrower reading — user docs
especially — is wrong.

Honesty requirement, borrowed verbatim from App Manager's documentation: **Thor only tracks what
Thor changed.** A component disabled by another tool shows as disabled but is not in the ledger, and
Restore all does not touch it. The UI says so rather than implying total knowledge.

---

## 7. Errors

Every failure travels through `AppInfoDetailsViewModel`'s existing events `Channel` → Toast, as
`UiText`, with `UiTextException` for the translated cases — the established plumbing, no new bus.

Raw exception text is never shown. Activity Launcher's failure path is literally
`Toast(… + ": " + e)` and it is the anti-pattern this feature is measured against. The known shapes
are mapped:

| Observed | Shown |
|---|---|
| `Security exception:` / `not exported` | "Android refused: this activity is private to *App*." |
| `does not exist` / `START_CLASS_NOT_FOUND` | "That component no longer exists in this app." |
| `unable to resolve Intent` | "Nothing on this device can open that." |
| Odin `JOB_NOT_EXECUTED` (-1) | The existing transport-failure string. |
| Anything else | Existing `R.string.error_format` fallback. |

After any state change the row is refreshed from the **observed** state, never optimistically from
the command's return.

---

## 8. Testing

JVM (the gate that runs in CI):

- `ComponentCommandsTest` — mirrors `PerUserCommandsTest`: every builder pinned as a whole string,
  and the reflective sweep that fails any builder not varying with the user id.
- `ComponentCapabilityTest` — exhaustive over `PrivilegeMode`, both Shizuku uids, and `isReady =
  false`. The `FreezePolicy` test is the template.
- Use-case and ViewModel tests asserting against `FakeSystemRepository.calls` — the ordered list of
  commands that reached the privilege layer — not against the returned `Result`, because a gate that
  refuses *after* the command was issued satisfies a `Result` assertion while the component is
  already disabled.
- `SystemRepositorySurfaceTest` updated for the three new methods.

Device:

- Unprivileged **Open** and the capability gating are testable on any emulator.
- The root paths need uid 0. An `android-30/google_apis` AVD gives `adb root` (a root *shell*), but
  Thor's root gateway needs `su` reachable from an **app** uid, which an AOSP emulator image does
  not provide. A Magisk-patched AVD is attempted; if it does not come up, the root paths are
  verified by the maintainer on physical hardware and this document is updated with the result
  rather than the feature being claimed as verified.

---

## 9. Deliberately not in this cut

### 9.1 Intent Firewall

App Manager blocks activities, services and broadcasts by writing XML to `/data/system/ifw` — alive
in AOSP `main` today, hot-reloaded by a `FileObserver` ~250 ms after a write, surviving reboot, app
update and even uninstall, and **undetectable by the target app**, which is a real advantage given
§1.4's note that an app can re-enable its own components. It is rejected for v1 because it means a
raw write into `/data/system` (root-only in practice — App Manager's capability check is literally a
writability probe), it has no `<provider>` tag so providers need a second mechanism and the UI then
has to explain which one is in force per row, and its behaviour under KernelSU/APatch SELinux
policy and on OEM ROMs is unverified. Worth doing later; not worth doubling the surface now.

### 9.2 Wakelocks, honestly

Per §1.3 the app-op is a placebo. The achievable, shell-capable (therefore Shizuku-capable) levers
are `cmd deviceidle whitelist -<pkg>`, `am set-standby-bucket <pkg> restricted`, and force-stop —
which genuinely drops the package's alarms and cancels its jobs via `ACTION_PACKAGE_RESTARTED`. Per-tag
blocking would need Thor's own Xposed module (Strombringer) hooking
`PowerManagerService.acquireWakeLockInternal`. That is a separate feature with a separate name, and
promising "restrict wakelocks" through app-ops would be shipping a lie.

### 9.3 Component labels

Activity Launcher shows human-readable labels. Doing so means a resource lookup per component into
another app's assets — 600+ for a large system app — and a taller row on a surface that is
deliberately monospaced class names. The cheap subset, if it is ever wanted, is launcher activities
only: one extra `queryIntentActivities` call, no per-row cost.

### 9.4 Bulk

The multi-select seam already exists (`MultiSelectToolBox` → `MultiAppAction` → the exhaustive
`MultiAppAffirmationDialog` `when` → `performLoggedMultiAction`) and "disable all receivers of these
12 apps" would ride it. The primitive lands first; the bulk verb is a follow-up that costs one
sealed-interface entry.

---

## 10. Risks

1. **Root-only means most users get only "Open".** That is the platform's decision, not ours, but it
   should be said plainly in the release notes rather than discovered.
2. **A disabled component can be re-enabled by the app itself** (§1.4). The ledger will then show a
   row whose real state has drifted. The UI reads state live on every load, so drift is *visible*;
   it is not silently corrected.
3. **No Room migration test** (§4.3).
4. **Disabling a provider can break other apps**, not just this one. Providers are in scope because
   excluding them would be arbitrary, but the disclaimer names this case.
5. **OEM ROM divergence** on `pm`'s component syntax is unverified beyond AOSP.
