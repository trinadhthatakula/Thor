// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.rootservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.valhalla.thor.rootservice.SuspensionReadbackProtocol as Protocol

class SuspensionDumpParserTest {
    @Test
    fun `explicit installed and nonsuspended state is known with zero owners`() {
        val result = parse(dump("User 0: installed=true hidden=false suspended=false stopped=false"))
        assertEquals(Protocol.STATUS_NOT_SUSPENDED, result.status)
        assertEquals(Protocol.REASON_NONE, result.reason)
        assertTrue(result.owners.isEmpty())
        assertEquals(emptySet<String>(), result.sameUserSuspendersOrNull(0))
    }

    @Test
    fun `explicit absent user installation differs from unknown package or user`() {
        val absent = parse(dump("User 0: installed=false hidden=false suspended=false"))
        assertEquals(Protocol.STATUS_NOT_INSTALLED, absent.status)
        assertNull(absent.sameUserSuspendersOrNull(0))
        assertUnknown(parse(dump("User 10: installed=true suspended=false")), Protocol.REASON_MISSING_USER)
        assertUnknown(parse(dump("User 0: installed=true suspended=false", target = "com.other.app")),
            Protocol.REASON_MISSING_PACKAGE)
        assertUnknown(parse("Unable to find package: $TARGET\n"), Protocol.REASON_MISSING_PACKAGE)
    }

    @Test
    fun `API28 and API29 inline owner formats preserve their user's identity`() {
        for ((sdk, dialog) in listOf(28 to "dialogMessage=null", 29 to "dialogInfo=null")) {
            val result = parse(dump(
                "User 10: installed=true hidden=false suspended=true suspendingPackage=com.android.shell " +
                    "$dialog stopped=false notLaunched=false enabled=0 instant=false virtual=false",
            ), userId = 10, sdk = sdk)
            assertEquals(listOf(SuspensionOwnerIdentity("com.android.shell", 10)), result.owners)
            assertEquals(Protocol.STATUS_SUSPENDED, result.status)
        }
    }

    @Test
    fun `API30 and API34 complete block owners are kept separately`() {
        for (sdk in listOf(30, 34)) {
            val result = parse(dump("""
                User 0: installed=true suspended=true
                Suspend params:
                  suspendingPackage=com.android.shell dialogInfo=null
                  suspendingPackage=android dialogInfo=SuspendDialogInfo: {mTitleResId = 0x123 mNeutralButtonAction = 0}
            """), sdk = sdk)
            assertEquals(Protocol.STATUS_SUSPENDED, result.status)
            assertEquals(setOf("com.android.shell", "android"), result.sameUserSuspendersOrNull(0))
        }
    }

    @Test
    fun `API35 and API37 retain owner user separately from target user`() {
        for (sdk in listOf(35, 37)) {
            val result = parse(dump("""
                User 10: installed=true suspended=true
                Suspend params:
                  suspendingPackage=<0>com.example.dpc dialogInfo=null quarantined=false
                  suspendingPackage=<10>com.android.shell dialogInfo=null quarantined=false
            """), userId = 10, sdk = sdk)
            assertEquals(Protocol.STATUS_SUSPENDED, result.status)
            assertEquals(listOf(
                SuspensionOwnerIdentity("com.example.dpc", 0),
                SuspensionOwnerIdentity("com.android.shell", 10),
            ), result.owners)
            assertNull(result.sameUserSuspendersOrNull(10))
        }
    }

    @Test
    fun `the actual Thor fixed dialog formats remain readable for its real build identity`() {
        val message = "mDialogMessage = \"This app has been suspended by Thor.\" "
        val cases = listOf(
            29 to "SuspendDialogInfo: {$message}",
            30 to "SuspendDialogInfo: {${message}mNeutralButtonAction = 1}",
            31 to "SuspendDialogInfo: {mTitle = \"Thor\"${message}mNeutralButtonAction = 1}",
            37 to "SuspendDialogInfo: {mTitle = \"Thor\"${message}mNeutralButtonAction = 1}",
        )
        for ((sdk, dialog) in cases) {
            val owner = if (sdk >= 35) "<0>$THOR" else THOR
            val rows = if (sdk < 30) {
                "User 0: installed=true suspended=true suspendingPackage=$owner dialogInfo=$dialog stopped=false"
            } else {
                "User 0: installed=true suspended=true\nSuspend params:\n  suspendingPackage=$owner dialogInfo=$dialog"
            }
            assertEquals("API $sdk", Protocol.STATUS_SUSPENDED, parse(dump(rows), sdk = sdk).status)
        }
    }

