// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveRestoreRequestTest {

    private val request = ArchiveRestoreRequest(
        uriString = "content://com.android.providers.downloads.documents/document/42",
        packageName = "com.example.app",
        classes = setOf(DataClass.CE, DataClass.EXTERNAL_MEDIA),
        restoreObb = true,
    )

    @Test
    fun `a request survives a round trip through the map`() {
        assertEquals(request, ArchiveRestoreRequest.fromMap(request.toMap()))
    }

    @Test
    fun `the map holds only types androidx work Data accepts`() {
        // A Set or an enum here throws at putAll — at enqueue time, in production, with no test
        // between here and there to catch it.
        // `Set<Class<*>>` spelled out: inferred, the three elements give `Set<Class<out Serializable>>`,
        // and `in` against a `Class<Any>` then cannot fix the `contains` type parameter.
        //
        // `javaObjectType`, not `java`: the comparison is against `value.javaClass`, which is always a
        // boxed `java.lang.Boolean`. `Boolean::class.java` is the *primitive* `boolean.class` and
        // matches nothing here, so a test written that way fails for a reason unrelated to what it is
        // asserting. Naming `java.lang.Boolean` directly says the same thing but warns on every build.
        val allowed: Set<Class<*>> =
            setOf(String::class.java, Boolean::class.javaObjectType, Array<String>::class.java)
        request.toMap().forEach { (key, value) ->
            assertTrue("$key is a ${value.javaClass}", value.javaClass in allowed)
        }
    }

    @Test
    fun `the map carries no gate decision`() {
        // installFirst is deliberately absent: the worker re-runs the gate against a fresh read of
        // the archive and of what is installed. A decision persisted at enqueue time describes an app
        // that may have been installed or removed while the job sat in the chain.
        assertTrue(
            request.toMap().keys.none { it.contains("install", ignoreCase = true) },
        )
    }

    @Test
    fun `a map with no uri is not a runnable restore`() {
        assertNull(ArchiveRestoreRequest.fromMap(request.toMap() - RESTORE_URI_KEY))
        assertNull(ArchiveRestoreRequest.fromMap(request.toMap() + (RESTORE_URI_KEY to "")))
    }

    @Test
    fun `a map with no package is not a runnable restore`() {
        // The package the archive claims, checked against the header the worker re-reads. Without it
        // a re-resolved URI pointing at a different archive would restore the wrong app's data.
        assertNull(ArchiveRestoreRequest.fromMap(request.toMap() - RESTORE_PACKAGE_KEY))
    }

    @Test
    fun `a map with no classes is not a runnable restore`() {
        assertNull(ArchiveRestoreRequest.fromMap(request.toMap() - RESTORE_CLASSES_KEY))
        assertNull(
            ArchiveRestoreRequest.fromMap(request.toMap() + (RESTORE_CLASSES_KEY to emptyArray<String>()))
        )
    }

    @Test
    fun `an unknown class id is dropped rather than fatal`() {
        // Same rule as ArchiveBackupRequest: a job enqueued by a newer build and run after a
        // downgrade restores the classes this build understands.
        val map = request.toMap() + (RESTORE_CLASSES_KEY to arrayOf(DataClass.CE.id, "ce-v2"))

        assertEquals(setOf(DataClass.CE), ArchiveRestoreRequest.fromMap(map)!!.classes)
    }

    @Test
    fun `classes come back in DataClass order, not map order`() {
        // The restore loop's order is the order CE/DE/ext-data/ext-media are declared, because DE
        // holding a key CE needs is the ordering that matters. A Set built from an arbitrary array
        // would leave that to chance.
        val map = request.toMap() + (
            RESTORE_CLASSES_KEY to arrayOf(DataClass.EXTERNAL_MEDIA.id, DataClass.CE.id, DataClass.DE.id)
            )

        assertEquals(
            listOf(DataClass.CE, DataClass.DE, DataClass.EXTERNAL_MEDIA),
            ArchiveRestoreRequest.fromMap(map)!!.orderedClasses(),
        )
    }

    @Test
    fun `restoreObb defaults to false when absent`() {
        assertEquals(false, ArchiveRestoreRequest.fromMap(request.toMap() - RESTORE_OBB_KEY)!!.restoreObb)
    }
}
