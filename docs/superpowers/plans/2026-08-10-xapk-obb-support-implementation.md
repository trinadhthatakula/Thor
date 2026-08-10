# XAPK OBB Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Thor pack an app's OBB expansion files into the `.xapk` it exports, and place bundled OBB files into `Android/obb/<pkg>/` when installing a `.xapk` — closing GH#164.

**Architecture:** One privileged surface (`SystemRepository.executeShellCommand`, which is gateway-routed and therefore works under root *and* Shizuku) does the probing, the reading and the placement. Both directions stage bytes through `context.externalCacheDir`, the only filesystem location Thor and the shell uid can both read and write. All decision logic — probe-output parsing, entry naming, path validation, free-space arithmetic — lives in pure top-level functions so it is JVM-unit-testable without a device, the way `stagedApkNames` already is.

**Tech Stack:** Kotlin, Coroutines, kotlinx.serialization, `java.util.zip` (`ZipFile`/`ZipOutputStream`), Koin Annotations (compiler plugin), Jetpack Compose + Material 3, JUnit4.

**Spec:** `docs/superpowers/specs/2026-08-10-xapk-obb-support-design.md` — read it before Task 1. Section references below (§4.3, §6.1, …) point into it.

## Global Constraints

- **The code blocks below are drafts, not verified source.** Task 1 shipped with two contradictions
  between its test block and its implementation block, one of which was a real runtime defect (the
  sentinel-ordering bug that would have made every OBB-less app probe as `Undetermined`). Run each
  task's tests before believing its implementation; when the two disagree, work out which side the
  rest of the system agrees with rather than editing whichever is easier. Report every deviation.

- **Branch:** `feat/xapk-obb-support`, already created off `dev` at `69d74ecf`. Never commit to `dev` or `master`. The PR targets `dev`.
- **Never add a `Co-Authored-By: Claude` trailer to any commit.**
- **Never `git add -A` or `git add .`** — stage explicit paths only. `docs/discussions/` must stay untracked and `gradle/libs.versions.toml` carries a pre-existing unrelated AGP bump that must stay unstaged.
- **Write files only with the native Write/Edit tools**, never through a shell heredoc.
- **Gradle runs through `ctx_execute` with `language: "shell"`**, never Bash.
- **Unit tests need `--rerun-tasks`** or the task reports UP-TO-DATE and silently skips. **Delete `app/build/test-results/testFossDebugUnitTest` before every run** — a failed compile leaves the previous run's passing XMLs in place. **Take pass/fail counts from the XML, never from the log line.**
- **The shell is zsh:** quote every glob (`rg -g '*.kt'`). An unquoted `--include=*.kt` returns empty output with exit 0.
- **Do not touch `versionCode` in `gradle.properties`** and do not write release notes. Those belong to a separate `chore(release)` commit.
- **Lint is fatal** (`warningsAsErrors`, `checkTestSources`). Any new string resource must be added to **all five** locales — `values`, `values-ar`, `values-es`, `values-fr`, `values-zh-rCN` — or `MissingTranslation` fails the build. An unused string fails `UnusedResources`. A string present only in a translation fails `ExtraTranslation`.
- **Koin uses the compiler plugin, not KSP**, with `compileSafety`, `strictSafety` and `unsafeDslChecks` all true. Annotate new classes `@Single`/`@Factory`; do not add bindings to `di/Modules.kt` unless the component scan genuinely cannot see the type.
- **No new manifest permission.** `MANAGE_EXTERNAL_STORAGE` is out of scope and out of the manifest (spec §3.3).
- **Wire-format fidelity over taste.** The XAPK manifest's SDK levels are `String` on purpose; `expansions` follows the same rule — match what `cade335/xapk-installer`, `dqh147258/XApkInstaller` and `litefeel/adbtool` read, not what looks tidier.
- **Dhizuku degrades visibly, never silently.** Under Dhizuku the `.xapk` chip is disabled with a stated reason, and an OBB-carrying archive is refused before anything is installed.
- **`.apks` never carries expansions.** OBB is an XAPK-only convention (spec §9).

---

## File Structure

**New production files**

| File | Responsibility |
|---|---|
| `app/src/main/java/com/valhalla/thor/domain/model/ObbProbe.kt` | The tri-state probe result (`None` / `Present` / `Undetermined`) and `ObbFile`. Domain, no Android types. |
| `app/src/main/java/com/valhalla/thor/data/repository/ObbProbeParser.kt` | Builds the probe shell command and parses its output. Pure, top-level, `internal`. |
| `app/src/main/java/com/valhalla/thor/data/repository/ObbExpansions.kt` | The `Android/obb/<pkg>/<leaf>.obb` path convention, shared by both directions: pack-side planning, install-side validation, free-space arithmetic. Pure, top-level, `internal`. |
| `app/src/main/java/com/valhalla/thor/data/repository/ObbInstaller.kt` | `@Single` — places extracted expansions into `Android/obb/<pkg>/` through the privileged shell. The only file that writes outside app storage. |

**Modified production files**

| File | Change |
|---|---|
| `domain/repository/SystemRepository.kt` | `+ suspend fun probeObb(packageName: String): ObbProbe` |
| `data/repository/SystemRepositoryImpl.kt` | Implement `probeObb` over `executeShellCommand`. |
| `data/util/ApksMetadataGenerator.kt` | `XapkExpansion` + nullable `expansions` on `XapkManifest` + an overload that accepts them. |
| `data/repository/BundleAnalysis.kt` | Reader-side `XapkExpansionInfo` + `expansions` on `XapkManifestInfo`. |
| `data/repository/AppBundleBuilderImpl.kt` | `ZipSource` entry-name model, OBB staging, free-space precondition. |
| `data/repository/BundleZip.kt` | `MAX_EXPANSION_TOTAL_BYTES` + `extractExpansions`. |
| `data/repository/InstallerRepositoryImpl.kt` | Refuse-before-install, place-after-success. |
| `presentation/appList/ExportBottomSheet.kt` | Probe on open; `.xapk` chip disabled with a reason when `Undetermined`. |
| `presentation/appList/AppInfoDetailsViewModel.kt` | `obbProbe` in the ui state. |
| `presentation/appList/AppInfoDetailsScreen.kt` | OBB card fed by the probe instead of `File.exists()`. |
| `res/values*/strings.xml` (×5) | Retract the false Android-forbids-it claim; add three strings. |

**New test files**

| File | Covers |
|---|---|
| `app/src/test/java/com/valhalla/thor/data/repository/ObbProbeParserTest.kt` | Command building + every probe-output shape. |
| `app/src/test/java/com/valhalla/thor/data/repository/ObbExpansionsTest.kt` | Path convention, the hostile-input table, free-space arithmetic. |
| `app/src/test/java/com/valhalla/thor/data/repository/ObbExpansionZipTest.kt` | `extractExpansions` — extraction, budget, refusals. |

**Modified test files**

| File | Change |
|---|---|
| `app/src/test/java/com/valhalla/thor/domain/repository/SystemRepositorySurfaceTest.kt` | A declaration lock for `probeObb`. |
| `app/src/test/java/com/valhalla/thor/data/util/ApksMetadataGeneratorTest.kt` | `expansions` round trip; absence when there are none. |

---

## Task 1: The probe — model, command and parser

