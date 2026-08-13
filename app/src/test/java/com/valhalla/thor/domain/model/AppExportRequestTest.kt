// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppExportRequestTest {

    private fun request(
        packageName: String = "com.example.game",
        format: BundleFormat = BundleFormat.XAPK,
        label: String = "Example Game",
        treeUri: String? = null,
    ) = AppExportRequest(packageName, format, label, treeUri)

    @Test
    fun `a request survives the round trip through Data`() {
        val original = request(treeUri = "content://tree/primary%3AExports")

        assertEquals(original, AppExportRequest.fromMap(original.toMap()))
    }

    @Test
    fun `a Downloads request survives the round trip too`() {
        val original = request(treeUri = null)

        assertEquals(original, AppExportRequest.fromMap(original.toMap()))
    }

    @Test
    fun `every value handed to Data is a type Data accepts`() {
        // `workDataOf` throws at enqueue time — in production, on the user's tap — for a Set, an enum
        // or a null. This request carries an enum and a nullable field, so both are converted here
        // rather than at the call site, and this is what says so.
        val values = request(treeUri = "content://tree/x").toMap().values

        assertTrue(values.all { it is String })
    }

    @Test
    fun `an absent tree means Downloads, and is absent rather than null`() {
        val map = request(treeUri = null).toMap()

        // Not `map[EXPORT_TREE_KEY] == null` — that is true either way. The key must not be present
        // at all, because a null *value* is what `workDataOf` refuses.
        assertEquals(false, map.containsKey(EXPORT_TREE_KEY))
        assertEquals(ExportTargetChoice.Downloads, request(treeUri = null).target)
    }

    @Test
    fun `a tree uri resolves to the custom target it names`() {
        assertEquals(
            ExportTargetChoice.Custom("content://tree/primary%3AExports"),
            request(treeUri = "content://tree/primary%3AExports").target,
        )
    }

    @Test
    fun `an unrecognised format is refused rather than guessed`() {
        // The only guess available is BundleFormat.autoFor, which can never return XAPK — so a
        // fallback would hand back a .apks where the user asked for the container with their game
        // data in it, and nothing would say so. A job enqueued by a newer build and run after a
        // downgrade is the case; refusing is the honest answer.
        val map = request().toMap().toMutableMap().apply { put(EXPORT_FORMAT_KEY, "AAB") }

        assertNull(AppExportRequest.fromMap(map))
    }

    @Test
    fun `the format is matched by name and not by ordinal`() {
        for (format in BundleFormat.entries) {
            val restored = AppExportRequest.fromMap(request(format = format).toMap())

            assertEquals(format, restored?.format)
        }
    }

    @Test
    fun `a missing or blank required key is refused`() {
        for (key in listOf(EXPORT_PACKAGE_KEY, EXPORT_FORMAT_KEY, EXPORT_LABEL_KEY)) {
            assertNull(AppExportRequest.fromMap(request().toMap() - key))
            assertNull(
                AppExportRequest.fromMap(request().toMap().toMutableMap().apply { put(key, "  ") })
            )
        }
    }

    @Test
    fun `a blank tree uri is read as absent rather than as a folder named nothing`() {
        val map = request().toMap().toMutableMap().apply { put(EXPORT_TREE_KEY, "") }

        assertEquals(ExportTargetChoice.Downloads, AppExportRequest.fromMap(map)?.target)
    }

    @Test
    fun `an empty map is refused`() {
        assertNull(AppExportRequest.fromMap(emptyMap()))
    }
}
