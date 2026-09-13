// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.source.local

import com.valhalla.thor.domain.gateway.ComponentEnabledState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three component commands, and the one function that decides whether one of them worked.
 *
 * Same reachable boundary as [PerUserCommandsTest], for the same reason: none of the gateways can be
 * constructed here, so what is under test is the string a builder produces and the verdict
 * [componentCommandFailure] returns for an output — never the wiring between them. Those two are
 * where every defect this feature can have that is *not* a device problem actually lives:
 *
 *  - a command that forgets `--user` acts on user 0 from a work profile, and `pm` exits 0 either way;
 *  - a spec that is not single-quoted loses its `$` to the shell, so an inner-class receiver name
 *    silently becomes its **outer** class — a different, real component;
 *  - a verdict taken from the exit code alone reports "Opened" for a launch the system refused.
 */
class ComponentCommandsTest {

    private val pkg = "com.example.app"
    private val cls = "com.example.app.MainActivity"

    /** The `--user <id>` a command names, or null if it names none — which is the bug. */
    private fun userArgOf(command: String): Int? =
        Regex("--user (\\d+)").find(command)?.groupValues?.get(1)?.toIntOrNull()

    private fun spec(packageName: String = pkg, className: String = cls): String =
        requireNotNull(escapedComponentSpecOrNull(packageName, className)) {
            "$packageName/$className was rejected by the validator"
        }

    // --- the spec: quoting and validation ---

    /**
     * The whole reason the spec is escaped before it reaches a builder. `PackageManager` reports an
     * inner class as `Outer$Inner`; unquoted, the shell expands `$Inner` to the empty string and the
     * command lands on `com.foo.Outer` — which in a great many apps is a real component of its own.
     * Single quotes are the only form that makes `$` inert in `sh`.
     */
    @Test
    fun `an inner class keeps its dollar sign`() {
        val escaped = spec(className = "com.example.app.Widget\$Receiver")
        assertTrue(
            "spec was not single-quoted: $escaped",
            escaped.startsWith("'") && escaped.endsWith("'"),
        )
        assertTrue("the dollar sign was lost: $escaped", escaped.contains("Widget\$Receiver"))
    }

    /** `$` is legal in a class name and must not be what makes the validator refuse. */
    @Test
    fun `an inner class is not rejected as implausible`() {
        assertNotNull(escapedComponentSpecOrNull(pkg, "com.example.app.Widget\$Receiver"))
    }

    /**
     * A `$` in the *package* half has no legitimate source, so the wider class pattern must not be
     * the one applied to it. Keeping the two patterns apart is the point of having two.
     */
    @Test
    fun `a dollar sign in the package half is rejected`() {
        assertNull(escapedComponentSpecOrNull("com.example\$app", cls))
    }

    /**
     * Shell metacharacters are refused outright rather than merely quoted. Quoting alone would be
     * enough for safety; refusing makes an impossible name visible as a bug instead of as a command
     * that runs against nothing.
     */
    @Test
    fun `shell metacharacters are refused in either half`() {
        assertNull(escapedComponentSpecOrNull("com.example.app; rm -rf /", cls))
        assertNull(escapedComponentSpecOrNull(pkg, "com.example.app.Main; rm -rf /"))
        assertNull(escapedComponentSpecOrNull(pkg, "com.example.app.Main`id`"))
    }

    /** The unescaped pair is `pkg/cls` and nothing else — the slash is the separator `pm` wants. */
    @Test
    fun `the spec is package slash class`() {
        assertEquals("$pkg/$cls", componentSpec(pkg, cls))
    }

    // --- pm enable / disable / default-state ---

    /**
     * `PackageManagerShellCommand` seeds `UserHandle.USER_SYSTEM` for the enable/disable/default
     * trio, so a bare command issued from a work profile changes **user 0's** component and exits 0.
     */
    @Test
    fun `every component state command names the user`() {
        for (state in ComponentState.entries) {
            assertEquals(
                "${state.pmVerb} dropped the user",
                10,
                userArgOf(setComponentStateCommand(spec(), 10, state)),
            )
        }
    }