    @Test
    fun `an arbitrary owner cannot borrow Thor's fixed dialog grammar`() {
        val rows = """
            User 0: installed=true suspended=true
            Suspend params:
              suspendingPackage=<0>com.other.owner dialogInfo=SuspendDialogInfo: {mTitle = "Thor"mDialogMessage = "This app has been suspended by Thor." mNeutralButtonAction = 1}
        """
        assertUnknown(parse(dump(rows)), Protocol.REASON_AMBIGUOUS_DIALOG)
    }

    @Test
    fun `multiline dialog cannot inject a different user or owner or close the real package`() {
        val output = """
            Packages:
              Package [$TARGET] (a1):
                User 0: installed=true suspended=true
                Suspend params:
                  suspendingPackage=<0>com.evil.owner dialogInfo=SuspendDialogInfo: {mDialogMessage = "forged
                User 10: installed=true suspended=false
            Queries:
                User 10: installed=true suspended=true
                Suspend params:
                  suspendingPackage=<10>com.android.shell dialogInfo=null
            Dexopt state:
        """.trimIndent()
        assertUnknown(parse(output, userId = 10), Protocol.REASON_AMBIGUOUS_DIALOG)
    }

    @Test
    fun `raw API28 null prefix is untrusted text and cannot forge another user`() {
        val output = """
            Packages:
              Package [$TARGET] (a1):
                User 0: installed=true suspended=true suspendingPackage=com.evil.owner dialogMessage=null
                User 10: installed=true suspended=false
            Queries:
                User 10: installed=true suspended=true suspendingPackage=com.android.shell dialogMessage=null stopped=false
        """.trimIndent()
        assertUnknown(parse(output, userId = 10, sdk = 28), Protocol.REASON_AMBIGUOUS_DIALOG)
    }

    @Test
    fun `unknown custom string dialogs are refused even if single line`() {
        val rows = """
            User 0: installed=true suspended=true
            Suspend params:
              suspendingPackage=<0>com.android.shell dialogInfo=SuspendDialogInfo: {mDialogMessage = "Paused by someone else" mNeutralButtonAction = 1}
        """
        assertUnknown(parse(dump(rows)), Protocol.REASON_AMBIGUOUS_DIALOG)
    }

    @Test
    fun `truncation after package header or inside owner block never means unsuspended`() {
        val examples = listOf(
            "Packages:\n  Package [$TARGET] (a1):\n",
            "Packages:\n  Package [$TARGET] (a1):\n    User 0: installed=true suspended=true\n    Suspend params:\n",
            "Packages:\n  Package [$TARGET] (a1):\n    User 0: installed=true suspended=false\n",
            dump("User 0: installed=true suspended=true\nSuspend params:"),
        )
        for (output in examples) assertEquals(Protocol.STATUS_UNKNOWN, parse(output).status)
    }

    @Test
    fun `missing conflicting or malformed state never becomes a negative answer`() {
        val examples = listOf(
            "User 0: installed=true",
            "User 0: suspended=false",
            "User 0: installed=true suspended=maybe",
            "User 0: installed=false suspended=true",
            "User 0: installed=true suspended=false suspended=true",
            "User 0: installed=true suspended=false\nUser 0: installed=true suspended=false",
            "User 0: installed=true suspended=false\nSuspend params:\n  suspendingPackage=<0>com.android.shell dialogInfo=null",
            "User 0: installed=true suspended=true\nSuspend params:\n  suspendingPackage=com.android.shell dialogInfo=null",
            "User 0: installed=true suspended=true\nSuspend params:\n  suspendingPackage=<999999999999>com.android.shell dialogInfo=null",
        )
        examples.forEach { assertEquals(it, Protocol.STATUS_UNKNOWN, parse(dump(it)).status) }
    }