The probe is the whole feature's foundation: under the chosen failure policy, `None` and `Undetermined` collapsing into each other reproduces exactly the bug we are fixing. Everything here is pure, so it is fully testable on the JVM.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/domain/model/ObbProbe.kt`
- Create: `app/src/main/java/com/valhalla/thor/data/repository/ObbProbeParser.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/ObbProbeParserTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed interface ObbProbe` with `data object None`, `data class Present(val files: List<ObbFile>, val otherEntryCount: Int)`, `data class Undetermined(val reason: String)`
  - `data class ObbFile(val name: String, val sizeBytes: Long)`
  - `internal fun obbProbeCommand(externalStorageDir: String, packageName: String): String?`
  - `internal fun parseObbProbe(exitCode: Int, output: String?): ObbProbe`
  - `internal fun isUsablePackageName(value: String): Boolean`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/data/repository/ObbProbeParserTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObbProbeParserTest {

    private fun listing(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `a normal listing yields Present with sizes`() {
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB 1048576 /storage/emulated/0/Android/obb/com.example.game/main.12.com.example.game.obb",
                "THOR_OBB 2048 /storage/emulated/0/Android/obb/com.example.game/patch.12.com.example.game.obb",
                "THOR_OTHER 0",
                "THOR_END"
            )
        )

        assertEquals(
            ObbProbe.Present(
                files = listOf(
                    ObbFile("main.12.com.example.game.obb", 1048576L),
                    ObbFile("patch.12.com.example.game.obb", 2048L)
                ),
                otherEntryCount = 0
            ),
            probe
        )
    }

    @Test
    fun `THOR_NODIR means the app genuinely has no OBB`() {
        assertEquals(ObbProbe.None, parseObbProbe(0, listing("THOR_NODIR")))
    }

    @Test
    fun `an empty OBB directory is None`() {
        assertEquals(ObbProbe.None, parseObbProbe(0, listing("THOR_OTHER 0", "THOR_END")))
    }

    @Test
    fun `THOR_NOPRIV is Undetermined, never None`() {
        val probe = parseObbProbe(0, listing("THOR_NOPRIV"))
        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `a listing without the end sentinel is Undetermined`() {
        // Truncated output — the shell died mid-script, or the gateway dropped the tail. Reading
        // this as None is the exact failure the tri-state exists to prevent: it would offer a
        // .xapk and silently build it without the game data.
        val probe = parseObbProbe(
            0,
            listing("THOR_OBB 10 /storage/emulated/0/Android/obb/com.example.game/main.obb")
        )
        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `a non-zero exit is Undetermined`() {
        assertTrue(parseObbProbe(1, listing("THOR_NODIR")) is ObbProbe.Undetermined)
    }

    @Test
    fun `null and blank output are Undetermined`() {
        assertTrue(parseObbProbe(0, null) is ObbProbe.Undetermined)
        assertTrue(parseObbProbe(0, "   ") is ObbProbe.Undetermined)
    }

    @Test
    fun `a file name containing spaces survives, because only the first space is a separator`() {
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB 99 /storage/emulated/0/Android/obb/com.example.game/main 1.obb",
                "THOR_OTHER 0",
                "THOR_END"
            )
        )

        assertEquals(listOf(ObbFile("main 1.obb", 99L)), (probe as ObbProbe.Present).files)
    }

    @Test
    fun `garbage lines are ignored rather than fatal`() {
        // Shells warn on stderr-merged streams and toybox versions differ; an unrecognised line
        // must not turn a good listing into a refusal.
        val probe = parseObbProbe(
            0,
            listing(
                "sh: something harmless",
                "THOR_OBB 5 /storage/emulated/0/Android/obb/com.example.game/main.obb",
                "THOR_OTHER 2",
                "THOR_END"
            )
        )

        assertEquals(ObbProbe.Present(listOf(ObbFile("main.obb", 5L)), 2), probe)
    }

    @Test
    fun `a non-obb name on an OBB line makes the probe Undetermined`() {
        // The shell only emits THOR_OBB for a *.obb glob match, so a THOR_OBB line whose name is
        // not a .obb means data was lost in transit — most plausibly a file name containing a
        // newline, which splits across two lines and leaves the head without its extension.
        // Dropping the line would tell the builder to pack zero expansions and ship a .xapk
        // without the game's data: #164 again, from a new direction.
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB 5 /storage/emulated/0/Android/obb/com.example.game/main.obb.part",
                "THOR_OTHER 1",
                "THOR_END"
            )
        )

        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `an unparseable size makes the probe Undetermined`() {
        // Present(emptyList(), 0) would be the worst of the three answers available here: it
        // claims the directory holds nothing at all, when we have just been told a file is in it.
        val probe = parseObbProbe(
            0,
            listing(
                "THOR_OBB notanumber /storage/emulated/0/Android/obb/com.example.game/main.obb",
                "THOR_OTHER 0",
                "THOR_END"
            )
        )

        assertTrue("$probe should be Undetermined", probe is ObbProbe.Undetermined)
    }

    @Test
    fun `the command quotes both interpolated values`() {
        val command = obbProbeCommand("/storage/emulated/0", "com.example.game")

        assertTrue(command!!, command.contains("'/storage/emulated/0/Android/obb'"))
        assertTrue(command, command.contains("'/storage/emulated/0/Android/obb/com.example.game'"))
        assertTrue(command, command.contains("THOR_END"))
    }

    @Test
    fun `a package name that is not a package name yields no command at all`() {
        // This string is assembled into a shell command. It comes from PackageManager rather than
        // from user input, but a validator here means the shell does not have to be trusted with
        // that assumption.
        assertNull(obbProbeCommand("/storage/emulated/0", "com.example.game; rm -rf /"))
        assertNull(obbProbeCommand("/storage/emulated/0", "../../etc"))
        assertNull(obbProbeCommand("/storage/emulated/0", ""))
        assertNull(obbProbeCommand("/storage/emulated/0", "com..example"))
    }

    @Test
    fun `an external storage dir containing a quote is rejected too`() {
        assertNull(obbProbeCommand("/storage/emu'lated/0", "com.example.game"))
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ObbProbeParserTest"
```
Expected: FAIL — compilation error, `Unresolved reference: ObbProbe` / `parseObbProbe` / `obbProbeCommand`.

- [ ] **Step 3: Write the domain model**

Create `app/src/main/java/com/valhalla/thor/domain/model/ObbProbe.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

/**
 * What a privileged look at `Android/obb/<pkg>` found — three answers, not two.
 *
 * `AppInfo.obbFilePath` is computed with `File(...).exists()`, which on Android 11+ returns false
 * for another package's OBB directory *whether or not one exists*. Any probe shaped like a path
 * test therefore folds "I cannot see it" into "there is none" — and under Thor's export policy
 * ("only offer .xapk when the OBB is capturable") that fold produces exactly the silently
 * incomplete bundle GH#164 is about. So the probe asserts its own privilege first and only then
 * interprets absence.
 */
sealed interface ObbProbe {

    /** The privileged read succeeded and the app has no expansion files. `.xapk` is offered. */
    data object None : ObbProbe

    /**
     * The privileged read succeeded and found something.
     *
     * [files] holds the depth-1 `*.obb` files, the only shape the XAPK format can carry.
     * [otherEntryCount] counts everything else in the directory — subdirectories, non-`.obb`
     * files — which will not be packed. It is a note shown to the user, not a refusal: the format
     * has no way to carry those, so refusing would deny a bundle that is complete by the format's
     * own definition.
     *
     * [files] may be empty while [otherEntryCount] is not. That is still `Present`, because the
     * directory exists and holds content Thor deliberately leaves out.
     */
    data class Present(val files: List<ObbFile>, val otherEntryCount: Int) : ObbProbe

    /**
     * The active privilege could not read `Android/obb` at all — the Dhizuku device-owner process,
     * a gateway failure, a truncated reply, a malformed listing.
     *
     * **Never treat this as [None].** It is the whole reason this type is a tri-state.
     *
     * [reason] is diagnostic, for logs. **Do not render it in the UI.** The user-facing explanation
     * is `R.string.export_xapk_unavailable`, which is translated and says something a user can act
     * on; `reason` is English-only and, in the malformed-listing cases, describes a line that may
     * contain an attacker-chosen file name.
     */
    data class Undetermined(val reason: String) : ObbProbe
}

/** One expansion file in `Android/obb/<pkg>/`, named by its leaf. */
data class ObbFile(val name: String, val sizeBytes: Long)
```

- [ ] **Step 4: Write the command builder and parser**

Create `app/src/main/java/com/valhalla/thor/data/repository/ObbProbeParser.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import com.valhalla.thor.domain.model.ObbFile
import com.valhalla.thor.domain.model.ObbProbe

// The sentinels are `internal`, not `private`: a later fixture that synthesises probe output must
// be able to reference them rather than repeat the literal. A repeated literal in a fixture is how
// a green test hides a defect in the code it is meant to be checking.

/** Emitted when the parent `Android/obb` cannot be listed — i.e. this privilege cannot see it. */
internal const val SENTINEL_NOPRIV = "THOR_NOPRIV"

/** Emitted when the parent listed fine but the package has no OBB directory. */
internal const val SENTINEL_NODIR = "THOR_NODIR"

/** Prefix of a size+path line for one `*.obb` file. */
internal const val PREFIX_OBB = "THOR_OBB "

/** Prefix of the count of directory entries that are not depth-1 `*.obb` files. */
internal const val PREFIX_OTHER = "THOR_OTHER "

/**
 * Proof the script ran to completion.
 *
 * Output without it is [ObbProbe.Undetermined], never [ObbProbe.None]. A truncated reply and an
 * empty directory look identical otherwise, and one of those two readings silently drops a game's
 * data out of the bundle.
 */
internal const val SENTINEL_END = "THOR_END"

/**
 * The ordinary package-name shape. Deliberately stricter than the platform: this string is
 * interpolated into a shell command, and a validator is cheaper than trusting every caller to
 * have got it from `PackageManager`.
 */
private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

/**
 * The probe command, or null when [packageName] or [externalStorageDir] is not safe to interpolate.
 *
 * Every line is load-bearing:
 *  - **Listing the parent is the capability assertion.** Root and the Shizuku shell uid succeed;
 *    the Dhizuku device-owner app process does not. This is what separates `None` from
 *    `Undetermined`, and nothing else in the output can.
 *  - **`stat -c 'THOR_OBB %s %n'` puts the size first**, so a name containing spaces parses by
 *    splitting on the *first* space only.
 *  - **The prefixes are checked, not assumed.** A name containing a newline splits across lines;
 *    the tail then fails the prefix test and the head fails the `.obb` extension test.
 *  - **[SENTINEL_END] is printed last** so a truncated reply is detectable.
 *
 * [externalStorageDir] comes from `Environment.getExternalStorageDirectory().absolutePath` rather
 * than a hardcoded `/storage/emulated/0` — but Thor runs against a single `thorUserId` (see
 * `data/source/local/thorUserId`; `am get-current-user` is denied without `INTERACT_ACROSS_USERS`),
 * so there is no multi-user path to juggle.
 */
internal fun obbProbeCommand(externalStorageDir: String, packageName: String): String? {
    if (!isUsablePackageName(packageName)) return null
    if (externalStorageDir.isBlank() || !externalStorageDir.startsWith('/')) return null
    // Single-quoted below, so the one character that could break out is the single quote itself.
    if (externalStorageDir.any { it == '\'' || it == '\n' }) return null

    val parent = "$externalStorageDir/Android/obb"
    val dir = "$parent/$packageName"
    return buildString {
        append("ls -1 '").append(parent).append("' >/dev/null 2>&1 || { echo ")
        append(SENTINEL_NOPRIV).append("; exit 0; }\n")
        append("[ -d '").append(dir).append("' ] || { echo ")
        append(SENTINEL_NODIR).append("; exit 0; }\n")
        append("n=0\n")
        append("for f in '").append(dir).append("'/*; do\n")
        append("  if [ -f \"\$f\" ]; then\n")
        append("    case \"\$f\" in\n")
        append("      *.obb) stat -c 'THOR_OBB %s %n' \"\$f\" ;;\n")
        append("      *) n=\$((n+1)) ;;\n")
        append("    esac\n")
        append("  elif [ -e \"\$f\" ]; then\n")
        append("    n=\$((n+1))\n")
        append("  fi\n")
        append("done\n")
        append("echo \"THOR_OTHER \$n\"\n")
        append("echo ").append(SENTINEL_END).append("\n")
    }
}

/** True when [value] is safe to interpolate into a shell command as a package name. */
internal fun isUsablePackageName(value: String): Boolean = PACKAGE_NAME.matches(value)

/**
 * Turn one probe run into a verdict.
 *
 * The order of the checks is the contract. **Both** `THOR_NOPRIV` and `THOR_NODIR` are tested
 * before the end sentinel, because each of those branches `exit 0`s without ever reaching the
 * `echo THOR_END`. Testing the sentinel first would classify every app that simply has no OBB
 * directory — the overwhelmingly common case — as `Undetermined`, disabling the `.xapk` chip
 * almost everywhere.
 *
 * A malformed `THOR_OBB` line is fatal, while an unrecognised line is not. The shell only emits
 * that prefix for a `*.obb` glob match, so a line carrying it that will not parse means an
 * expansion file exists and could not be characterised; anything without the prefix is shell noise
 * on a merged stderr and must stay harmless.
 */
internal fun parseObbProbe(exitCode: Int, output: String?): ObbProbe {
    if (exitCode != 0) {
        return ObbProbe.Undetermined("the privileged shell exited with code $exitCode")
    }
    val text = output?.takeIf { it.isNotBlank() }
        ?: return ObbProbe.Undetermined("the privileged shell returned no output")

    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    if (lines.any { it == SENTINEL_NOPRIV }) {
        return ObbProbe.Undetermined("this access mode cannot list Android/obb")
    }
    if (lines.any { it == SENTINEL_NODIR }) return ObbProbe.None
    if (lines.none { it == SENTINEL_END }) {
        return ObbProbe.Undetermined("the privileged shell reply was truncated")
    }

    val files = mutableListOf<ObbFile>()
    lines.forEach { line ->
        if (!line.startsWith(PREFIX_OBB)) return@forEach
        val rest = line.removePrefix(PREFIX_OBB)
        val space = rest.indexOf(' ')
        if (space <= 0) {
            return ObbProbe.Undetermined("a game data entry could not be read: $line")
        }
        val size = rest.substring(0, space).toLongOrNull()
            ?: return ObbProbe.Undetermined("a game data file's size could not be read: $line")
        val name = rest.substring(space + 1).substringAfterLast('/')
        // As shipped this is `if (!isSafeObbLeafName(name))` — the same predicate Task 4 defines and
        // Task 6's copy command is gated on, so that Present means "capturable" and not merely
        // "these files exist". A name only this stricter form rejects would otherwise be reported as
        // Present, the export sheet would offer .xapk on the strength of it, and the export would
        // fail later at staging. The shipped reason strings also quote no filename: they are
        // diagnostic, and the name comes from a directory the target app writes freely.
        if (!isSafeObbLeafName(name)) {
            return ObbProbe.Undetermined("an expansion file listing named a file Thor cannot capture")
        }
        files += ObbFile(name, size)
    }

    val other = lines.firstOrNull { it.startsWith(PREFIX_OTHER) }
        ?.removePrefix(PREFIX_OTHER)
        ?.toIntOrNull()
        ?: 0

    return if (files.isEmpty() && other == 0) ObbProbe.None else ObbProbe.Present(files, other)
}
```

- [ ] **Step 5: Run the test and watch it pass**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ObbProbeParserTest"
```
Then read the real counts, never the log line:
```
cd /Users/trinadhthatakula/StudioProjects/Thor && grep -h -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testFossDebugUnitTest/*.xml
```
Expected: `tests="14" skipped="0" failures="0" errors="0"`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/model/ObbProbe.kt \
        app/src/main/java/com/valhalla/thor/data/repository/ObbProbeParser.kt \
        app/src/test/java/com/valhalla/thor/data/repository/ObbProbeParserTest.kt
git commit -m "feat(obb): tri-state OBB probe model, command and parser

The probe asserts its own privilege before interpreting absence, so
\"cannot see it\" and \"there is none\" stay distinguishable. Refs #164."
```

---

## Task 2: Expose the probe on `SystemRepository`

Wiring the pure parser to the one privileged surface that works under root *and* Shizuku. `executeShellCommand` is routed through `runGatewayAction`, unlike `copyFileWithRoot`/`getAppPaths`, which are hard root-gated — that routing is the reason this feature is not root-only.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/domain/repository/SystemRepository.kt:51`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/SystemRepositoryImpl.kt` (add an override next to the existing `executeShellCommand`)
- Test: `app/src/test/java/com/valhalla/thor/domain/repository/SystemRepositorySurfaceTest.kt` (add one test)

**Interfaces:**
- Consumes: `obbProbeCommand`, `parseObbProbe`, `ObbProbe` (Task 1); the existing `SystemRepository.executeShellCommand(command: String): Result<Pair<Int, String?>>`.
- Produces: `SystemRepository.probeObb(packageName: String): ObbProbe` — the single entry point every other task uses to ask about OBB. It never throws and never returns null; failure is `ObbProbe.Undetermined`.

- [ ] **Step 1: Write the failing test**

Append this test inside the existing `SystemRepositorySurfaceTest` class, after `the reflection actually sees the interface`:

```kotlin
    /**
     * A presence lock, the mirror of the absence lock above.
     *
     * `probeObb` is the seam three consumers depend on — the export sheet's chip gating, the
     * bundle builder's pack step and the app-info OBB card — and none of those can be unit-tested
     * on the JVM (they need gateways, and `rikka.shizuku.Shizuku`'s static initialiser throws
     * "not mocked"). This reflection check is the cheapest thing that fails if the method is
     * renamed or dropped, and it reuses [surfaceNames]'s de-mangling, so a `Result`-returning or
     * `internal` redeclaration would not slip past it.
     */
    @Test
    fun `probeObb is declared`() {
        val names = surfaceNames()

        assertTrue(
            "SystemRepository no longer declares probeObb; the sweep found $names. The export " +
                "sheet, the bundle builder and the app-info OBB card all read OBB state through " +
                "it, and each of them silently degrades to \"no OBB\" without it",
            "probeObb" in names
        )
    }
```

- [ ] **Step 2: Run the test and watch it fail**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.repository.SystemRepositorySurfaceTest"
```
Expected: FAIL — `SystemRepository no longer declares probeObb; the sweep found [...]`.

- [ ] **Step 3: Declare it on the interface**

In `app/src/main/java/com/valhalla/thor/domain/repository/SystemRepository.kt`, replace the last two lines of the interface:

```kotlin
    // Raw shell execution via the active privilege gateway (used by extensions).
    suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>>
}
```

with:

```kotlin
    // Raw shell execution via the active privilege gateway (used by extensions).
    suspend fun executeShellCommand(command: String): Result<Pair<Int, String?>>

    /**
     * Look at `Android/obb/<packageName>` through the active privilege gateway.
     *
     * Not root-only: this goes through [executeShellCommand], which `SystemRepositoryImpl` routes
     * via `runGatewayAction`, so root and Shizuku both answer it. The Dhizuku device-owner process
     * cannot see another package's external directories and gets
     * [com.valhalla.thor.domain.model.ObbProbe.Undetermined].
     *
     * Never throws. Every failure — bad package name, gateway error, truncated reply — is
     * `Undetermined`, which callers must not collapse into `None`.
     */
    suspend fun probeObb(packageName: String): com.valhalla.thor.domain.model.ObbProbe
}
```

Then hoist the import: add `import com.valhalla.thor.domain.model.ObbProbe` under the `package` line and shorten the return type to `ObbProbe` in both the signature and the KDoc link.

- [ ] **Step 4: Implement it**

In `app/src/main/java/com/valhalla/thor/data/repository/SystemRepositoryImpl.kt`, add these imports:

```kotlin
import android.os.Environment
import com.valhalla.thor.domain.model.ObbProbe
```

and add this override immediately after the existing `executeShellCommand` override:

```kotlin
    /**
     * Deliberately built on [executeShellCommand] rather than on `runGatewayAction` directly: the
     * probe and the copy that follows it must run through the *same* privileged surface, or a
     * successful probe stops being evidence that the files can actually be captured — which is
     * what lets the export sheet leave the `.xapk` chip enabled on a `Present` result.
     */
    override suspend fun probeObb(packageName: String): ObbProbe {
        val command = obbProbeCommand(
            Environment.getExternalStorageDirectory()?.absolutePath.orEmpty(),
            packageName
        ) ?: return ObbProbe.Undetermined("\"$packageName\" is not a usable package name")

        return executeShellCommand(command).fold(
            onSuccess = { (exitCode, output) -> parseObbProbe(exitCode, output) },
            onFailure = { ObbProbe.Undetermined(it.message ?: "no privileged shell is available") }
        )
    }
```

- [ ] **Step 5: Run the test and watch it pass**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.domain.repository.SystemRepositorySurfaceTest"
```
Expected: PASS. Note the file's other tests assert `containsAll`, never equality, so adding a method is explicitly allowed there.

- [ ] **Step 6: Compile the app, because adding an interface method breaks every implementor**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && ./gradlew :app:compileFossDebugKotlin
```
Expected: BUILD SUCCESSFUL. If any test double in `app/src/test` implements `SystemRepository` it will fail here — add `override suspend fun probeObb(packageName: String) = ObbProbe.None` to each such double, which is the honest default for a fake that models a device with no OBB.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/domain/repository/SystemRepository.kt \
        app/src/main/java/com/valhalla/thor/data/repository/SystemRepositoryImpl.kt \
        app/src/test/java/com/valhalla/thor/domain/repository/SystemRepositorySurfaceTest.kt
git commit -m "feat(obb): SystemRepository.probeObb over the gateway-routed shell

executeShellCommand is routed through runGatewayAction, so this works
under root and Shizuku alike rather than root-only. Refs #164."
```

---

## Task 3: The `expansions` wire format — writer and reader

The XAPK manifest field that every third-party installer reads. The nullability here is not a style choice: `xapkJson` sets `encodeDefaults = true`, so a defaulted `emptyList()` would emit `"expansions":[]` into every OBB-less `.xapk` Thor has ever produced, changing bytes for apps this feature does not touch. `explicitNulls = false` drops a null cleanly.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/util/ApksMetadataGenerator.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/BundleAnalysis.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/util/ApksMetadataGeneratorTest.kt`

**Interfaces:**
- Consumes: the existing `XapkManifest`, `xapkJson`, `generateManifestJson(appInfo, totalSize, iconName, staged)`, and reader-side `XapkManifestInfo`/`parseXapkManifest`.
- Produces:
  - `@Serializable data class XapkExpansion(file: String, installLocation: String = "EXTERNAL_STORAGE", installPath: String)` — writer side, `@SerialName("file")`, `@SerialName("install_location")`, `@SerialName("install_path")`.
  - `XapkManifest.expansions: List<XapkExpansion>? = null`
  - `ApksMetadataGenerator.generateManifestJson(appInfo, totalSize, iconName, staged, expansions: List<XapkExpansion> = emptyList()): String` — an added defaulted parameter, so no call site changes.
  - `@Serializable data class XapkExpansionInfo(file: String? = null, installPath: String? = null)` — reader side, both nullable because the input is hostile.
  - `XapkManifestInfo.expansions: List<XapkExpansionInfo> = emptyList()`

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/valhalla/thor/data/util/ApksMetadataGeneratorTest.kt` (match the file's existing `AppInfo` fixture helper; if it builds one inline, do the same here):

```kotlin
    @Test
    fun `expansions round-trip from the writer into the reader`() {
        val json = generator.generateManifestJson(
            appInfo = sampleAppInfo(),
            totalSize = 123L,
            iconName = "icon.png",
            staged = listOf(ApksMetadataGenerator.StagedApk("/data/app/base.apk", "base.apk")),
            expansions = listOf(
                XapkExpansion(
                    file = "Android/obb/com.example.game/main.12.com.example.game.obb",
                    installPath = "Android/obb/com.example.game/main.12.com.example.game.obb"
                )
            )
        )

        // The wire keys are what third-party installers read; they are not ours to rename.
        assertTrue(json, json.contains("\"expansions\""))
        assertTrue(json, json.contains("\"install_path\""))
        assertTrue(json, json.contains("\"install_location\":\"EXTERNAL_STORAGE\""))

        val parsed = parseXapkManifest(json)!!
        assertEquals(1, parsed.expansions.size)
        assertEquals(
            "Android/obb/com.example.game/main.12.com.example.game.obb",
            parsed.expansions.first().file
        )
        assertEquals(
            "Android/obb/com.example.game/main.12.com.example.game.obb",
            parsed.expansions.first().installPath
        )
    }

    @Test
    fun `no expansions means no expansions key at all`() {
        // encodeDefaults is true on xapkJson, so a defaulted emptyList would write
        // "expansions":[] into every .xapk Thor produces — changing the bytes of bundles this
        // feature does not touch. The field is nullable precisely so absence stays absence.
        val json = generator.generateManifestJson(
            appInfo = sampleAppInfo(),
            totalSize = 123L,
            iconName = "icon.png",
            staged = listOf(ApksMetadataGenerator.StagedApk("/data/app/base.apk", "base.apk"))
        )

        assertFalse(json, json.contains("expansions"))
    }

    @Test
    fun `a manifest with no expansions key reads as an empty list, not null`() {
        val parsed = parseXapkManifest(
            """{"package_name":"com.example.game","name":"Game","version_code":"12"}"""
        )!!

        assertTrue(parsed.expansions.isEmpty())
    }

    @Test
    fun `an expansion entry missing install_path parses with a null, rather than failing the whole manifest`() {
        // A hostile or merely old archive must not make the manifest unreadable — losing the
        // manifest would lose the split list too, breaking an install that could have worked.
        val parsed = parseXapkManifest(
            """
            {"package_name":"com.example.game","name":"Game","version_code":"12",
             "expansions":[{"file":"main.obb"}]}
            """.trimIndent()
        )!!

        assertEquals(1, parsed.expansions.size)
        assertNull(parsed.expansions.first().installPath)
    }
```

Add `import com.valhalla.thor.data.repository.parseXapkManifest`, `import org.junit.Assert.assertFalse` and `import org.junit.Assert.assertNull` if the file lacks them.

- [ ] **Step 2: Run the test and watch it fail**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.util.ApksMetadataGeneratorTest"
```
Expected: FAIL — `Unresolved reference: XapkExpansion`.

- [ ] **Step 3: Add the writer side**

In `app/src/main/java/com/valhalla/thor/data/util/ApksMetadataGenerator.kt`, add above `XapkManifest`:

```kotlin
/**
 * One OBB expansion file inside a `.xapk`.
 *
 * The field names are the wire format, fixed by the installers that consume it — verified against
 * `cade335/xapk-installer`, `dqh147258/XApkInstaller` and `litefeel/adbtool`. [installLocation] is
 * decorative: every implementation checked writes `"EXTERNAL_STORAGE"` and none of them branches
 * on it. It is emitted anyway, because an installer that *does* read it would find a value it
 * expects rather than an absent key.
 *
 * [file] is the zip entry path; [installPath] is the destination relative to the external-storage
 * root. Thor writes them equal (see `expansionEntryName`), which is what the reference installers
 * assume, but they are separate fields on the wire and the reader must not assume they match.
 */
@Serializable
data class XapkExpansion(
    @SerialName("file") val file: String,
    @SerialName("install_location") val installLocation: String = "EXTERNAL_STORAGE",
    @SerialName("install_path") val installPath: String
)
```

Add to `XapkManifest`, after `splitApks`:

```kotlin
    /**
     * Null, never an empty list.
     *
     * [xapkJson] sets `encodeDefaults = true`, so a defaulted `emptyList()` would write
     * `"expansions":[]` into every OBB-less `.xapk`. `explicitNulls = false` drops a null instead,
     * leaving bundles for apps without expansion files byte-for-byte as they were.
     */
    @SerialName("expansions") val expansions: List<XapkExpansion>? = null
```

Change the `.xapk` overload's signature to take `expansions: List<XapkExpansion> = emptyList()` as its last parameter and pass it through to `xapkManifest`. In `xapkManifest`, set `expansions = expansions.takeIf { it.isNotEmpty() }`.

- [ ] **Step 4: Add the reader side**

In `app/src/main/java/com/valhalla/thor/data/repository/BundleAnalysis.kt`, add next to `XapkSplitApkInfo`:

```kotlin
/**
 * Reader-side mirror of the writer's expansion descriptor.
 *
 * Both fields are nullable and default to null on purpose: this parses an archive someone else
 * built, and a missing key must not take the whole manifest — and with it the split list — down.
 * Validation of what these strings actually mean lives in `resolveExpansions`, not here.
 */
@Serializable
data class XapkExpansionInfo(
    @SerialName("file") val file: String? = null,
    @SerialName("install_path") val installPath: String? = null
)
```

and to `XapkManifestInfo`, after `splitApks`:

```kotlin
    @SerialName("expansions") val expansions: List<XapkExpansionInfo> = emptyList(),
```

(Place it before the closing paren of the constructor, keeping `baseApkFile()`/`splitApkFiles()` in the body unchanged. `bundleJson` already sets `ignoreUnknownKeys = true`, so old readers and new manifests stay compatible in both directions.)

- [ ] **Step 5: Run the test and watch it pass**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.util.ApksMetadataGeneratorTest"
```
Expected: PASS, including every pre-existing test in that class — the `.apks` path uses a different `Json` instance and a different manifest type and must be untouched.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/util/ApksMetadataGenerator.kt \
        app/src/main/java/com/valhalla/thor/data/repository/BundleAnalysis.kt \
        app/src/test/java/com/valhalla/thor/data/util/ApksMetadataGeneratorTest.kt
git commit -m "feat(obb): expansions in the XAPK manifest, both directions

Nullable, not a defaulted empty list: encodeDefaults would otherwise
write \"expansions\":[] into every OBB-less bundle. Refs #164."
```

---

## Task 4: The path convention — one definition, both directions

Everything about `Android/obb/<pkg>/<leaf>.obb` in one pure file, so the pack side and the install side cannot drift. This is also where the install-side hostile-input table lives (spec §6.1): the existing installer is deliberately flat and path-free — `isSafeEntryFileName` refuses anything containing a separator — and OBB extraction is the first code in Thor that writes to a path from an archive, which reintroduces zip-slip as a live risk class.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/repository/ObbExpansions.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/ObbExpansionsTest.kt`

**Interfaces:**
- Consumes: `ObbFile` (Task 1), `XapkExpansionInfo` (Task 3), `isUsablePackageName` (Task 1).
- Produces:
  - `internal fun expansionEntryName(packageName: String, leaf: String): String` — `"Android/obb/$packageName/$leaf"`
  - `internal fun isSafeObbLeafName(name: String): Boolean`
  - `internal data class ResolvedExpansion(val entryName: String, val leafName: String)`
  - `internal fun resolveExpansions(packageName: String, declared: List<XapkExpansionInfo>, entryNames: List<String>): List<ResolvedExpansion>` — the single validator; returns only entries safe to extract and place.
  - `internal data class BundleSpace(val internalBytes: Long, val externalBytes: Long)`
  - `internal fun bundleSpaceRequirement(apkBytes: Long, obbBytes: Long): BundleSpace`
  - `internal fun spaceShortfall(need: BundleSpace, internalFree: Long, externalFree: Long, sameVolume: Boolean): Long`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/data/repository/ObbExpansionsTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObbExpansionsTest {

    private val pkg = "com.example.game"

    private fun declared(vararg paths: String) =
        paths.map { XapkExpansionInfo(file = it, installPath = it) }

    @Test
    fun `the entry name is the install path, and both are the canonical OBB location`() {
        assertEquals(
            "Android/obb/com.example.game/main.12.com.example.game.obb",
            expansionEntryName(pkg, "main.12.com.example.game.obb")
        )
    }

    @Test
    fun `a well-formed manifest resolves`() {
        val resolved = resolveExpansions(
            packageName = pkg,
            declared = declared("Android/obb/com.example.game/main.obb"),
            entryNames = listOf("manifest.json", "base.apk", "Android/obb/com.example.game/main.obb")
        )

        assertEquals(
            listOf(
                ResolvedExpansion(
                    entryName = "Android/obb/com.example.game/main.obb",
                    leafName = "main.obb"
                )
            ),
            resolved
        )
    }

    @Test
    fun `a traversal in install_path is dropped`() {
        // The first code in Thor that writes to a path taken from an archive. Everything else in
        // the installer is flat by construction (isSafeEntryFileName refuses separators outright),
        // so zip-slip stops being theoretical here.
        val resolved = resolveExpansions(
            pkg,
            listOf(
                XapkExpansionInfo(
                    file = "Android/obb/com.example.game/main.obb",
                    installPath = "Android/obb/com.example.game/../../../data/local/tmp/evil.obb"
                )
            ),
            listOf("Android/obb/com.example.game/main.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `an install_path for a different package is dropped`() {
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.other.app/main.obb"),
            listOf("Android/obb/com.other.app/main.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `an absolute install_path is dropped`() {
        val resolved = resolveExpansions(
            pkg,
            declared("/sdcard/Android/obb/com.example.game/main.obb"),
            listOf("/sdcard/Android/obb/com.example.game/main.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a non-obb extension is dropped`() {
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.example.game/payload.so"),
            listOf("Android/obb/com.example.game/payload.so")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a nested install_path below the package dir is dropped`() {
        // The platform's own OBB layout is flat. Allowing depth here would mean creating
        // directories from archive-controlled names for no gain.
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.example.game/sub/main.obb"),
            listOf("Android/obb/com.example.game/sub/main.obb")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `a declared expansion with no matching zip entry is dropped`() {
        val resolved = resolveExpansions(
            pkg,
            declared("Android/obb/com.example.game/main.obb"),
            listOf("manifest.json", "base.apk")
        )

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `two expansions landing on the same leaf keep only the first`() {
        val resolved = resolveExpansions(
            pkg,
            listOf(
                XapkExpansionInfo("a/main.obb", "Android/obb/com.example.game/main.obb"),
                XapkExpansionInfo("b/main.obb", "Android/obb/com.example.game/main.obb")
            ),
            listOf("a/main.obb", "b/main.obb")
        )

        assertEquals(listOf("a/main.obb"), resolved.map { it.entryName })
    }

    @Test
    fun `an entry name may differ from the install path, because they are separate wire fields`() {
        val resolved = resolveExpansions(
            pkg,
            listOf(
                XapkExpansionInfo(
                    file = "obb/main.obb",
                    installPath = "Android/obb/com.example.game/main.obb"
                )
            ),
            listOf("obb/main.obb")
        )

        assertEquals(
            listOf(ResolvedExpansion("obb/main.obb", "main.obb")),
            resolved
        )
    }

    @Test
    fun `a manifest-free archive falls back to any depth-correct obb entry`() {
        // The reference installer does this, and APKPure archives in the wild omit the expansions
        // block while still carrying the files. Declaring nothing must not mean losing the data.
        val resolved = resolveExpansions(
            pkg,
            declared = emptyList(),
            entryNames = listOf(
                "manifest.json",
                "base.apk",
                "Android/obb/com.example.game/main.obb",
                "Android/obb/com.other.app/main.obb"
            )
        )

        assertEquals(
            listOf(ResolvedExpansion("Android/obb/com.example.game/main.obb", "main.obb")),
            resolved
        )
    }

    @Test
    fun `an unusable package name resolves nothing at all`() {
        assertTrue(
            resolveExpansions(
                "com.example.game; rm -rf /",
                declared("Android/obb/com.example.game/main.obb"),
                listOf("Android/obb/com.example.game/main.obb")
            ).isEmpty()
        )
    }

    @Test
    fun `leaf names that are not plain file names are refused`() {
        assertTrue(isSafeObbLeafName("main.12.com.example.game.obb"))
        assertFalse(isSafeObbLeafName(""))
        assertFalse(isSafeObbLeafName("."))
        assertFalse(isSafeObbLeafName(".."))
        assertFalse(isSafeObbLeafName("a/b.obb"))
        assertFalse(isSafeObbLeafName("a\\b.obb"))
        assertFalse(isSafeObbLeafName("main.obb "))
        assertFalse(isSafeObbLeafName("main.obb\n"))
        assertFalse(isSafeObbLeafName("main.txt"))
    }

    @Test
    fun `packing needs two copies of the apks but only one of the obb`() {
        // The APKs are copied out of /data/app into the staging dir and then deflated into the
        // final zip, so both exist at once. The OBB is streamed from external storage straight
        // into the zip, so only the zip's copy lands on internal storage... except that Thor
        // cannot read another package's OBB directly, so it stages there too.
        assertEquals(
            BundleSpace(internalBytes = 2 * 100L + 50L, externalBytes = 50L),
            bundleSpaceRequirement(apkBytes = 100L, obbBytes = 50L)
        )
    }

    @Test
    fun `no obb means no external requirement`() {
        assertEquals(
            BundleSpace(internalBytes = 200L, externalBytes = 0L),
            bundleSpaceRequirement(apkBytes = 100L, obbBytes = 0L)
        )
    }

    @Test
    fun `shortfall is zero when both volumes have room`() {
        val need = bundleSpaceRequirement(100L, 50L)

        assertEquals(0L, spaceShortfall(need, internalFree = 1000L, externalFree = 1000L, sameVolume = false))
    }

    @Test
    fun `shortfall reports the larger gap when both volumes are short`() {
        val need = BundleSpace(internalBytes = 1000L, externalBytes = 500L)

        assertEquals(
            900L,
            spaceShortfall(need, internalFree = 100L, externalFree = 400L, sameVolume = false)
        )
    }

    @Test
    fun `on a single-volume device the two requirements are summed, not maxed`() {
        // Most phones emulate external storage on the data partition, so "internal free" and
        // "external free" are the same bytes reported twice. Checking them independently there
        // passes a device that then runs out mid-copy.
        val need = BundleSpace(internalBytes = 600L, externalBytes = 600L)

        assertEquals(0L, spaceShortfall(need, 1000L, 1000L, sameVolume = false))
        assertEquals(200L, spaceShortfall(need, 1000L, 1000L, sameVolume = true))
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ObbExpansionsTest"
```
Expected: FAIL — `Unresolved reference: expansionEntryName`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/valhalla/thor/data/repository/ObbExpansions.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

/** The external-storage-relative directory the platform reserves for one package's expansions. */
internal fun expansionDirFor(packageName: String): String = "Android/obb/$packageName"

/**
 * Where an expansion file lives, both as a zip entry and as an install path.
 *
 * Thor writes the two equal. The reference installers assume that, and it means an archive Thor
 * produced is readable by an installer that ignores the manifest entirely and just looks for
 * `*.obb` entries.
 */
internal fun expansionEntryName(packageName: String, leaf: String): String =
    "${expansionDirFor(packageName)}/$leaf"

/**
 * A file name Thor is willing to create inside `Android/obb/<pkg>/`, or to hand to a root shell.
 *
 * Stricter than `isSafeEntryFileName` by two rules:
 *
 *  - **The `.obb` extension**, because that is what the platform's own expansion loader looks for,
 *    and it keeps a hostile archive from dropping an arbitrarily-typed file into a world-readable
 *    directory.
 *  - **No single quote.** This is a shell-injection guard, not tidiness. The leaf is interpolated
 *    into single-quoted `cp` commands that run as root, and it is untrusted in *both* directions:
 *    on the pack side it comes from `stat` on the target app's own `Android/obb/<pkg>/`, a
 *    directory that app can write to with no permission at all. So any installed app could pick a
 *    name like `main'; id > /sdcard/pwned; echo '.obb` and choose bytes Thor feeds to a root
 *    shell. Scoped to `'` only, matching `obbProbeCommand`'s reasoning — single-quoted, so the one
 *    character that breaks out is the quote itself. Interior spaces stay legal: `main 1.obb` is a
 *    real name and `ObbProbeParserTest` locks it as valid.
 *
 * The guard lives here rather than in each caller because this is the single definition both
 * directions share. If a call site ever switches to double quotes, fix this predicate — `"`,
 * `` ` `` and `$` would then all be live — rather than patching the call site.
 */
internal fun isSafeObbLeafName(name: String): Boolean =
    name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        !name.contains('/') &&
        !name.contains('\\') &&
        !name.contains('\'') &&
        name.none { it.isISOControl() } &&
        name.endsWith(".obb", ignoreCase = true)

/** One expansion cleared for extraction: which zip entry to read, and what to name the result. */
internal data class ResolvedExpansion(val entryName: String, val leafName: String)

/**
 * Decide which expansions in an archive are safe to extract and place.
 *
 * Both inputs are attacker-controlled — the manifest and the entry list come from a file the user
 * picked, possibly downloaded from anywhere. The rules, in order:
 *
 *  1. An unusable package name resolves nothing. The name is interpolated into a shell command
 *     downstream, and it is also the authority for rule 3.
 *  2. `install_path` must be exactly `Android/obb/<packageName>/<leaf>` — same package, depth 1,
 *     no traversal, not absolute. A `.xapk` may only write into its own package's OBB directory.
 *  3. `<leaf>` must satisfy [isSafeObbLeafName].
 *  4. The declared `file` must actually be an entry in the archive. A declaration with no entry is
 *     dropped rather than treated as an error, so a manifest listing an optional patch file that
 *     was not shipped still installs.
 *  5. Two declarations resolving to the same leaf keep the first. Later ones would silently
 *     overwrite the earlier extraction. The key is `leaf.lowercase()`, not `leaf`: the volumes
 *     this writes to — emulated external storage, FAT/exFAT — are case-insensitive, so `main.obb`
 *     and `MAIN.OBB` are one file, and a case-sensitive key lets the second declaration through to
 *     perform exactly the overwrite this rule exists to prevent. The no-arg `lowercase()` is
 *     locale-invariant, which is what a filesystem comparison wants.
 *  6. **Manifest-free fallback:** when nothing is declared, any entry already at
 *     `Android/obb/<packageName>/<leaf>.obb` is taken at its own path. APKPure archives in the
 *     wild omit the block while carrying the files, and the reference installer does exactly this.
 *     The fallback applies only when `declared` is empty — a manifest that declares *some*
 *     expansions is treated as authoritative about all of them.
 */
internal fun resolveExpansions(
    packageName: String,
    declared: List<XapkExpansionInfo>,
    entryNames: List<String>
): List<ResolvedExpansion> {
    if (!isUsablePackageName(packageName)) return emptyList()

    val prefix = "${expansionDirFor(packageName)}/"
    val present = entryNames.toSet()
    val seenLeaves = LinkedHashSet<String>()
    val out = mutableListOf<ResolvedExpansion>()

    fun accept(entryName: String, installPath: String) {
        if (!installPath.startsWith(prefix)) return
        val leaf = installPath.removePrefix(prefix)
        if (!isSafeObbLeafName(leaf)) return
        if (entryName !in present) return
        if (!seenLeaves.add(leaf.lowercase())) return
        out += ResolvedExpansion(entryName, leaf)
    }

    if (declared.isEmpty()) {
        entryNames.forEach { name -> accept(name, name) }
    } else {
        declared.forEach { info ->
            val entryName = info.file ?: return@forEach
            val installPath = info.installPath ?: return@forEach
            accept(entryName, installPath)
        }
    }
    return out
}

/**
 * Bytes a bundle build needs, split by the volume they land on.
 *
 * [internalBytes] is the peak on Thor's own cache directory: the APKs are copied out of `/data/app`
 * into a staging directory and then written into the final zip, so both copies coexist — plus one
 * copy of the OBB, which ends up inside that same zip.
 *
 * [externalBytes] is the peak in `externalCacheDir`, the only place Thor and the privileged shell
 * can both reach. Thor cannot open `Android/obb/<other-pkg>/` itself, so the shell copies each
 * expansion there first and Thor streams it into the zip from there.
 */
internal data class BundleSpace(val internalBytes: Long, val externalBytes: Long)

/** @see BundleSpace for why the APKs count twice and the OBB counts once per volume. */
internal fun bundleSpaceRequirement(apkBytes: Long, obbBytes: Long): BundleSpace =
    BundleSpace(internalBytes = 2 * apkBytes + obbBytes, externalBytes = obbBytes)

/**
 * How many bytes short the device is, or 0 when there is room.
 *
 * [sameVolume] must be true when internal and external storage are the same filesystem, which is
 * the normal case on a phone with no SD card: external storage is emulated on the data partition,
 * so the two free-space figures are the same bytes counted twice and the requirements add rather
 * than overlap. Checking them independently on such a device passes a build that then dies
 * halfway through the copy. Callers derive it by comparing `totalSpace` on the two directories.
 */
internal fun spaceShortfall(
    need: BundleSpace,
    internalFree: Long,
    externalFree: Long,
    sameVolume: Boolean
): Long = if (sameVolume) {
    (need.internalBytes + need.externalBytes - internalFree).coerceAtLeast(0L)
} else {
    maxOf(
        (need.internalBytes - internalFree).coerceAtLeast(0L),
        (need.externalBytes - externalFree).coerceAtLeast(0L)
    )
}
```

- [ ] **Step 4: Run the test and watch it pass**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ObbExpansionsTest"
```
Expected: `tests="18" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/repository/ObbExpansions.kt \
        app/src/test/java/com/valhalla/thor/data/repository/ObbExpansionsTest.kt
git commit -m "feat(obb): one path convention for both directions, plus the space math

resolveExpansions is the whole hostile-input surface: OBB extraction is
the first path write in an installer that is otherwise flat. Refs #164."
```

---

## Task 5: Zip entry names — teach the builder that a name is not a `File.name`

`zipFiles` currently derives every entry name from `file.name`, which is exactly right for the flat APK/sidecar layout and exactly wrong for `Android/obb/<pkg>/main.obb`. Splitting the entry name from the file is a small, self-contained change with its own test, kept separate from the OBB staging so a regression in either is unambiguous.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/AppBundleBuilderTest.kt` — **a new file.** `stagedApkNames`'s existing coverage lives in `StagedApkNamesTest.kt` and stays there; do not move it, and do not expect it to appear in this class's results.

**Interfaces:**
- Consumes: the existing private `zipFiles(files: List<File>, zipFile: File)`.
- Produces:
  - `internal data class ZipSource(val file: File, val entryName: String)`
  - `internal fun zipSourcesFor(format: BundleFormat, apkFiles: List<File>, sidecars: List<File>, expansions: List<ZipSource>): List<ZipSource>` — top-level and pure, beside `stagedApkNames`.
  - `private suspend fun zipFiles(sources: List<ZipSource>, zipFile: File)` — signature change, one call site.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/com/valhalla/thor/data/repository/AppBundleBuilderTest.kt`:

```kotlin
    @Test
    fun `xapk puts the sidecars first and the expansions last`() {
        // Sidecar-first is not cosmetic: an installer that streams the archive reads manifest.json
        // before it has to decide what to do with anything else. Expansions go last because they
        // are the largest entries and the least urgent to reach.
        val sources = zipSourcesFor(
            format = BundleFormat.XAPK,
            apkFiles = listOf(File("/tmp/base.apk"), File("/tmp/split_a.apk")),
            sidecars = listOf(File("/tmp/manifest.json"), File("/tmp/icon.png")),
            expansions = listOf(
                ZipSource(File("/tmp/staged/main.obb"), "Android/obb/com.example.game/main.obb")
            )
        )

        assertEquals(
            listOf(
                "manifest.json",
                "icon.png",
                "base.apk",
                "split_a.apk",
                "Android/obb/com.example.game/main.obb"
            ),
            sources.map { it.entryName }
        )
    }

    @Test
    fun `apks keeps apks first and carries no expansions`() {
        // .apks is SAI's format and has no expansion convention. Passing some in is a caller bug,
        // and dropping them beats writing entries no reader will look for.
        val sources = zipSourcesFor(
            format = BundleFormat.APKS,
            apkFiles = listOf(File("/tmp/base.apk")),
            sidecars = listOf(File("/tmp/meta.sai_v2.json")),
            expansions = listOf(
                ZipSource(File("/tmp/staged/main.obb"), "Android/obb/com.example.game/main.obb")
            )
        )

        assertEquals(listOf("base.apk", "meta.sai_v2.json"), sources.map { it.entryName })
    }

    @Test
    fun `a plain file keeps its own name as the entry name`() {
        val sources = zipSourcesFor(
            BundleFormat.XAPK,
            apkFiles = listOf(File("/tmp/staging/base.apk")),
            sidecars = emptyList(),
            expansions = emptyList()
        )

        assertEquals(listOf("base.apk"), sources.map { it.entryName })
        assertEquals(listOf(File("/tmp/staging/base.apk")), sources.map { it.file })
    }
```

- [ ] **Step 2: Run the test and watch it fail**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.AppBundleBuilderTest"
```
Expected: FAIL — `Unresolved reference: zipSourcesFor`.

- [ ] **Step 3: Add the model and the ordering helper**

In `app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt`, add at the bottom of the file next to `stagedApkNames`:

```kotlin
/**
 * A file plus the name it takes inside the zip.
 *
 * Until OBB support, every entry name was `file.name` and the archive was flat. An expansion has
 * to land at `Android/obb/<pkg>/<leaf>`, so the entry name stops being derivable from the file and
 * becomes a decision the caller makes.
 */
internal data class ZipSource(val file: File, val entryName: String)

/**
 * The entry order for one bundle.
 *
 * `.xapk` puts the sidecars first — an installer reading the archive as a stream reaches
 * `manifest.json` before anything large — and the expansions last, because they are the biggest
 * entries and nothing needs them early. `.apks` keeps its existing APKs-then-sidecars order, and
 * drops expansions entirely: that format has no expansion convention, and writing entries no
 * reader looks for would only inflate the file.
 */
internal fun zipSourcesFor(
    format: BundleFormat,
    apkFiles: List<File>,
    sidecars: List<File>,
    expansions: List<ZipSource>
): List<ZipSource> {
    val apks = apkFiles.map { ZipSource(it, it.name) }
    val extras = sidecars.map { ZipSource(it, it.name) }
    return if (format == BundleFormat.XAPK) extras + apks + expansions else apks + extras
}
```

- [ ] **Step 4: Change `zipFiles` to take sources**

Replace the `zipFiles` signature and its entry construction:

```kotlin
    private suspend fun zipFiles(sources: List<ZipSource>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            out.setLevel(Deflater.NO_COMPRESSION)
            val data = ByteArray(COPY_BUFFER_BYTES)
            sources.forEach { source ->
                FileInputStream(source.file).use { fi ->
                    BufferedInputStream(fi).use { origin ->
                        val entry = ZipEntry(source.entryName)
                        out.putNextEntry(entry)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val readBytes = origin.read(data)
                            if (readBytes == -1) break
                            out.write(data, 0, readBytes)
                        }
                    }
                }
            }
        }
    }