    /** The verb has to follow the state, or "disable" enables and the row lies about what happened. */
    @Test
    fun `the verb follows the requested state`() {
        assertTrue(
            setComponentStateCommand(spec(), 10, ComponentState.DISABLED).startsWith("pm disable ")
        )
        assertTrue(
            setComponentStateCommand(spec(), 10, ComponentState.ENABLED).startsWith("pm enable ")
        )
        assertTrue(
            setComponentStateCommand(spec(), 10, ComponentState.DEFAULT)
                .startsWith("pm default-state ")
        )
    }

    /**
     * `disable`, never `disable-user`. `DISABLED_USER` is the state Settings writes at *package*
     * level; reusing it per component would make Thor's rows indistinguishable from a user-disabled
     * app in `dumpsys package`. `startsWith("pm disable ")` above would still pass for
     * `pm disable-user` if the trailing space were ever dropped, so this is asserted separately.
     */
    @Test
    fun `disable is the strong state and not disable-user`() {
        assertFalse(
            setComponentStateCommand(spec(), 10, ComponentState.DISABLED).contains("disable-user")
        )
    }

    /** The escaped spec reaches the command intact — quotes and all. */
    @Test
    fun `the command carries the quoted spec`() {
        val escaped = spec()
        assertTrue(setComponentStateCommand(escaped, 10, ComponentState.DISABLED).endsWith(escaped))
    }

    /**
     * [ComponentEnabledState.DEFAULT] maps to `default-state`, **not** to `enable`. `default-state`
     * removes the override; `enable` writes one. For a component that ships disabled the two produce
     * opposite results, which is exactly the case a "reset" is most often used on.
     */
    @Test
    fun `default maps to default-state and not to enable`() {
        assertEquals(ComponentState.DEFAULT, ComponentEnabledState.DEFAULT.asComponentState())
        assertEquals(ComponentState.ENABLED, ComponentEnabledState.ENABLED.asComponentState())
        assertEquals(ComponentState.DISABLED, ComponentEnabledState.DISABLED.asComponentState())
    }

    // --- am start ---

    @Test
    fun `starting an activity names the user`() {
        assertEquals(10, userArgOf(startActivityCommand(spec(), 10)))
    }

    /** `-n` is what makes the trailing word a component rather than data or a package. */
    @Test
    fun `starting an activity passes the component with -n`() {
        assertTrue(startActivityCommand(spec(), 10).contains("-n ${spec()}"))
    }

    /**
     * No action, no category, no flags. An invented `MAIN`/`LAUNCHER` action is visible to the
     * target through `intent.action`, and several apps branch on it in `onCreate` — the activity the
     * user asked to see is then not the one they get.
     */
    @Test
    fun `starting an activity invents no action or category`() {
        val command = startActivityCommand(spec(), 10)
        assertFalse(command.contains("-a "))
        assertFalse(command.contains("-c "))
        assertFalse(command.contains("android.intent.action"))
    }

    // --- am stopservice ---

    @Test
    fun `stopping a service names the user`() {
        assertEquals(10, userArgOf(stopServiceCommand(spec(), 10)))
    }

    /**
     * The `-n` here is not cosmetic. `Intent.parseCommandArgs` reads a trailing bare argument as
     * data or as a package, never as a component, so `am stopservice pkg/cls` builds an intent with
     * no component, stops nothing, and exits 0 — a silent no-op the UI would report as success.
     */
    @Test
    fun `stopping a service passes the component with -n`() {
        assertTrue(stopServiceCommand(spec(), 10).contains("-n ${spec()}"))
    }

    // --- the verdict ---

    /**
     * The case the whole function exists for: on most releases `am start` prints the refusal and
     * still exits **0**. Reading the code alone reports a launch that visibly did not happen.
     */
    @Test
    fun `a security exception at exit zero is a failure`() {
        val output = """
            Starting: Intent { cmp=com.example.app/.SecretActivity }
            Security exception: Permission Denial: starting Intent { ... } not exported from uid 10123
            	at android.os.Parcel.createExceptionOrNull(Parcel.java:3057)
            	at android.os.Parcel.createException(Parcel.java:3041)
        """.trimIndent()
        val failure = componentCommandFailure(exitCode = 0, output = output)
        assertNotNull("exit 0 hid a refusal", failure)
        assertTrue(failure!!.contains("Security exception"))
    }