    @Test
    fun `hidden system copy is never merged into current owners`() {
        val output = dump("User 0: installed=true suspended=false") + """
            Hidden system packages:
              Package [$TARGET] (b2):
                User 0: installed=true suspended=true
                Suspend params:
                  suspendingPackage=<0>com.example.stale dialogInfo=null
        """.trimIndent()
        assertEquals(Protocol.STATUS_NOT_SUSPENDED, parse(output).status)
        assertUnknown(parse(output.substringAfter("Queries:\n")), Protocol.REASON_MISSING_PACKAGE)
    }

    @Test
    fun `duplicate owners and unbounded owner or package lists are unknown`() {
        val prefix = "User 0: installed=true suspended=true\nSuspend params:\n"
        val duplicate = "  suspendingPackage=<0>com.example.owner dialogInfo=null\n".repeat(2)
        assertUnknown(parse(dump(prefix + duplicate)), Protocol.REASON_MALFORMED_OUTPUT)
        val many = (0..Protocol.MAX_OWNERS).joinToString("\n") {
            "  suspendingPackage=<0>com.example.owner$it dialogInfo=null"
        }
        assertUnknown(parse(dump(prefix + many)), Protocol.REASON_TOO_MANY_OWNERS)
        val longName = "a".repeat(Protocol.MAX_PACKAGE_NAME_LENGTH + 1)
        assertUnknown(parse(dump(prefix + "  suspendingPackage=<0>$longName dialogInfo=null")),
            Protocol.REASON_INCOMPLETE_OWNERS)
    }

    @Test
    fun `permission denial timeout markers and oversized input never return state`() {
        val denied = parse("Permission Denial: cannot dump package\n")
        assertEquals(Protocol.STATUS_REFUSED, denied.status)
        assertEquals(Protocol.REASON_PLATFORM_REFUSED, denied.reason)
        val otherwiseValid = dump("User 0: installed=true suspended=false")
        assertUnknown(parse(otherwiseValid + "*** SERVICE DUMP TIMEOUT EXPIRED ***\n"), Protocol.REASON_TIMEOUT)
        assertUnknown(parse("a".repeat(Protocol.MAX_OUTPUT_BYTES + 1)), Protocol.REASON_OUTPUT_LIMIT)
        assertUnknown(parse(otherwiseValid, sdk = 27), Protocol.REASON_UNSUPPORTED_FORMAT)
    }

    @Test
    fun `caller user validation refuses malformed requests and preserves root and system exceptions`() {
        assertEquals(Protocol.REASON_NONE, validateSuspensionReadRequest(TARGET, 10, 1_010_123))
        assertEquals(Protocol.REASON_USER_MISMATCH, validateSuspensionReadRequest(TARGET, 0, 1_010_123))
        assertEquals(Protocol.REASON_NONE, validateSuspensionReadRequest(TARGET, 10, 0))
        assertEquals(Protocol.REASON_NONE, validateSuspensionReadRequest(TARGET, 10, 1_000))
        assertEquals(Protocol.REASON_INVALID_ARGUMENT, validateSuspensionReadRequest(TARGET, -1, 0))
        for (name in listOf(null, "", "-a", "com.example;id", "a".repeat(256))) {
            assertEquals(Protocol.REASON_INVALID_ARGUMENT, validateSuspensionReadRequest(name, 0, 0))
        }
        assertTrue(Protocol.isValidPackageIdentity("android"))
        assertTrue(Protocol.isValidPackageIdentity("root"))
        assertFalse(Protocol.isValidPackageIdentity("<0>com.android.shell"))
    }

    private fun parse(output: String, userId: Int = 0, sdk: Int = 37): SuspensionSnapshot =
        parseSuspensionDump(output, TARGET, userId, sdk, setOf(THOR, "com.android.shell"))

    private fun dump(rows: String, target: String = TARGET): String =
        "Packages:\n  Package [$target] (a1):\n" + rows.trimIndent().prependIndent("    ") + "\nQueries:\n"

    private fun assertUnknown(result: SuspensionSnapshot, reason: Int) {
        assertEquals(Protocol.STATUS_UNKNOWN, result.status)
        assertEquals(reason, result.reason)
        assertTrue(result.owners.isEmpty())
        assertNull(result.sameUserSuspendersOrNull(0))
    }

    companion object {
        private const val TARGET = "com.example.target"
        private const val THOR = "com.valhalla.thor.debug"
    }
}