```

and update the single call site, which currently reads:

```kotlin
            val filesToZip = if (format == BundleFormat.XAPK) sidecars + apkFiles else apkFiles + sidecars
            zipFiles(filesToZip, finalFile)
```

to:

```kotlin
            zipFiles(zipSourcesFor(format, apkFiles, sidecars, expansions = emptyList()), finalFile)
```

The `emptyList()` is deliberate and temporary — Task 6 supplies the real list. Keeping this task to a pure refactor means a failure in the next task cannot be confused with a failure in this one.

- [ ] **Step 5: Run the test and watch it pass**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.AppBundleBuilderTest"
```
Expected: PASS. `stagedApkNames`'s own tests live in `StagedApkNamesTest.kt` and will not appear here — run the full suite if you want them confirmed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt \
        app/src/test/java/com/valhalla/thor/data/repository/AppBundleBuilderTest.kt
git commit -m "refactor(export): separate a zip entry's name from its file

Pure refactor with no behaviour change; entry names are still file.name
everywhere. Expansions need a name the file cannot supply. Refs #164."
```

---

## Task 6: Pack the OBB into the `.xapk`

The first half of GH#164. The builder probes, has the privileged shell copy each expansion into `externalCacheDir` (the only place both parties can reach), streams them into the zip and declares them in the manifest.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/AppBundleBuilderTest.kt`