    /**
     * Android 14 folds the same refusal into `START_CLASS_NOT_FOUND`, so the marker changes shape
     * entirely. Missing this one would make the feature report success on every recent release.
     */
    @Test
    fun `the Android 14 class-not-found refusal is a failure`() {
        val failure = componentCommandFailure(
            exitCode = 0,
            output = "Error: Activity class {com.example.app/com.example.app.SecretActivity} does " +
                "not exist.",
        )
        assertNotNull(failure)
        assertTrue(failure!!.startsWith("Error:"))
    }

    /**
     * The output above never arrives alone. `ActivityManagerShellCommand` prints its numeric code
     * on the line *before* the sentence, and both lines are marked — so a scan that takes the first
     * matching *line* reports "Error type 3", which names neither the component nor the reason.
     * Captured verbatim from an API 37 emulator.
     */
    @Test
    fun `the numeric code does not beat the sentence below it`() {
        val output = """
            Starting: Intent { cmp=com.example.app/com.example.app.SecretActivity }
            Error type 3
            Error: Activity class {com.example.app/com.example.app.SecretActivity} does not exist.
        """.trimIndent()
        val failure = componentCommandFailure(exitCode = 1, output = output)
        assertNotNull(failure)
        assertTrue(
            "expected the descriptive line, got: $failure",
            failure!!.startsWith("Error: Activity class"),
        )
    }

    /** …but on its own it is still the only evidence there is, and better than the bare echo. */
    @Test
    fun `the numeric code is reported when nothing better is present`() {
        val output = """
            Starting: Intent { cmp=com.example.app/.MainActivity }
            Error type 3
        """.trimIndent()
        assertEquals("Error type 3", componentCommandFailure(exitCode = 1, output = output))
    }

    /**
     * The single most common repeat press in the feature: opening an activity that is already the
     * foreground task. `am` calls that a warning and it is a complete success — treating it as a
     * failure would put an error Toast on the happy path.
     */
    @Test
    fun `a warning that the task was brought forward is a success`() {
        val output = """
            Starting: Intent { cmp=com.example.app/.MainActivity }
            Warning: Activity not started, its current task has been brought to the front
        """.trimIndent()
        assertNull(componentCommandFailure(exitCode = 0, output = output))
    }

    /** Plain success stays success — no marker, no code, no verdict. */
    @Test
    fun `a clean start is a success`() {
        assertNull(
            componentCommandFailure(
                exitCode = 0,
                output = "Starting: Intent { cmp=com.example.app/.MainActivity }",
            )
        )
    }

    /**
     * The other direction. A non-zero code with nothing recognisable in the output is still a
     * failure, so the code is checked as well as the text rather than instead of it.
     */
    @Test
    fun `a non-zero exit with no marker is still a failure`() {
        assertEquals("something went sideways", componentCommandFailure(1, "something went sideways"))
    }

    /** …and with no output at all, the code itself is the only thing left to report. */
    @Test
    fun `a non-zero exit with empty output reports the code`() {
        assertEquals("exit 137", componentCommandFailure(137, "   \n\n  "))
    }

    /**
     * `ShellCommand.exec` prints the whole stack trace on an exception. A Toast is not the place for
     * forty frames, so the verdict is one line and a bounded one.
     */
    @Test
    fun `the failure is one bounded line and not the stack trace`() {
        val output = buildString {
            appendLine("Security exception: Permission Denial")
            repeat(40) { appendLine("\tat android.os.Parcel.createException(Parcel.java:$it)") }
        }
        val failure = requireNotNull(componentCommandFailure(0, output))
        assertFalse("the stack trace came along", failure.contains("\n"))
        assertTrue("the line is unbounded", failure.length <= 200)
    }

    /** A line 200 characters long is not truncated; one longer is. */
    @Test
    fun `an overlong failure line is truncated`() {
        val failure = requireNotNull(componentCommandFailure(0, "Error: " + "x".repeat(500)))
        assertEquals(200, failure.length)
    }

