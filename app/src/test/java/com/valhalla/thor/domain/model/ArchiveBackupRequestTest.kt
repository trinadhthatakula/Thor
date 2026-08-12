// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This map becomes a `WorkRequest`'s input `Data`, which WorkManager writes to **SQLite**. The tests
 * that matter are therefore as much about what is *absent* as about what round trips.
 */
class ArchiveBackupRequestTest {

    private val request = ArchiveBackupRequest(
        packageName = "com.example.app",
        classes = setOf(DataClass.CE, DataClass.EXTERNAL_MEDIA),
        includeBundle = true,
        salt = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
    )

    @Test
    fun `a request round trips through the map`() {
        val restored = ArchiveBackupRequest.fromMap(request.toMap())!!

        assertEquals(request.packageName, restored.packageName)
        assertEquals(request.classes, restored.classes)
        assertEquals(request.includeBundle, restored.includeBundle)
        assertTrue(request.salt.contentEquals(restored.salt))
    }

    @Test
    fun `no value in the map is the passphrase or the key`() {
        // The contract this whole type exists to enforce. WorkManager persists input `Data` to its
        // database, so anything here is on disk in the clear and stays there until the job is pruned.
        // The salt is *not* secret — it is published in `thorbak.json` — so it may travel.
        val values = request.toMap().values.map { it.toString() }

        assertTrue(values.toString(), values.none { it.contains("pass", ignoreCase = true) })
        assertEquals(4, request.toMap().size)
    }

    @Test
    fun `every value is a type WorkManager Data accepts`() {
        // `Data` takes String, Boolean, Int, Long, Double, their arrays, and nothing else. A `Set` or
        // a `DataClass` would throw at `putAll` — at enqueue time, in production, not here.
        val accepted: Set<Class<*>> = setOf(String::class.java, Boolean::class.javaObjectType, Array<String>::class.java)

        request.toMap().forEach { (dataKey, value) ->
            assertTrue("$dataKey is ${value.javaClass}", value.javaClass in accepted)
        }
    }

    @Test
    fun `an unknown class id is dropped rather than crashing the worker`() {
        // A job enqueued by an older Thor, surviving a downgrade. Dropping the class it names is
        // recoverable; throwing inside `fromMap` is a worker that fails before it can say why.
        val map = request.toMap().toMutableMap()
        map[BACKUP_CLASSES_KEY] = arrayOf("ce", "ce-from-the-future")

        val restored = ArchiveBackupRequest.fromMap(map)!!

        assertEquals(setOf(DataClass.CE), restored.classes)
    }

    @Test
    fun `a request with no recognisable class is refused`() {
        // Not "back up nothing" — an archive with no members is a file that looks like a backup and
        // restores nothing.
        val map = request.toMap().toMutableMap()
        map[BACKUP_CLASSES_KEY] = arrayOf<String>()

        assertNull(ArchiveBackupRequest.fromMap(map))
    }

    @Test
    fun `a map missing the package name is refused`() {
        val map = request.toMap().toMutableMap()
        map.remove(BACKUP_PACKAGE_KEY)

        assertNull(ArchiveBackupRequest.fromMap(map))
    }

    @Test
    fun `a map whose salt is not a salt is refused`() {
        // Base64 that decodes to the wrong length would derive a key that no reader can reproduce, and
        // the failure would appear at *restore* time, months later.
        val map = request.toMap().toMutableMap()
        map[BACKUP_SALT_KEY] = "not base64 at all !!"

        assertNull(ArchiveBackupRequest.fromMap(map))
    }
}