**Interfaces:**
- Consumes: `SystemRepository.probeObb` (Task 2), `XapkExpansion` + the extended `generateManifestJson` (Task 3), `expansionEntryName`/`bundleSpaceRequirement`/`spaceShortfall`/`isSafeObbLeafName` (Task 4), `ZipSource`/`zipSourcesFor` (Task 5), `SystemRepository.executeShellCommand`.
- Produces:
  - `private suspend fun stageExpansions(packageName: String, files: List<ObbFile>, stagingDir: File): List<ZipSource>?`
  - `internal fun obbCopyCommand(externalStorageDir: String, packageName: String, leaf: String, destPath: String): String?`
  - `internal fun expansionDescriptors(sources: List<ZipSource>): List<XapkExpansion>`
  - No change to `AppBundleBuilder`'s public interface — `build` keeps its signature, so `ExportAppUseCase` and `ExportBottomSheet` are untouched by this task.

**Failure and cleanup rules for this task — read before writing any of it.** They are the reason the steps are shaped the way they are:

1. **Fail by `throw`, never by `return@withContext Result.failure(...)`.** `build`'s body is a `withContext` block wrapped in `try`/`catch`, and each catch deletes `cacheDir`. A `return@withContext` inside the `try` is a normal return: it does **not** run the catch, so it leaks the whole staging tree. Every failure this task introduces therefore throws `IOException`, exactly as the surrounding code throws `IllegalStateException("Failed to copy APK: $name")`, and the existing `catch (e: Exception)` turns it into `Result.failure(e)`.
2. **The expansion staging dir is outside everything the catch blocks currently wipe.** Staged copies must live in `externalCacheDir` (the shell cannot write into `/data/data/<thor>`, 0700), and `cacheDir.deleteRecursively()` does not reach it. Declare it *before* the `try` and delete it in the success path **and** in both catch blocks — otherwise a cancelled or failed export leaves multiple gigabytes in external cache until the next export of the same package, which may never happen.
3. Given rules 1 and 2, `stageExpansions` needs no cleanup of its own. Do not give it a `try`/`catch`: a `return null` out of a `map` lambda is a non-local return that would skip any cleanup written after the loop, which is precisely the bug that shape invites.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/com/valhalla/thor/data/repository/AppBundleBuilderTest.kt`:

```kotlin
    @Test
    fun `the copy command quotes both paths and refuses a hostile leaf`() {
        val command = obbCopyCommand(
            externalStorageDir = "/storage/emulated/0",
            packageName = "com.example.game",
            leaf = "main.12.com.example.game.obb",
            destPath = "/storage/emulated/0/Android/data/com.valhalla.thor/cache/obb/main.obb"
        )!!

        assertTrue(
            command,
            command.contains("'/storage/emulated/0/Android/obb/com.example.game/main.12.com.example.game.obb'")
        )
        assertTrue(
            command,
            command.contains("'/storage/emulated/0/Android/data/com.valhalla.thor/cache/obb/main.obb'")
        )

        assertNull(
            obbCopyCommand("/storage/emulated/0", "com.example.game", "../../evil.obb", "/tmp/x")
        )
        assertNull(
            obbCopyCommand("/storage/emulated/0", "com.example.game", "main.obb", "/tmp/it's")
        )
        assertNull(
            obbCopyCommand("/storage/emulated/0", "bad;name", "main.obb", "/tmp/x")
        )
    }

    @Test
    fun `expansions are declared with the entry name as the install path`() {
        // What a third-party installer reads. file == install_path is the shape the reference
        // installers assume, and it also means a manifest-blind installer that scans for *.obb
        // entries lands them in the right place by accident.
        val declared = expansionDescriptors(
            listOf(
                ZipSource(File("/tmp/a"), "Android/obb/com.example.game/main.obb"),
                ZipSource(File("/tmp/b"), "Android/obb/com.example.game/patch.obb")
            )
        )

        assertEquals(
            listOf(
                "Android/obb/com.example.game/main.obb",
                "Android/obb/com.example.game/patch.obb"
            ),
            declared.map { it.file }
        )
        assertEquals(declared.map { it.file }, declared.map { it.installPath })
        assertTrue(declared.all { it.installLocation == "EXTERNAL_STORAGE" })
    }
```

- [ ] **Step 2: Run the test and watch it fail**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.AppBundleBuilderTest"
```
Expected: FAIL — `Unresolved reference: obbCopyCommand`.

- [ ] **Step 3: Add the pure helpers**

At the bottom of `AppBundleBuilderImpl.kt`, next to `zipSourcesFor`:

```kotlin
/**
 * `cp` one expansion out of `Android/obb/<pkg>/` into a destination the app can read, or null when
 * any of the three interpolated strings is unsafe to put in a shell command.
 *
 * The destination is always inside Thor's own `externalCacheDir`, which is the single location the
 * privileged shell and Thor can both reach: the shell uid cannot enter `/data/data/com.valhalla.thor`
 * (0700), and Thor cannot open another package's `Android/obb`. `chmod 644` follows the copy
 * because a file the shell creates is owned by the shell, and Thor has to be able to delete it
 * afterwards.
 */
internal fun obbCopyCommand(
    externalStorageDir: String,
    packageName: String,
    leaf: String,
    destPath: String
): String? {
    if (!isUsablePackageName(packageName)) return null
    if (!isSafeObbLeafName(leaf)) return null
    if (externalStorageDir.isBlank() || !externalStorageDir.startsWith('/')) return null
    if (!destPath.startsWith('/')) return null
    // `leaf` is in this sum as well as the other two, even though isSafeObbLeafName already
    // rejects a quote. Defence in depth: this command runs as root, and the cost of the redundant
    // check is nothing next to the cost of the predicate ever being relaxed by someone who did not
    // read why it is strict.
    if ((externalStorageDir + destPath + leaf).any { it == '\'' || it == '\n' }) return null

    val source = "$externalStorageDir/${expansionDirFor(packageName)}/$leaf"
    return "cp -f '$source' '$destPath' && chmod 644 '$destPath'"
}

/**
 * Turn staged expansion sources into the manifest's `expansions` block.
 *
 * `file` and `install_path` are written equal — see [XapkExpansion] for why that is the compatible
 * choice rather than a shortcut.
 */
internal fun expansionDescriptors(
    sources: List<ZipSource>
): List<XapkExpansion> = sources.map { XapkExpansion(file = it.entryName, installPath = it.entryName) }
```

Add the imports `com.valhalla.thor.data.util.XapkExpansion`, `com.valhalla.thor.domain.model.ObbFile`, `com.valhalla.thor.domain.model.ObbProbe`, `android.os.Environment`, `android.text.format.Formatter` and `java.io.IOException`.

`expansionDescriptors` takes no `packageName`: the entry name already carries the package, and a parameter the body never reads is a warning this build treats as an error.

- [ ] **Step 4: Stage and pack**

First, declare the staging dir **before** the `try`, immediately after the existing `val cacheDir = ...` line (rule 2 above):

```kotlin
        // Not under cacheDir: the privileged shell that copies an expansion out of
        // Android/obb/<pkg>/ cannot write into /data/data/<thor> (0700), so the staged copies have
        // to land somewhere both parties can reach. That also puts them outside everything the
        // catch blocks below wipe, which is why this dir is deleted explicitly on all three exits —
        // a failed export of a 4 GB game would otherwise leave 4 GB in external cache until the
        // next export of the same package, which may never come.
        val obbStagingDir = context.externalCacheDir?.let {
            File(it, "obb_out/${appInfo.packageName}")
        }
```

Then, inside the zip branch, after `val totalApkSize = apkFiles.sumOf { it.length() }`:

```kotlin
                // Only .xapk carries expansions. The export sheet disables the .xapk chip on an
                // Undetermined probe, so in practice this format is not even selectable then — but
                // that gate is in the UI, and this is the only place that knows whether the bundle
                // it is about to write is complete. Treating Undetermined as "no expansions" would
                // make a lost privilege between rendering the chip and pressing Export produce a
                // silently OBB-less .xapk, which is GH#164 reached from a new direction. So it
                // fails instead, and the chip becomes defence in depth rather than the whole
                // defence. (`autoFor` never returns XAPK, so no caller reaches this by default.)
                val probe = if (format == BundleFormat.XAPK) {
                    systemRepository.probeObb(appInfo.packageName)
                } else {
                    ObbProbe.None
                }
                if (probe is ObbProbe.Undetermined) {
                    throw IOException(
                        "whether this app has game data could not be determined, so a .xapk " +
                            "might be incomplete"
                    )
                }
                val obbFiles = (probe as? ObbProbe.Present)?.files.orEmpty()

                val expansionSources = if (obbFiles.isEmpty()) {
                    emptyList()
                } else {
                    val externalCache = context.externalCacheDir
                    if (externalCache == null || obbStagingDir == null) {
                        throw IOException(
                            "external storage is unavailable, so the game data cannot be staged"
                        )
                    }
                    val shortfall = spaceShortfall(
                        need = bundleSpaceRequirement(
                            apkBytes = totalApkSize,
                            obbBytes = obbFiles.sumOf { it.sizeBytes }
                        ),
                        internalFree = cacheDir.usableSpace,
                        externalFree = externalCache.usableSpace,
                        // Same emulated volume on any phone without an SD card, in which case the
                        // two free-space figures are the same bytes counted twice.
                        sameVolume = cacheDir.totalSpace == externalCache.totalSpace
                    )
                    if (shortfall > 0L) {
                        throw IOException(
                            "not enough free space to pack this app's game data — about " +
                                "${Formatter.formatShortFileSize(context, shortfall)} more is needed"
                        )
                    }
                    stageExpansions(appInfo.packageName, obbFiles, obbStagingDir)
                        ?: throw IOException(
                            "this app's game data could not be read, so the .xapk would be incomplete"
                        )
                }
```

`usableSpace` is read from `externalCache` rather than from `obbStagingDir`, deliberately: `obbStagingDir` does not exist yet at that point, and `File.usableSpace` on a non-existent path returns 0, which would fail every export with a phantom shortfall.

Then, in the existing `.xapk` `generateManifestJson` call, pass the expansions and widen the size it reports — at that call site only, leaving `totalApkSize` itself alone because the space math above needs the APK figure on its own:

```kotlin
                    manifestFile.writeText(
                        apksMetadataGenerator.generateManifestJson(
                            appInfo,
                            totalApkSize + expansionSources.sumOf { it.file.length() },
                            iconFile?.name,
                            stagedApks,
                            expansionDescriptors(expansionSources)
                        )
                    )
```

Replace the zip call's `expansions = emptyList()` with `expansionSources`, and extend the success-path cleanup:

```kotlin
                tempSplitDir.deleteRecursively()
                obbStagingDir?.deleteRecursively()
```

Finally add `obbStagingDir?.deleteRecursively()` to **both** catch blocks, beside the existing `cacheDir.deleteRecursively()` in each. Extend the `CancellationException` catch's comment to say the staged expansions go with it.

Note the ordering constraint: `stageExpansions` must run **before** the manifest is generated, because the manifest declares what was staged. If staging fails, no manifest is written and no partial `.xapk` exists — the failure policy this feature was scoped around.

- [ ] **Step 5: Add the staging function**

Beside `copyFileSafely`:

```kotlin
    /**
     * Copy each expansion into a directory Thor can read, returning one [ZipSource] per file, or
     * null if any single copy failed.
     *
     * All-or-nothing on purpose. A `.xapk` missing one of a game's expansion files installs and
     * then fails at runtime in a way the user cannot diagnose — the exact complaint in GH#164 —
     * so a partial capture is a failed export, not a degraded one.
     *
     * Unlike [copyFileSafely] this never tries a direct read first: `Android/obb/<other-pkg>/` is
     * unreadable to Thor on every Android version this app supports, so an unprivileged attempt
     * is a guaranteed exception and a wasted syscall.
     *
     * Cleanup of [stagingDir] belongs to the caller, on every one of its exits — see the comment
     * where it is declared. Deliberately no `try`/`catch` here: `return null` inside the loop is a
     * non-local return, so anything written after the loop to tidy up would not run on the paths
     * that need it most.
     */
    private suspend fun stageExpansions(
        packageName: String,
        files: List<ObbFile>,
        stagingDir: File
    ): List<ZipSource>? {
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) return null

        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath.orEmpty()
        return files.map { obb ->
            val dest = File(stagingDir, obb.name)
            val command = obbCopyCommand(
                externalStorageDir = externalRoot,
                packageName = packageName,
                leaf = obb.name,
                destPath = dest.absolutePath
            ) ?: return null

            val result = systemRepository.executeShellCommand(command).getOrNull()
            if (result == null || result.first != 0) return null
            // The shell reported success; verify the bytes actually arrived. A `cp` that hits a
            // full volume can still exit 0 on some toybox builds, and a size that no longer
            // matches what the probe measured means the app rewrote the file underneath us —
            // either way the capture is not the one the manifest is about to describe.
            if (!dest.isFile || dest.length() != obb.sizeBytes) return null

            ZipSource(dest, expansionEntryName(packageName, obb.name))
        }
    }
```

`obb.name` reaches `File(stagingDir, obb.name)` before `obbCopyCommand` has vetted it — harmless only because [isSafeObbLeafName] is what makes the command non-null, and a name that would escape `stagingDir` returns null on the very next line without the file having been touched. Do not reorder those two.

- [ ] **Step 6: Run the tests and the build**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.AppBundleBuilderTest" && ./gradlew :app:compileFossDebugKotlin
```
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/repository/AppBundleBuilderImpl.kt \
        app/src/test/java/com/valhalla/thor/data/repository/AppBundleBuilderTest.kt
git commit -m "feat(export): pack OBB expansion files into .xapk

Closes the export half of #164. Staging goes through externalCacheDir,
the one place Thor and the privileged shell can both reach, and a
partial capture fails the export rather than shipping a broken bundle."
```

---

## Task 7: Extract expansions from an archive

The install-side reader. Expansions need their own byte budget: `MAX_EXTRACTED_TOTAL_BYTES` is 4 GiB for the APK set, and a single large game's OBB can approach that on its own, so reusing it would refuse legitimate archives.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/BundleZip.kt`
- Test: `app/src/test/java/com/valhalla/thor/data/repository/ObbExpansionZipTest.kt`

**Interfaces:**
- Consumes: `ResolvedExpansion` (Task 4), the existing `copyAtMostTo`, `InstallRefusedException`.
- Produces:
  - `internal const val MAX_EXPANSION_TOTAL_BYTES = 16L * 1024 * 1024 * 1024`
  - `internal const val MAX_EXPANSION_ENTRIES = 32`
  - `internal data class ExtractedExpansion(val file: File, val leafName: String)`
  - `internal fun extractExpansions(zip: File, expansions: List<ResolvedExpansion>, outDir: File, maxTotalBytes: Long = MAX_EXPANSION_TOTAL_BYTES, maxEntries: Int = MAX_EXPANSION_ENTRIES): List<ExtractedExpansion>`

**As shipped, three things this section did not specify** (commit `1dcc55d3`, after the review of the first implementation):