    /**
     * `ShellCommand.exec` announces an exception with a header line and puts the exception itself on
     * the next one, so a plain first-match scan reports "Exception occurred while executing
     * 'start':" — a sentence that tells the user nothing. The specific markers are searched across
     * the whole output before the generic one is considered.
     *
     * Verbatim stderr from an Android 17 `am start` of an unexported activity at the shell uid.
     */
    @Test
    fun `the specific marker beats the exception header above it`() {
        val output = """
            Starting: Intent { cmp=com.google.android.deskclock/com.android.deskclock.ringtone.RingtoneSearchActivity }

            Exception occurred while executing 'start':
            java.lang.SecurityException: Permission Denial: starting Intent { cmp=com.google.android.deskclock/com.android.deskclock.ringtone.RingtoneSearchActivity } from null (pid=7714, uid=2000) not exported from uid 10175
            	at com.android.server.wm.ActivityStarter.executeRequest(ActivityStarter.java:1299)
        """.trimIndent()
        val failure = requireNotNull(componentCommandFailure(exitCode = 255, output = output))
        assertTrue("the header was reported instead of the denial", failure.contains("SecurityException"))
        assertFalse(failure.contains("Exception occurred while executing"))
    }

    /**
     * With no marker matching anywhere, the line *after* the header is reported rather than the
     * header — that is where `ShellCommand.exec` puts the exception, and a bare exception type still
     * says more than "something happened".
     */
    @Test
    fun `the line after the header is reported in preference to the header`() {
        val output = """
            Stopping service: Intent { cmp=com.example.app/.SyncService }
            Exception occurred while executing 'stopservice':
            java.lang.NullPointerException
        """.trimIndent()
        assertEquals(
            "java.lang.NullPointerException",
            componentCommandFailure(exitCode = 255, output = output),
        )
    }

    /** …and the header itself when there is no line after it worth reporting. */
    @Test
    fun `the header stands alone when only a stack frame follows it`() {
        val output = """
            Exception occurred while executing 'stopservice':
            	at com.android.server.am.ActivityManagerShellCommand.onCommand(AMSC.java:12)
        """.trimIndent()
        val failure = requireNotNull(componentCommandFailure(exitCode = 255, output = output))
        assertTrue(failure.startsWith("Exception occurred while executing"))
    }

    /**
     * The failure a "Restore all" hits for a component an app update has removed, verbatim from an
     * Android 17 `pm default-state`. The message names the component and is the whole point of
     * reporting a line at all — before `Exception:` was a marker, the user got the header instead.
     */
    @Test
    fun `a missing component names itself`() {
        val output = """

            Exception occurred while executing 'default-state':
            java.lang.IllegalArgumentException: Component class com.does.not.Exist does not exist in com.google.android.deskclock
            	at com.android.server.pm.PackageManagerService.setEnabledSettings(PackageManagerService.java:4257)
        """.trimIndent()
        val failure = requireNotNull(componentCommandFailure(exitCode = 255, output = output))
        assertTrue(failure.contains("IllegalArgumentException"))
        assertTrue(failure.contains("com.does.not.Exist"))
    }

    // --- the stopservice verdict ---
    //
    // `am stopservice` exits **255 whatever happens**. Verified on an Android 17 emulator for a
    // service that was stopped, one that was not running, and a component that does not exist,
    // through both `am` and `cmd activity stop-service`, with and without `--user`. Every one of
    // them printed `Stopping service: Intent { … }` to stdout, exited 255, and differed only in the
    // sentence written to stderr. So for this kind the code is not evidence, and the whole verdict
    // is the marker — which is also why the Shizuku gateway had to stop using `execute()`, whose
    // stdout-or-stderr contract drops the only line that carries the answer.

    @Test
    fun `a stopped service is a success despite exit 255`() {
        assertNull(
            componentCommandFailure(
                exitCode = 255,
                output = "Stopping service: Intent { cmp=com.example.app/.SyncService }\nService stopped",
                kind = ComponentCommandKind.STOP_SERVICE,
            )
        )
    }

    /**
     * The one code a stopservice *must* still read: a negative one, which `am` never returns.
     * `ShizukuHelper` uses it for a dead binder, a timeout, and a thrown exception, and its output
     * carries none of the three markers — so "ignore the exit code" taken literally reports a
     * service Thor never reached as a service it stopped.
     */
    @Test
    fun `a dead transport is a failure even for stopservice`() {
        assertEquals(
            "Shizuku binder is null",
            componentCommandFailure(
                exitCode = -1,
                output = "Shizuku binder is null",
                kind = ComponentCommandKind.STOP_SERVICE,
            )
        )
    }

    /** …and with no output at all there is still a verdict, rather than a silent success. */
    @Test
    fun `a silent transport failure still reports for stopservice`() {
        assertEquals(
            "exit -1",
            componentCommandFailure(
                exitCode = -1,
                output = "",
                kind = ComponentCommandKind.STOP_SERVICE,
            )
        )
    }

    /**
     * The asynchronous wording, for a service whose `onDestroy` has not returned yet. It is the
     * third success marker and the only one with no test of its own — without this, deleting it
     * from the list turns every slow stop into an error Toast and the suite stays green.
     */
    @Test
    fun `a service that is still stopping is a success`() {
        assertNull(
            componentCommandFailure(
                exitCode = 255,
                output = "Stopping service: Intent { cmp=com.example.app/.SyncService }\n" +
                    "Service stopping",
                kind = ComponentCommandKind.STOP_SERVICE,
            )
        )
    }

    /**
     * The most likely outcome of the press, and the one that must not be an error: most services in
     * a component list are not running when the user is looking at them, and "stop this service" is
     * satisfied by a service that is already stopped.
     */
    @Test
    fun `a service that was not running is a success`() {
        assertNull(
            componentCommandFailure(
                exitCode = 255,
                output = "Stopping service: Intent { cmp=com.example.app/.SyncService }\n" +
                    "Service not stopped: was not running.",
                kind = ComponentCommandKind.STOP_SERVICE,
            )
        )
    }

    /** The one real failure `ActivityManagerShellCommand` reports, and it has to survive. */
    @Test
    fun `an error stopping the service is still a failure`() {
        val failure = componentCommandFailure(
            exitCode = 255,
            output = "Stopping service: Intent { cmp=com.example.app/.SyncService }\n" +
                "Error stopping service",
            kind = ComponentCommandKind.STOP_SERVICE,
        )
        assertEquals("Error stopping service", failure)
    }

    /** A refusal reaches the user even though the code it came with is the same 255 as a success. */
    @Test
    fun `a security exception on stopservice is a failure`() {
        val failure = componentCommandFailure(
            exitCode = 255,
            output = "Stopping service: Intent { cmp=com.example.app/.SyncService }\n" +
                "java.lang.SecurityException: Permission Denial",
            kind = ComponentCommandKind.STOP_SERVICE,
        )
        assertNotNull(failure)
        assertTrue(failure!!.contains("SecurityException"))
    }

    /**
     * The echo alone — which is all the Shizuku path could ever see before `executeCombined` — is
     * *not* a failure. Reading exit 255 as one is exactly the defect: every single "Stop now" press
     * reported "Stopping service: Intent { … }" as its error message.
     */
    @Test
    fun `the bare intent echo at exit 255 is not a failure`() {
        assertNull(
            componentCommandFailure(
                exitCode = 255,
                output = "Stopping service: Intent { cmp=com.example.app/.SyncService }",
                kind = ComponentCommandKind.STOP_SERVICE,
            )
        )
    }

    /**
     * The exemption is scoped to the stopservice kind and nothing else. The same output judged as a
     * standard command is a failure, which is what keeps `pm disable` and `am start` — both of which
     * *do* set a meaningful code — from silently inheriting the exemption.
     */
    @Test
    fun `the standard kind still reads a non-zero exit as a failure`() {
        assertEquals(
            "Stopping service: Intent { cmp=com.example.app/.SyncService }",
            componentCommandFailure(
                exitCode = 255,
                output = "Stopping service: Intent { cmp=com.example.app/.SyncService }",
            )
        )
    }

    /** …and the standard kind is what a caller that names no kind gets. */
    @Test
    fun `the default kind is standard`() {
        assertEquals(
            componentCommandFailure(1, "boom", ComponentCommandKind.STANDARD),
            componentCommandFailure(1, "boom"),
        )
    }
}