- **An entry-count cap, because the byte budget is not one.** A manifest-free `.xapk` has every `*.obb` entry treated as an expansion, so a million one-byte entries costs a million inodes and a million `cp` invocations while spending almost none of the 16 GiB. The check runs before `outDir` is created, so a refused archive leaves nothing behind at all.
- **A repeated leaf refuses** rather than overwriting. `resolveExpansions` drops repeats, so this only fires for a caller that built its list another way — but overwriting would replace the first file's bytes, return two entries pointing at one `File`, and charge the budget twice. Keyed on `lowercase()`: the staging volume is usually emulated or FAT.
- **`ExtractedExpansion` carries no digest and should not gain one.** `ExtractedApk.sha256` closes a real window — on API 28-29 an app with `WRITE_EXTERNAL_STORAGE` can overwrite a staged file in `externalCacheDir` between the write and the install. That window exists here too, but on exactly those versions `Android/obb/<pkg>/` is writable by that same app, so an attacker who could win the race can write the destination directly instead. From API 30 the staging dir is sandboxed and the race is gone. A digest would buy nothing and cost a second full read of gigabytes per install.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/valhalla/thor/data/repository/ObbExpansionZipTest.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ObbExpansionZipTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile("bundle-${entries.size}-${entries.hashCode()}.xapk")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `an expansion is extracted to its leaf name`() {
        val zip = zipOf(
            "base.apk" to ByteArray(4) { 1 },
            "Android/obb/com.example.game/main.obb" to ByteArray(64) { 7 }
        )
        val out = temp.newFolder("out")

        val extracted = extractExpansions(
            zip,
            listOf(ResolvedExpansion("Android/obb/com.example.game/main.obb", "main.obb")),
            out
        )

        assertEquals(listOf("main.obb"), extracted.map { it.leafName })
        assertEquals(64L, extracted.single().file.length())
        // Flat in the output directory — the nesting belongs to the destination on the device,
        // not to Thor's private staging area.
        assertEquals(out, extracted.single().file.parentFile)
    }

    @Test
    fun `nothing declared extracts nothing and does not fail`() {
        val zip = zipOf("base.apk" to ByteArray(4))
        val out = temp.newFolder("out")

        assertTrue(extractExpansions(zip, emptyList(), out).isEmpty())
    }

    @Test
    fun `a declared entry that vanished between resolve and extract refuses`() {
        // resolveExpansions checked the central directory; if it disagrees now, the archive is
        // being modified underneath us. Refuse rather than place a partial set.
        val zip = zipOf("base.apk" to ByteArray(4))
        val out = temp.newFolder("out")

        assertThrows(InstallRefusedException::class.java) {
            extractExpansions(
                zip,
                listOf(ResolvedExpansion("Android/obb/com.example.game/main.obb", "main.obb")),
                out
            )
        }
    }

    @Test
    fun `exceeding the total budget refuses and leaves nothing behind`() {
        val zip = zipOf(
            "Android/obb/com.example.game/a.obb" to ByteArray(64) { 1 },
            "Android/obb/com.example.game/b.obb" to ByteArray(64) { 2 }
        )
        val out = temp.newFolder("out")

        assertThrows(InstallRefusedException::class.java) {
            extractExpansions(
                zip,
                listOf(
                    ResolvedExpansion("Android/obb/com.example.game/a.obb", "a.obb"),
                    ResolvedExpansion("Android/obb/com.example.game/b.obb", "b.obb")
                ),
                out,
                maxTotalBytes = 100L
            )
        }

        // A refusal must not leave half a game's data in the staging directory for the next
        // install to trip over.
        assertEquals(emptyList<File>(), out.listFiles()?.toList().orEmpty())
    }

    @Test
    fun `the budget is the whole set, not per entry`() {
        val zip = zipOf(
            "Android/obb/com.example.game/a.obb" to ByteArray(64) { 1 },
            "Android/obb/com.example.game/b.obb" to ByteArray(64) { 2 }
        )
        val out = temp.newFolder("out")

        val extracted = extractExpansions(
            zip,
            listOf(
                ResolvedExpansion("Android/obb/com.example.game/a.obb", "a.obb"),
                ResolvedExpansion("Android/obb/com.example.game/b.obb", "b.obb")
            ),
            out,
            maxTotalBytes = 128L
        )

        assertEquals(2, extracted.size)
    }

    @Test
    fun `the expansion budget is far larger than the apk budget`() {
        // A single modern game's expansion set can approach the 4 GiB the APK set is capped at.
        // Sharing that cap would refuse archives that are entirely legitimate.
        assertTrue(MAX_EXPANSION_TOTAL_BYTES > MAX_EXTRACTED_TOTAL_BYTES)
    }

    @Test
    fun `an unsafe leaf refuses even if it somehow reached this far`() {
        // Defence in depth: resolveExpansions is the gate, but this function writes files and
        // must not depend on having been called correctly.
        val zip = zipOf("Android/obb/com.example.game/main.obb" to ByteArray(4))
        val out = temp.newFolder("out")

        assertThrows(InstallRefusedException::class.java) {
            extractExpansions(
                zip,
                listOf(ResolvedExpansion("Android/obb/com.example.game/main.obb", "../evil.obb")),
                out
            )
        }
        assertFalse(File(out.parentFile, "evil.obb").exists())
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ObbExpansionZipTest"
```
Expected: FAIL — `Unresolved reference: extractExpansions`.

- [ ] **Step 3: Implement it**

In `app/src/main/java/com/valhalla/thor/data/repository/BundleZip.kt`, add below `MAX_EXTRACTED_TOTAL_BYTES`:

```kotlin
/**
 * Ceiling on the total bytes of expansion files one archive may unpack.
 *
 * Separate from [MAX_EXTRACTED_TOTAL_BYTES], and much larger, because it bounds a different thing.
 * That constant caps a set of APKs, where 4 GiB is already absurd; a single game's expansion set
 * legitimately reaches gigabytes, so sharing the cap would reject real archives. This is a
 * zip-bomb backstop, not a policy about how big a game may be — the free-space check in
 * `ObbInstaller` is what actually protects the device.
 */
internal const val MAX_EXPANSION_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
```

and after `extractEntries`:

```kotlin
/** One expansion unpacked into Thor's staging directory, flat, named by its leaf. */
internal data class ExtractedExpansion(val file: File, val leafName: String)

/**
 * Unpack the resolved expansions into [outDir], flat.
 *
 * All-or-nothing, exactly like [extractEntries]: on any refusal every file written by this call is
 * deleted, because half a game's expansion data left in the staging directory would be picked up
 * as complete by the next attempt.
 *
 * The output is flat even though the destination is not. Building `Android/obb/<pkg>/` here would
 * mean creating directories from archive-supplied names inside Thor's own storage for no benefit —
 * [ObbInstaller] knows the destination and derives it from the *package being installed*, never
 * from the archive.
 *
 * Callers must pass expansions that came from `resolveExpansions`. The leaf is re-validated anyway;
 * this function writes files and does not get to assume it was called correctly.
 */
internal fun extractExpansions(
    zip: File,
    expansions: List<ResolvedExpansion>,
    outDir: File,
    maxTotalBytes: Long = MAX_EXPANSION_TOTAL_BYTES
): List<ExtractedExpansion> {
    if (expansions.isEmpty()) return emptyList()

    val written = mutableListOf<ExtractedExpansion>()

    fun refuse(message: String): Nothing {
        written.forEach { it.file.delete() }
        throw InstallRefusedException(message)
    }

    if (!outDir.isDirectory && !outDir.mkdirs()) {
        throw InstallRefusedException("this device would not let Thor create a staging directory for the game data.")
    }

    var budget = maxTotalBytes
    ZipFile(zip).use { archive ->
        expansions.forEach { expansion ->
            if (!isSafeObbLeafName(expansion.leafName)) {
                refuse("this archive names a game data file Thor will not create: ${expansion.leafName}")
            }
            val entry = archive.getEntry(expansion.entryName)
                ?: refuse("this archive lists game data it does not contain: ${expansion.entryName}")
            if (entry.isDirectory) {
                refuse("this archive lists a folder where a game data file should be: ${expansion.entryName}")
            }

            val dest = File(outDir, expansion.leafName)
            val copied = archive.getInputStream(entry).use { input ->
                FileOutputStream(dest).use { output -> input.copyAtMostTo(output, budget) }
            }
            if (copied == null) {
                dest.delete()
                refuse("this archive's game data is larger than Thor will unpack.")
            }
            budget -= copied
            written += ExtractedExpansion(dest, expansion.leafName)
        }
    }
    return written
}
```

Check the imports at the top of the file — `java.io.FileOutputStream` and `java.util.zip.ZipFile` are already there for `extractEntryTo`/`extractEntries`; add only what is missing.

- [ ] **Step 4: Run the test and watch it pass**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks --tests "com.valhalla.thor.data.repository.ObbExpansionZipTest"
```
Expected: `tests="7" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/repository/BundleZip.kt \
        app/src/test/java/com/valhalla/thor/data/repository/ObbExpansionZipTest.kt
git commit -m "feat(obb): extract expansion files from a .xapk

Own byte budget, 16 GiB: the 4 GiB APK cap would refuse real games.
All-or-nothing, so a refusal leaves no partial set behind. Refs #164."
```

---

## Task 8: Place the expansions on the device

The second half of GH#164. A dedicated `@Single` rather than more code in the 974-line `InstallerRepositoryImpl`, and the only class in the feature that writes outside app storage.

`SystemRepositoryImpl` injects only the three gateways, `PreferenceRepository`, `StorageStatsProvider` and dispatchers — no `InstallerRepository` — so `ObbInstaller(context, systemRepository, ioDispatcher)` injected into `InstallerRepositoryImpl` introduces no Koin cycle. Verify that by building, since `strictSafety` makes a cycle a build failure rather than a runtime one.

**Files:**
- Create: `app/src/main/java/com/valhalla/thor/data/repository/ObbInstaller.kt`
- Modify: `app/src/main/java/com/valhalla/thor/data/repository/InstallerRepositoryImpl.kt`

**Interfaces:**
- Consumes: `SystemRepository.executeShellCommand`, `resolveExpansions`/`expansionDirFor`/`isSafeObbLeafName` (Task 4), `extractExpansions`/`ExtractedExpansion` (Task 7), `parseXapkManifest`/`XapkManifestInfo.expansions` (Task 3), `BundleZip.read`/`entryNames`.
- Produces:
  - `sealed interface ObbPlacement { data object NotNeeded; data class Placed(val count: Int); data class Failed(val reason: String) }`
  - `@Single class ObbInstaller(context, systemRepository, @Named("io") ioDispatcher)`
  - `suspend fun ObbInstaller.refusalReason(bundle: File, packageName: String): String?` — called **before** any install; non-null means do not install.
  - `suspend fun ObbInstaller.place(bundle: File, packageName: String): ObbPlacement` — called after a verified-successful install.

- [ ] **Step 1: Create the installer**

Create `app/src/main/java/com/valhalla/thor/data/repository/ObbInstaller.kt`:

```kotlin
// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.content.Context
import android.os.Environment
import com.valhalla.thor.domain.repository.SystemRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.File

/** What happened to an archive's game data. */
sealed interface ObbPlacement {

    /** The archive declared no expansions, so there was nothing to do. */
    data object NotNeeded : ObbPlacement

    /** [count] expansion files are now in `Android/obb/<pkg>/`. */
    data class Placed(val count: Int) : ObbPlacement

    /**
     * The app installed but its game data did not land.
     *
     * Reported to the user rather than swallowed: an installed game that crashes on first launch
     * with no explanation is the failure mode GH#164 describes, and silence here would reproduce
     * it from the other direction.
     */
    data class Failed(val reason: String) : ObbPlacement
}

/**
 * Places a `.xapk`'s expansion files into `Android/obb/<pkg>/`.
 *
 * Split out of `InstallerRepositoryImpl` because it is the only code in the install path that
 * writes outside app storage, and it is easier to reason about with that boundary visible.
 *
 * Two entry points, and the order matters. [refusalReason] runs **before** anything is installed,
 * so an archive whose game data cannot be placed does not leave a half-installed game behind;
 * [place] runs after the install is confirmed, because the destination directory's ownership is
 * synthesised from the installed package and the platform is entitled to wipe
 * `Android/obb/<pkg>/` when that package is (re)installed.
 */
@Single
class ObbInstaller(
    private val context: Context,
    private val systemRepository: SystemRepository,
    @Named("io") private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Why this archive must not be installed, or null when it is fine to proceed.
     *
     * Only ever non-null when the archive actually carries expansions. An ordinary `.apks` or a
     * `.xapk` without game data is never blocked by this feature.
     */
    suspend fun refusalReason(bundle: File, packageName: String): String? =
        withContext(ioDispatcher) {
            val expansions = declaredExpansions(bundle, packageName)
            if (expansions.isEmpty()) return@withContext null

            if (!canWriteObb()) {
                return@withContext "this file carries game data, and the current access mode " +
                    "cannot write to the game data folder. Installing it would produce a game " +
                    "that starts and then fails. Switch to root or Shizuku and try again."
            }

            val externalRoot = Environment.getExternalStorageDirectory()
            if (externalRoot == null || externalRoot.usableSpace <= 0L) {
                return@withContext "this device's shared storage is unavailable, so the game " +
                    "data in this file cannot be placed."
            }
            null
        }

    /**
     * Unpack the archive's expansions and move them into place. Call only after the package is
     * confirmed installed.
     *
     * The destination path is built from [packageName] — the package that was just installed —
     * never from the archive. `resolveExpansions` also rejects any `install_path` naming a
     * different package, so a `.xapk` cannot write into another app's game data folder even if
     * its manifest asks to.
     *
     * The destination directory is not cleared first. A user reinstalling a game over an existing
     * copy keeps whatever the archive does not replace; deleting the directory would throw away
     * data an already-installed game depends on to satisfy a tidiness nobody asked for.
     */
    suspend fun place(bundle: File, packageName: String): ObbPlacement = withContext(ioDispatcher) {
        val resolved = declaredExpansions(bundle, packageName)
        if (resolved.isEmpty()) return@withContext ObbPlacement.NotNeeded

        val externalCache = context.externalCacheDir
            ?: return@withContext ObbPlacement.Failed("shared storage is unavailable")
        val staging = File(externalCache, "obb_in/$packageName")
        staging.deleteRecursively()

        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath
            ?: return@withContext ObbPlacement.Failed("shared storage is unavailable")
        val destDir = "$externalRoot/${expansionDirFor(packageName)}"

        try {
            val extracted = extractExpansions(bundle, resolved, staging)
            val totalBytes = extracted.sumOf { it.file.length() }
            if (totalBytes > 0 && File(externalRoot).usableSpace < totalBytes) {
                return@withContext ObbPlacement.Failed("there is not enough free space for the game data")
            }

            val mkdir = systemRepository.executeShellCommand("mkdir -p '$destDir'").getOrNull()
            if (mkdir == null || mkdir.first != 0) {
                return@withContext ObbPlacement.Failed("the game data folder could not be created")
            }

            extracted.forEach { item ->
                if (!isSafeObbLeafName(item.leafName)) {
                    return@withContext ObbPlacement.Failed("the archive names a file Thor will not create")
                }
                val source = item.file.absolutePath
                val dest = "$destDir/${item.leafName}"
                if ((source + dest).any { it == '\'' || it == '\n' }) {
                    return@withContext ObbPlacement.Failed("the game data path is not usable on this device")
                }
                // 644, not the shell's default: files the shell creates are owned by the shell,
                // and the installed game reads them as its own uid.
                val move = systemRepository
                    .executeShellCommand("cp -f '$source' '$dest' && chmod 644 '$dest'")
                    .getOrNull()
                if (move == null || move.first != 0) {
                    return@withContext ObbPlacement.Failed("${item.leafName} could not be copied into place")
                }
            }
            ObbPlacement.Placed(extracted.size)
        } catch (e: InstallRefusedException) {
            ObbPlacement.Failed(e.message ?: "the game data in this file could not be unpacked")
        } catch (e: Exception) {
            ObbPlacement.Failed(e.message ?: "the game data in this file could not be unpacked")
        } finally {
            staging.deleteRecursively()
        }
    }

    /** The archive's expansions, already validated against [packageName]. Empty for a plain APK. */
    private fun declaredExpansions(bundle: File, packageName: String): List<ResolvedExpansion> =
        try {
            val contents = BundleZip.read(bundle, setOf("manifest.json"))
            val manifest = contents.bytes["manifest.json"]
                ?.let { parseXapkManifest(it.decodeToString()) }
            resolveExpansions(
                packageName = packageName,
                declared = manifest?.expansions.orEmpty(),
                entryNames = BundleZip.entryNames(bundle)
            )
        } catch (_: Exception) {
            // An unreadable archive is not this class's problem to report — the install path
            // ahead will fail on it with a better message. Claiming "no expansions" here only
            // means this feature adds nothing to that failure.
            emptyList()
        }

    /**
     * Whether the active privilege can write into `Android/obb`.
     *
     * Same test the export-side probe uses, and for the same reason: it must be the *privileged
     * surface* answering, not a `File` API that returns false for reasons unrelated to privilege.
     */
    private suspend fun canWriteObb(): Boolean {
        val root = Environment.getExternalStorageDirectory()?.absolutePath ?: return false
        if (root.any { it == '\'' || it == '\n' }) return false
        val result = systemRepository
            .executeShellCommand("ls -1 '$root/Android/obb' >/dev/null 2>&1 && echo THOR_OK")
            .getOrNull() ?: return false
        return result.first == 0 && result.second?.contains("THOR_OK") == true
    }
}
```

- [ ] **Step 2: Wire it into the install path**

In `InstallerRepositoryImpl`, add `private val obbInstaller: ObbInstaller` to the constructor (Koin resolves it by scan; no module change).

At the top of `installPackage`, inside the existing `withContext(ioDispatcher) { try {` and **before** the `when (mode)`:

```kotlin
            // Refuse before installing, not after. An archive whose game data cannot be placed
            // would otherwise leave an installed game that starts and immediately fails — the
            // same broken outcome #164 reports, arrived at from the other direction.
            val packageName = staged.file.let { resolvePackageNameForObb(it) }
            if (packageName != null) {
                obbInstaller.refusalReason(staged.file, packageName)?.let { reason ->
                    eventBus.emit(InstallState.Error(UiText.DynamicString(reason)))
                    return@withContext
                }
            }
```

After the `when (mode)` block, still inside the `try`:

```kotlin
            // The install rungs emit InstallState.Success themselves and do not reliably throw on
            // failure, so "did it install?" is answered by asking the package manager rather than
            // by the absence of an exception.
            if (packageName != null && isInstalled(packageName)) {
                when (val placement = obbInstaller.place(staged.file, packageName)) {
                    is ObbPlacement.Failed -> eventBus.emit(
                        InstallState.Error(
                            UiText.DynamicString(
                                "${staged.displayName ?: packageName} installed, but its game " +
                                    "data could not be placed: ${placement.reason}"
                            )
                        )
                    )
                    is ObbPlacement.Placed, ObbPlacement.NotNeeded -> Unit
                }
            }
```

> **As shipped, this gate is wrong and was corrected (commit `70a9cd00`).** `isInstalled(packageName)`
> immediately after the `when (mode)` is not a completed-install check. Only the `pm`-based rungs
> finish synchronously; `performPackageInstallerInstall` ends at `session.commit()`, which returns
> **before** the platform has installed anything — the outcome arrives later as a broadcast to
> `InstallReceiver`. So for a *first-time* install through a session the condition is `false`, the
> `place()` call never runs, and the OBB is dropped **in silence**: the exact failure #164 reports,
> reintroduced on a different path. It is reachable today, not hypothetically — Shizuku's shell rung
> failing falls through to a session.
>
> The shipped shape:
>
> ```kotlin
>                 // EXTERNAL is excluded because nothing has been installed yet on that path.
>                 // carriesExpansions() comes first so an archive with no game data pays one
>                 // central-directory read and nothing else — in particular, never the wait below.
>                 if (mode != InstallMode.EXTERNAL && packageName != null &&
>                     obbInstaller.carriesExpansions(staged.file, packageName)
>                 ) {
>                     val name = staged.displayName ?: packageName
>                     if (!awaitInstalled(packageName)) { /* stated error, see below */ }
>                     else when (val placement = obbInstaller.place(staged.file, packageName)) { … }
>                 }
> ```
>
> with `ObbInstaller.carriesExpansions(bundle, packageName): Boolean` (a `withContext(ioDispatcher)`
> wrapper over the private `declaredExpansions`) and, in the repository:
>
> ```kotlin
>     private suspend fun awaitInstalled(packageName: String): Boolean {
>         if (isInstalled(packageName)) return true
>         return withTimeoutOrNull(OBB_INSTALL_WAIT_MS) {
>             while (!isInstalled(packageName)) delay(OBB_INSTALL_POLL_MS)
>             true
>         } == true
>     }
>     // file-level: OBB_INSTALL_WAIT_MS = 90_000L, OBB_INSTALL_POLL_MS = 250L
> ```
>
> Three things that shape are deliberately not: it is **not** a receiver handoff (`InstallerViewModel.onCleared()`
> discards the staged bundle, so the file may be gone by the time the broadcast lands), it is **not**
> event-bus coupling (the bus is `replay = 1` and app-scoped, so a stale Success would satisfy the
> wait), and the timeout does **not** fall through to placing anyway — a rung that fell through to the
> default installer may still be sitting behind the system's own confirmation dialog, so the wait ends
> in a stated error:
> *"Thor could not confirm <name> finished installing, so its game data was not placed. Install it
> again to place the game data."*
>
> `awaitInstalled` is not JVM-unit-testable (it reads a real `PackageManager`), so it carries a device
> check in Task 12 instead: *Shizuku shell rung fails → fallback session → OBB still placed.*

and add the two helpers:

```kotlin
    /** The package an archive installs, from its own manifest — null when it cannot be read. */
    private fun resolvePackageNameForObb(bundle: File): String? = try {
        BundleZip.read(bundle, setOf("manifest.json")).bytes["manifest.json"]
            ?.let { parseXapkManifest(it.decodeToString()) }
            ?.packageName
            ?.takeIf { isUsablePackageName(it) }
    } catch (_: Exception) {
        null
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Exception) {
        false
    }
```

- [ ] **Step 3: Build**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && ./gradlew :app:compileFossDebugKotlin
```
Expected: BUILD SUCCESSFUL. A Koin cycle — the one real risk in this task — fails here rather than at runtime, because `compileSafety`/`strictSafety`/`unsafeDslChecks` are all on.

- [ ] **Step 4: Run the whole suite, since this file has broad coverage**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks
```
Then take the counts from the XML, not the log:
```
cd /Users/trinadhthatakula/StudioProjects/Thor && grep -h -o 'failures="[0-9]*"' app/build/test-results/testFossDebugUnitTest/*.xml | sort | uniq -c
```
Expected: every file reports `failures="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/data/repository/ObbInstaller.kt \
        app/src/main/java/com/valhalla/thor/data/repository/InstallerRepositoryImpl.kt
git commit -m "feat(install): place a .xapk's OBB files into Android/obb

Closes the install half of #164. Refuse-before-install so an
unplaceable archive never leaves a half-working game; the destination
comes from the installed package, never from the archive."
```

---

## Task 9: Strings — retract the false claim, in all five locales

`export_explain_xapk` currently tells users that "Android 11 and later stops any app from reading another app's OBB folder". That is true of an ordinary app and false of Thor, which reads protected paths through a privileged shell every time it exports a split APK. It is the only place a user is told the game data is missing, and after this feature it is simply wrong.

Six new strings and one rewrite, ×5 locales. Lint is fatal and `MissingTranslation`/`ExtraTranslation`/`UnusedResources` all bite, so every string added here must appear in all five files **and** be referenced by Tasks 10–11.

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-ar/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/res/values-fr/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

**Interfaces:**
- Produces: `R.string.export_xapk_unavailable`, `R.string.export_obb_included` (`%1$s` = formatted size), `R.string.export_obb_partial`, `R.string.info_obb_present` (`%1$s` = path, `%2$s` = size), `R.string.info_obb_none`, `R.string.info_obb_unknown`; rewritten `R.string.export_explain_xapk`.
- Consumed by: Task 10 (the first three) and Task 11 (the last three).

**Deliberately no `<plurals>`.** `otherEntryCount` is never rendered as a number — `export_obb_partial` says "some items" — because Arabic requires six plural forms and a count adds nothing the user acts on.

- [ ] **Step 1: Rewrite the English claim and add the six new strings**

In `app/src/main/res/values/strings.xml`, replace `export_explain_xapk` (line ~198) with:

```xml
    <string name="export_explain_xapk">Thor packs this app\'s parts into a single .xapk in the folder below, a format other installers understand too. Game data (OBB files) is included when Thor\'s current access mode can read it.</string>
    <string name="export_xapk_unavailable">.xapk needs root or Shizuku so Thor can read this app\'s game data. The current access mode can\'t, and a .xapk without it would install a game that doesn\'t run.</string>
    <string name="export_obb_included">Includes %1$s of game data.</string>
    <string name="export_obb_partial">Some items in this app\'s game data folder aren\'t expansion files and won\'t be included.</string>
```

and next to `info_obb_dir` (line ~486):

```xml
    <string name="info_obb_present">%1$s — %2$s of game data</string>
    <string name="info_obb_none">No game data</string>
    <string name="info_obb_unknown">Can\'t check without root or Shizuku</string>
```

- [ ] **Step 2: Arabic**

In `app/src/main/res/values-ar/strings.xml`, replace `export_explain_xapk` (line ~535) and add the rest:

```xml
    <string name="export_explain_xapk">يحزم Thor أجزاء هذا التطبيق في ملف ‎.xapk واحد داخل المجلد أدناه، وهي صيغة تفهمها برامج التثبيت الأخرى أيضًا. تُضمَّن بيانات اللعبة (ملفات OBB) عندما يستطيع وضع الوصول الحالي قراءتها.</string>
    <string name="export_xapk_unavailable">تحتاج صيغة ‎.xapk إلى الروت أو Shizuku ليتمكن Thor من قراءة بيانات اللعبة. وضع الوصول الحالي لا يستطيع ذلك، وملف ‎.xapk بدونها سيثبّت لعبة لا تعمل.</string>
    <string name="export_obb_included">يتضمن %1$s من بيانات اللعبة.</string>
    <string name="export_obb_partial">بعض العناصر في مجلد بيانات هذا التطبيق ليست ملفات توسعة ولن تُضمَّن.</string>
    <string name="info_obb_present">%1$s — %2$s من بيانات اللعبة</string>
    <string name="info_obb_none">لا توجد بيانات لعبة</string>
    <string name="info_obb_unknown">لا يمكن التحقق بدون الروت أو Shizuku</string>
```

- [ ] **Step 3: Spanish**

In `app/src/main/res/values-es/strings.xml` (replace at line ~501, add the rest):

```xml
    <string name="export_explain_xapk">Thor empaqueta las partes de esta app en un único .xapk en la carpeta de abajo, un formato que otros instaladores también entienden. Los datos del juego (archivos OBB) se incluyen cuando el modo de acceso actual de Thor puede leerlos.</string>
    <string name="export_xapk_unavailable">.xapk necesita root o Shizuku para que Thor pueda leer los datos del juego. El modo de acceso actual no puede, y un .xapk sin ellos instalaría un juego que no funciona.</string>
    <string name="export_obb_included">Incluye %1$s de datos del juego.</string>
    <string name="export_obb_partial">Algunos elementos de la carpeta de datos de esta app no son archivos de expansión y no se incluirán.</string>
    <string name="info_obb_present">%1$s: %2$s de datos del juego</string>
    <string name="info_obb_none">Sin datos de juego</string>
    <string name="info_obb_unknown">No se puede comprobar sin root ni Shizuku</string>
```

- [ ] **Step 4: French**

In `app/src/main/res/values-fr/strings.xml` (replace at line ~501, add the rest). Note every apostrophe is escaped — an unescaped one is a build failure, not a typo:

```xml
    <string name="export_explain_xapk">Thor regroupe les parties de cette app dans un seul .xapk dans le dossier ci-dessous, un format que d\'autres installeurs comprennent aussi. Les données de jeu (fichiers OBB) sont incluses lorsque le mode d\'accès actuel de Thor peut les lire.</string>
    <string name="export_xapk_unavailable">Le format .xapk nécessite root ou Shizuku pour que Thor puisse lire les données de jeu. Le mode d\'accès actuel ne le permet pas, et un .xapk sans elles installerait un jeu qui ne fonctionne pas.</string>
    <string name="export_obb_included">Inclut %1$s de données de jeu.</string>
    <string name="export_obb_partial">Certains éléments du dossier de données de cette app ne sont pas des fichiers d\'extension et ne seront pas inclus.</string>
    <string name="info_obb_present">%1$s — %2$s de données de jeu</string>
    <string name="info_obb_none">Aucune donnée de jeu</string>
    <string name="info_obb_unknown">Vérification impossible sans root ni Shizuku</string>
```

- [ ] **Step 5: Simplified Chinese**

In `app/src/main/res/values-zh-rCN/strings.xml` (replace at line ~493, add the rest):

```xml
    <string name="export_explain_xapk">Thor 会把此应用的各个部分打包成下方文件夹中的单个 .xapk，其他安装器也能识别这种格式。当 Thor 当前的访问方式可以读取时，游戏数据（OBB 文件）会一并打包。</string>
    <string name="export_xapk_unavailable">.xapk 需要 Root 或 Shizuku，Thor 才能读取该应用的游戏数据。当前访问方式无法读取，缺少游戏数据的 .xapk 装上后游戏也无法运行。</string>
    <string name="export_obb_included">包含 %1$s 游戏数据。</string>
    <string name="export_obb_partial">此应用游戏数据文件夹中有些内容不是扩展文件，不会被打包。</string>
    <string name="info_obb_present">%1$s — %2$s 游戏数据</string>
    <string name="info_obb_none">无游戏数据</string>
    <string name="info_obb_unknown">没有 Root 或 Shizuku 无法检查</string>
```

- [ ] **Step 6: Check that all five files agree**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && for f in values values-ar values-es values-fr values-zh-rCN; do printf '%s: ' "$f"; grep -c -E 'name="(export_xapk_unavailable|export_obb_included|export_obb_partial|info_obb_present|info_obb_none|info_obb_unknown)"' "app/src/main/res/$f/strings.xml"; done
```
Expected: `6` for every locale. Then confirm the retraction actually landed everywhere:
```
cd /Users/trinadhthatakula/StudioProjects/Thor && grep -rn 'Android 11' app/src/main/res/values*/strings.xml
```
Expected: no output. Any hit is a locale still carrying the false claim.

- [ ] **Step 7: Commit**

Lint is not run here — the new strings are unreferenced until Tasks 10 and 11, and `UnusedResources` is fatal. It runs in Task 12.

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-ar/strings.xml \
        app/src/main/res/values-es/strings.xml app/src/main/res/values-fr/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml
git commit -m "i18n(export): retract the OBB claim and add the new copy, all 5 locales

\"Android 11 stops any app reading another app's OBB folder\" is true of
an ordinary app and false of Thor, which reads protected paths through
a privileged shell on every split export. Refs #164."
```

---

## Task 10: The export sheet — a disabled chip with a stated reason

The user's chosen failure policy is "only offer .xapk when the OBB is capturable". Implemented as a chip that stays visible but disabled, with the reason shown — a chip that silently vanishes leaves the user with no way to find out why.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt`

**Interfaces:**
- Consumes: `SystemRepository.probeObb` (Task 2), `ObbProbe`, the strings from Task 9.
- Produces: no new public API. `ExportBottomSheet(appInfo, onDismiss)` keeps its signature, so `AppInfoDetailsScreen` is untouched.

- [ ] **Step 1: Probe when the sheet opens**

The sheet already documents itself as self-contained and injects its own dependencies, so this follows the existing pattern rather than pushing state into a caller. Beside the existing `koinInject` calls:

```kotlin
    val systemRepository = koinInject<SystemRepository>()
    // null while the probe is in flight — distinct from ObbProbe.None, which is an answer.
    var obbProbe by remember(appInfo.packageName) { mutableStateOf<ObbProbe?>(null) }

    LaunchedEffect(appInfo.packageName) {
        obbProbe = systemRepository.probeObb(appInfo.packageName)
    }
```

Extend the existing `LaunchedEffect(Unit) { targetLabel = ... }` only if it is convenient; a separate effect keyed on the package name is correct here because the sheet is reused across apps.

- [ ] **Step 2: Gate the chip**

Replace the chip's `enabled` and add a reason line. The chip currently reads `enabled = !exporting`; make it:

```kotlin
                    // `is Undetermined` is false while obbProbe is still null, so the chip stays
                    // enabled for the length of the probe rather than flickering disabled and back.
                    // A selection made in that window is re-checked by the builder, which fails the
                    // export rather than writing an incomplete bundle.
                    val xapkBlocked = option == BundleFormat.XAPK && obbProbe is ObbProbe.Undetermined
                    FilterChip(
                        selected = option == format,
                        onClick = { format = option },
                        // Disabled, not hidden. Under the "only offer .xapk when the OBB is
                        // capturable" policy a vanishing chip would leave the user with no way to
                        // learn why the format they came for is missing.
                        enabled = !exporting && !xapkBlocked,
                        // A file extension, not copy — the same token in every locale, so it is
                        // built from BundleFormat rather than from a translated string.
                        label = { Text(".${option.extension}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
```

Add, immediately after the chip row:

The reason always has a chip to explain: `formatOptions` is `listOf(BundleFormat.autoFor(appInfo), BundleFormat.XAPK)` and `autoFor` returns only `APK` or `APKS`, so `XAPK` is always offered and `formatOptions.first()` is never `XAPK` — which is also what makes the fallback below terminate.

```kotlin
                // Sits under the chip row, not under the explain text, because it explains why a
                // chip the user can see cannot be pressed.
                if (obbProbe is ObbProbe.Undetermined) {
                    Text(
                        text = stringResource(R.string.export_xapk_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
```

If `format` was already `XAPK` when the probe comes back `Undetermined`, fall back:

```kotlin
    LaunchedEffect(obbProbe) {
        if (obbProbe is ObbProbe.Undetermined && format == BundleFormat.XAPK) {
            format = formatOptions.first()
        }
    }
```

- [ ] **Step 3: Say what will be included**

The `when (format)` block at ~:255 is preceded by a comment reading *"For .xapk this is the only place the user is told the OBB assets are left out, so it has to follow the selection."* That comment now describes the opposite of what the code does — update it to:

```kotlin
            // Plain-language explanation of the selected format, plus what the .xapk will actually
            // carry. This is the only place the user learns whether their game data is going in,
            // so it has to follow the selection rather than sit above it.
```

Then, below the `Text` that renders the explain string, add:

```kotlin
        val present = obbProbe as? ObbProbe.Present
        if (format == BundleFormat.XAPK && present != null) {
            val totalObbBytes = present.files.sumOf { it.sizeBytes }
            if (totalObbBytes > 0) {
                Text(
                    text = stringResource(
                        R.string.export_obb_included,
                        Formatter.formatShortFileSize(LocalContext.current, totalObbBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (present.otherEntryCount > 0) {
                // Not a refusal. The format cannot carry anything but .obb files, so a bundle
                // without those extras is complete by the format's own definition — the user is
                // told, and decides.
                Text(
                    text = stringResource(R.string.export_obb_partial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
```

Add the imports: `android.text.format.Formatter`, `androidx.compose.ui.platform.LocalContext`, `com.valhalla.thor.domain.model.ObbProbe`, `com.valhalla.thor.domain.repository.SystemRepository`.

- [ ] **Step 4: Build**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && ./gradlew :app:compileFossDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/appList/ExportBottomSheet.kt
git commit -m "feat(export): gate the .xapk chip on whether the OBB is readable

Disabled with a stated reason rather than hidden — the whole point of
the policy is that the user finds out why. Refs #164."
```

---

## Task 11: The app-info OBB card — ask the probe, not the filesystem

`AppInfoDetailsScreen` renders the OBB card from `appInfo.obbFilePath`, which `AppInfoMapper` computes with `File(...).exists()`. On Android 11+ that returns false for another package's OBB directory whether or not one exists, so today the card is absent for every game on a modern device. The probe answers correctly.

**Files:**
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsViewModel.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsScreen.kt`
- Modify: `app/src/main/java/com/valhalla/thor/presentation/widgets/AppInfoSheet.kt` — *added in review; see the note after Step 3*
- Modify: `app/src/test/java/com/valhalla/thor/presentation/appList/AppInfoDetailsViewModelTest.kt` — *ditto*

**Interfaces:**
- Consumes: `SystemRepository.probeObb` (already injected into the view model), the strings from Task 9.
- Produces: `AppInfoDetailsUiState.obbProbe: ObbProbe? = null`; `AppInfoDetailBody(details, obbProbe: ObbProbe? = null, modifier)`; `GeneralTabScreen(details, obbProbe: ObbProbe?)`.

- [ ] **Step 1: Add the field and load it**

In `AppInfoDetailsViewModel`, add to `AppInfoDetailsUiState`:

```kotlin
    /** Null until the probe answers. See [com.valhalla.thor.domain.model.ObbProbe]. */
    val obbProbe: ObbProbe? = null,
```

and at the end of `loadAppDetails`, after the existing state emission:

```kotlin
            // Deliberately after the details land: the probe shells out, and the rest of the
            // screen should not wait on it.
            val probe = systemRepository.probeObb(packageName)
            _uiState.update { it.copy(obbProbe = probe) }
```

Place it inside the same `viewModelScope.launch` that loads the details, after the `copy(isLoading = false, detailedInfo = ...)` update, so the screen renders first and the card fills in.

- [ ] **Step 2: Thread it to the card**

In `AppInfoDetailsScreen.kt`:
- line ~216, change `AppInfoDetailBody(details)` to `AppInfoDetailBody(details, state.obbProbe)` (`state` is already in scope in that branch);
- line ~428, change the signature to `fun AppInfoDetailBody(details: DetailedAppInfo, obbProbe: ObbProbe? = null, modifier: Modifier = Modifier)` — defaulted because the function is public;
- line ~471, change `0 -> GeneralTabScreen(details)` to `0 -> GeneralTabScreen(details, obbProbe)`;
- line ~646, change to `private fun GeneralTabScreen(details: DetailedAppInfo, obbProbe: ObbProbe?)`.

- [ ] **Step 3: Replace the card**

At ~:740, replace:

```kotlin
        appInfo.obbFilePath?.let { obb ->
            item {
                InfoCard(title = stringResource(R.string.info_obb_dir), value = obb)
            }
        }
```

with:

```kotlin
        // Not appInfo.obbFilePath: that is computed with File(...).exists(), which returns false
        // for another package's OBB directory on Android 11+ regardless of whether one exists —
        // so this card was simply absent for every game on a modern device.
        when (val probe = obbProbe) {
            null -> Unit // still probing
            ObbProbe.None -> item {
                InfoCard(
                    title = stringResource(R.string.info_obb_dir),
                    value = stringResource(R.string.info_obb_none)
                )
            }

            is ObbProbe.Undetermined -> item {
                InfoCard(
                    title = stringResource(R.string.info_obb_dir),
                    value = stringResource(R.string.info_obb_unknown)
                )
            }

            is ObbProbe.Present -> item {
                InfoCard(
                    title = stringResource(R.string.info_obb_dir),
                    value = stringResource(
                        R.string.info_obb_present,
                        "Android/obb/${appInfo.packageName}",
                        Formatter.formatShortFileSize(
                            LocalContext.current,
                            probe.files.sumOf { it.sizeBytes }
                        )
                    )
                )
            }
        }
```

Add `android.text.format.Formatter`, `androidx.compose.ui.platform.LocalContext` and `com.valhalla.thor.domain.model.ObbProbe` to the imports if absent. `LocalContext` was already imported and `GeneralTabScreen` already holds `val context = LocalContext.current` (used by the install-size card), so use that local rather than a second lookup.

> **This task listed two files and needed four.** Both gaps were found in review, not by the compiler, and both would have shipped silently:
>
> **1. `AppInfoSheet` renders the card too, and never got the probe** (fixed in `bd13c19e`).
> `presentation/widgets/AppInfoSheet.kt:322` hosts the same `AppInfoDetailBody` from the same
> `AppInfoDetailsViewModel` — it calls `loadAppDetails` on expansion, so it *pays* for the probe — but
> it called the body with `details` and `modifier` only. `obbProbe` is defaulted because the function
> is public, so the omission compiled cleanly and left the card on its `null -> Unit` branch forever.
> The sheet is the unified app-info surface most users reach, so the new card would have been invisible
> everywhere except the full detail screen. Pass `obbProbe = detailsState.obbProbe`. **The general
> lesson: a defaulted parameter added to a public composable hides every call site that should have
> been updated — grep for the callers, do not trust the build.**
>
> **2. Adding a `probeObb` call to `loadAppDetails` breaks assertions in `AppInfoDetailsViewModelTest`.**
> `FakeSystemRepository.probeObb` records `calls += "probeObb:$packageName"`, so any test that loads
> details and then asserts on `system.calls` changes meaning. Two were affected: *"removing a disabled
> and suspended app undoes both halves"* and *"adding to the watchlist freezes nothing"* (whose
> `calls.isEmpty()` assertion, message *"no privileged call belongs on the add path"*, stops meaning
> anything at all if the expected value becomes a non-empty list). Fixed with `system.calls.clear()`
> after the load setup — **not** by adding `"probeObb:a"` to the expectations, and **not** by removing
> the recording from the fake, which is what lets a test prove the probe ran. Three tests added
> (verdict lands in state; `Undetermined` is not collapsed to `None`; `null` before the probe answers),
> 924 → 927.
>
> Two smaller things this task's text did not settle, decided in the code:
> - **Where the probe goes when the load fails.** "After the existing state emission" and "after the
>   `copy(isLoading = false, detailedInfo = …)` update" name different sites when `details == null`. It
>   went after the whole if/else, so a package whose details fail still gets a verdict behind the error
>   screen. Harmless either way, but it was a choice.
> - **`otherEntryCount` has no branch in this card**, which reads against `ObbProbe.Present`'s KDoc
>   (*"a note shown to the user"*). The note lives on the export sheet, where "what won't be packed" is
>   actionable; a read-only card cannot act on it. Recorded in a comment at the card site so nobody
>   reads the absence as an oversight. Consequence: `Present(files = emptyList(), otherEntryCount > 0)`
>   renders `Android/obb/<pkg> — 0 B of game data`.
>
> One caveat on the "screen renders first" comment: it is true of production and not of the tests.
> `StateFlow` conflates and the update is synchronous, so the intermediate "details loaded, probe
> pending" value is only observable because the real `probeObb` suspends. `FakeSystemRepository` does
> not, which is why the nullness test asserts from the pre-load state rather than a mid-load snapshot.

- [ ] **Step 4: Build**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && ./gradlew :app:compileFossDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsViewModel.kt \
        app/src/main/java/com/valhalla/thor/presentation/appList/AppInfoDetailsScreen.kt
git commit -m "fix(app-info): read the OBB card from the probe, not File.exists()

File.exists() is false for another package's OBB dir on Android 11+
whether or not one is there, so this card never appeared. Refs #164."
```

---

## Task 12: Full sweep, docs and the PR

**Files:**
- Modify: `docs/follow-ups/README.md` (two edits — the #164 row, and the drifted `## Do next` preamble)
- Modify: `docs/follow-ups/app-data-backup-and-xapk-export.md`

**Interfaces:** none — verification and documentation only.

- [ ] **Step 1: Build every variant, lint included**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && ./gradlew :app:assembleFossDebug :app:lintFossDebug
```
Expected: BUILD SUCCESSFUL. Lint is fatal with `warningsAsErrors` and `checkTestSources`; the likely failures are `UnusedResources` (a Task 9 string no view references) and `MissingTranslation` (a locale missed). This is the first task where the strings are all referenced, which is why lint runs here and not in Task 9.

- [ ] **Step 2: Run the whole unit-test suite**

```
cd /Users/trinadhthatakula/StudioProjects/Thor && rm -rf app/build/test-results/testFossDebugUnitTest && ./gradlew :app:testFossDebugUnitTest --rerun-tasks
```
Take the totals from the XML, never from the log line — a failed compile leaves the previous run's passing XMLs in place, which is why the directory is deleted first:
```
cd /Users/trinadhthatakula/StudioProjects/Thor && grep -h -o 'tests="[0-9]*"' app/build/test-results/testFossDebugUnitTest/*.xml | grep -o '[0-9]*' | paste -sd+ - | bc; grep -h -o 'failures="[0-9]*"' app/build/test-results/testFossDebugUnitTest/*.xml | grep -o '[0-9]*' | paste -sd+ - | bc
```
Expected: a total well above the 842 the previous release carried, and `0` failures. Record both numbers for the PR body.

- [ ] **Step 3: Update the follow-up docs**

In `docs/follow-ups/app-data-backup-and-xapk-export.md`, mark the `.xapk` OBB half done — both directions, root and Shizuku, Dhizuku refused with a stated reason — and note that app data backup (Band C row 23 / GH#51 phase 2) remains open and unstarted.

In `docs/follow-ups/README.md`, update the #164 row to shipped, and fix the pre-existing drift in the `## Do next` preamble (~line 162): it still calls band A #1's release-notes retraction "due on the `master` merge", but that shipped in `release-notes/v1.94.1/github.md`. One line, unrelated to this feature, and cheaper to fix while the file is open than to carry forward again.

- [ ] **Step 4: Commit and push**

```bash
git add docs/follow-ups/README.md docs/follow-ups/app-data-backup-and-xapk-export.md
git commit -m "docs(follow-ups): #164 shipped; correct the band A retraction status"
git push -u origin feat/xapk-obb-support
```

Stage explicit paths only. `gradle/libs.versions.toml` carries an unrelated AGP bump that must stay unstaged, and `docs/discussions/` must stay untracked — `git add -A` would sweep in both.

- [ ] **Step 5: Open the PR**

```bash
gh pr create --base dev --title "feat: OBB support in .xapk export and install (#164)" --body "$(cat <<'EOF'
Closes #164.

`.xapk` export dropped every app's OBB files, and `.xapk` install ignored any that were
bundled. Both halves are fixed.

**Export.** The builder probes `Android/obb/<pkg>` through the privileged shell, has that
shell stage each expansion into `externalCacheDir` — the only location Thor and the shell
uid can both reach — streams them into the archive at
`Android/obb/<pkg>/<leaf>.obb`, and declares them in the manifest's `expansions` block.

**Install.** A bundled `expansions` block is validated against the package being installed
and placed into `Android/obb/<pkg>/`. An archive whose game data cannot be placed is refused
**before** anything is installed, rather than leaving a game that starts and then fails.

**Not root-only.** `executeShellCommand` is routed through `runGatewayAction`, so root and
Shizuku both work. Dhizuku's device-owner process cannot see another package's external
directories: the `.xapk` chip is disabled there with the reason shown, and an OBB-carrying
archive is refused with an explanation.

Thor also shipped a factually wrong justification — that "Android 11 and later stops any app
from reading another app's OBB folder". True of an ordinary app, false of Thor, which reads
protected paths through a privileged shell on every split export. Retracted in all five
locales.

The app-info OBB card was likewise dead code on any modern device: it was computed with
`File(...).exists()`, which returns false for another package's OBB directory whether or not
one exists. It now reads the probe.

Design: `docs/superpowers/specs/2026-08-10-xapk-obb-support-design.md`
Plan: `docs/superpowers/plans/2026-08-10-xapk-obb-support-implementation.md`
EOF
)"
```

- [ ] **Step 6: Device verification before merge**

The unit tests cover every pure decision, but nothing here proves the privileged parts work on a real device. Confirm on hardware (spec §8.3), and record the results in the PR:

- [ ] Export a game with an OBB under **root** — the `.xapk` contains `Android/obb/<pkg>/*.obb` and a matching `expansions` block.
- [ ] The same export under **Shizuku**. This is the claim that most needs proof: it rests on `executeShellCommand` being gateway-routed, and it is the difference between a root-only feature and a general one.
- [ ] Under **Dhizuku**, the `.xapk` chip is disabled and the reason is legible.
- [ ] Install that `.xapk` on a device where the game is absent, and **launch the game** — an install that reports success but leaves an unplayable game is the bug, not the fix.
- [ ] Install a third-party APKPure `.xapk` with expansions, to prove the wire format matches what other tools actually write rather than only what Thor writes.
- [ ] **Shizuku with its shell rung failing** — force the fallback to a `PackageInstaller` session (e.g. a package the `pm install` rung refuses) and confirm the OBB is still placed. This is the only cover for `awaitInstalled`, which reads a real `PackageManager` and so has no JVM unit test; before the fix in `70a9cd00` this exact path dropped the OBB in silence.
- [ ] Confirm shell-written files in `externalCacheDir` are readable **and deletable** by Thor. If they are not, the staging directory grows without bound on every export.
- [ ] Round-trip a game whose expansions exceed 4 GiB, to exercise Zip64.
- [ ] Confirm `stat -c '%s %n'` behaves on the target's toybox build. It is the probe's only sizing mechanism, and a toybox that rejects the format would make every probe `Undetermined` — which fails closed, but silently disables the feature.

---

## Review pass on PR #376 — four defects this plan did not anticipate

Automated review of the pushed branch found four things wrong with what shipped. All four are fixed
on the branch; recorded here so the plan stops disagreeing with the code.

1. **Task 1's probe script failed *open* on `stat`.** The `*.obb` branch ran `stat` unguarded, so a
   file it could not describe produced no `THOR_OBB` line while `THOR_OTHER 0`, `THOR_END` and exit
   code 0 all still arrived — and the parser read that as an empty directory. `None` leaves the
   `.xapk` chip enabled, so the export would have offered a bundle and packed no game data: GH#164
   again. The whole task was built around not folding "cannot see it" into "there is none", and it
   folded them anyway at the one place the plan never questioned — because `stat -c` availability
   across ROMs is exactly what device check 8 was written to find out. Now `THOR_STATFAIL` →
   `Undetermined`. **Lesson: a tri-state is only as honest as its narrowest failure path.**
2. **Task 8's gate could not tell an update from a completed install.** `awaitInstalled` asked
   `getPackageInfo`, which for an update is already true *before* `commit()` does anything — so the
   gate returned immediately and placed expansions against a session still in flight, and an update
   is the common case for a game. It also had no failure signal at all: a declined confirmation
   dialog never produces a package, so it spun the full 90 s and then emitted "could not confirm" on
   top of the real error `InstallReceiver` had already delivered. Now: `lastUpdateTime` captured
   before the install as the completion signal, plus `InstallerEventBus.latest` read each tick as the
   failure signal, and the failure branch is silent.
3. **Task 11's card rendered the previous app's verdict.** `loadAppDetails` cleared `errorMessage`
   but not `obbProbe`, and the probe deliberately resolves *after* the details — so a second load
   showed the new app's details next to the old app's game-data size, and left `.xapk` enabled for an
   app whose expansions Thor had not read. Fixed and regression-tested.
4. **Task 9's strings asserted a cause they cannot know.** `export_xapk_unavailable` and
   `info_obb_unknown` both said "needs root or Shizuku", but `Undetermined` also covers a failed,
   truncated or malformed reply from a shell Thor *does* have. That is the same mistake as the
   explain string this feature retracted, re-committed one screen away from the retraction. The copy
   now states what is certain and offers the usual cause without asserting it, in all five locales.

Two review findings were **not** acted on, deliberately: `markdownlint`'s MD018 on
`docs/follow-ups/README.md:53` (nothing in this repo runs markdownlint, and `#51` without a following
space is not an ATX heading under CommonMark — GitHub renders it as text) and MD040 on this design's
bare code fences (six other docs including four specs use bare fences; enforcing it here alone is
noise). Two more were doc drift and are fixed in the design: its stale `not yet implemented` status,
and a `copyFileWithRoot` fallback it specified that the builder does not implement — dropped, because
`externalCacheDir` is null exactly when the volume holding `Android/obb` is unavailable, so the
fallback could only ever run when its input does not exist.

## Self-Review

**Spec coverage.** Every section of `docs/superpowers/specs/2026-08-10-xapk-obb-support-design.md` maps to a task: §4 probe → Tasks 1–2; §2/§5.3 wire format → Task 3; §5.1 entry names → Task 5; §6.1 validation → Task 4; §5.2/§5.4 staging and space → Task 6; §6.2 extraction → Task 7; §6.3 placement → Task 8; §7 UI and strings → Tasks 9–11; §8 testing → per-task tests plus Task 12; §9 scope boundary (`.apks` carries no expansions, no `MANAGE_EXTERNAL_STORAGE`) → enforced in Tasks 5 and in Global Constraints.

**Placeholder scan.** No task says "similar to Task N", "TBD", "add appropriate error handling", or "etc." Every file path is exact, every signature is spelled out, every command is runnable as written.

**Type consistency.** `ObbProbe`/`ObbFile` are produced in Task 1 and consumed in 2, 6, 10, 11. `XapkExpansion`/`XapkExpansionInfo` are produced in Task 3 and consumed in 4, 6, 8. `ResolvedExpansion` is produced in Task 4 and consumed in 7 and 8. `ZipSource` is produced in Task 5 and consumed in 6. `ExtractedExpansion` is produced in Task 7 and consumed in 8. No consumer precedes its producer.

**Known risks, and where they fail loudly rather than quietly.**
- A Koin cycle from `ObbInstaller` fails the **build** in Task 8 Step 3, not at runtime, because `strictSafety` is on.
- An unreferenced string fails **lint** in Task 12 Step 1, which is why Task 9 explicitly does not run lint.
- Adding a method to `SystemRepository` breaks every implementor, including test doubles — caught by the compile in Task 2 Step 6, with the fix stated inline.
- The privileged paths are not unit-testable at all (`rikka.shizuku.Shizuku`'s static initialiser throws "not mocked" on the JVM), which is why Task 12 Step 6 is a required device pass and not an optional one.
