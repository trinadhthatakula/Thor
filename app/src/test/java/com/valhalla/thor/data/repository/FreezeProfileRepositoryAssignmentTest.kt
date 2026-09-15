// SPDX-FileCopyrightText: 2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.data.repository

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.valhalla.thor.data.source.local.room.AppDatabase
import com.valhalla.thor.domain.model.MissingFreezeProfilesException
import com.valhalla.thor.domain.model.ProfileAssignmentCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FreezeProfileRepositoryAssignmentTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: FreezeProfileRepositoryImpl

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = FreezeProfileRepositoryImpl(database.freezeProfileDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `append to multiple targets preserves names metadata members and other profiles`() = runTest {
        val first = repository.create("Games", listOf("com.example.existing"))
        val second = repository.create("Travel", listOf("com.example.maps"))
        val other = repository.create("Work", listOf("com.example.work"))
        // The picker may have observed the old name; success reports the current stored name.
        database.freezeProfileDao().renameProfile(first, "Current games")
        val metadata = database.freezeProfileDao().profilesByIds(listOf(first, second, other))

        val result = repository.addApps(setOf(second, first), setOf("com.example.new", "android"))

        assertEquals(
            listOf(
                ProfileAssignmentCount(first, "Current games", 2, 0),
                ProfileAssignmentCount(second, "Travel", 2, 0),
            ),
            result.profiles,
        )
        assertEquals(4, result.addedCount)
        assertEquals(0, result.alreadyPresentCount)
        assertEquals(setOf("com.example.existing", "com.example.new", "android"), repository.packagesOf(first).toSet())
        assertEquals(setOf("com.example.maps", "com.example.new", "android"), repository.packagesOf(second).toSet())
        assertEquals(listOf("com.example.work"), repository.packagesOf(other))
        assertEquals(metadata, database.freezeProfileDao().profilesByIds(listOf(first, second, other)))
    }

    @Test
    fun `overlap and repeated assignment report actual membership counts without duplicates`() = runTest {
        val first = repository.create("Games", listOf("com.example.shared", "com.example.retained"))
        val second = repository.create("Travel", listOf("com.example.new"))
        val packages = setOf("com.example.shared", "com.example.new", "com.example.new")

        val firstResult = repository.addApps(setOf(first, second), packages)

        assertEquals(
            listOf(
                ProfileAssignmentCount(first, "Games", 1, 1),
                ProfileAssignmentCount(second, "Travel", 1, 1),
            ),
            firstResult.profiles,
        )
        assertEquals(2, firstResult.addedCount)
        assertEquals(2, firstResult.alreadyPresentCount)

        val repeated = repository.addApps(setOf(first, second), packages)

        assertEquals(0, repeated.addedCount)
        assertEquals(4, repeated.alreadyPresentCount)
        assertEquals(listOf(2, 2), repeated.profiles.map { it.alreadyPresentCount })
        assertEquals(setOf("com.example.shared", "com.example.retained", "com.example.new"), repository.packagesOf(first).toSet())
        assertEquals(3, repository.packagesOf(first).size)
        assertEquals(2, repository.packagesOf(second).size)
    }

    @Test
    fun `deleted or unknown targets fail together without changing a surviving profile`() = runTest {
        val surviving = repository.create("Games", listOf("com.example.existing"))
        val deleted = repository.create("Removed", listOf("com.example.removed"))
        repository.delete(deleted)
        val before = repository.observeProfiles().first()
        val unknown = deleted + 1_000

        val failure = runCatching {
            repository.addApps(setOf(surviving, deleted, unknown), setOf("com.example.new"))
        }.exceptionOrNull()

        assertTrue("Expected a typed missing-target failure, got $failure", failure is MissingFreezeProfilesException)
        assertEquals(setOf(deleted, unknown), (failure as MissingFreezeProfilesException).profileIds)
        assertEquals(before, repository.observeProfiles().first())
    }

    @Test
    fun `an insertion failure on a later profile rolls back all earlier appends`() = runTest {
        val first = repository.create("Games", listOf("com.example.existing"))
        val second = repository.create("Travel", listOf("com.example.maps"))
        val before = repository.observeProfiles().first()
        // Fail after the first profile's inserts have run, exercising Room's outer transaction
        // rather than only a pre-write check or one INSERT statement's own rollback.
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_second_profile_assignment
            BEFORE INSERT ON freeze_profile_apps
            WHEN NEW.profileId = $second
            BEGIN
                SELECT RAISE(ABORT, 'assignment write rejected');
            END
            """.trimIndent(),
        )

        val failure = runCatching {
            repository.addApps(setOf(first, second), setOf("com.example.new", "com.example.another"))
        }.exceptionOrNull()

        assertTrue("Expected the injected SQLite constraint failure, got $failure", failure is SQLiteConstraintException)
        assertEquals(before, repository.observeProfiles().first())
    }

    @Test
    fun `invalid selections are rejected without modifying profiles`() = runTest {
        val profile = repository.create("Games", listOf("com.example.existing"))
        val before = repository.observeProfiles().first()
        val invalidSelections = listOf(
            emptySet<Long>() to setOf("com.example.new"),
            setOf(0L, profile) to setOf("com.example.new"),
            setOf(-1L, profile) to setOf("com.example.new"),
            setOf(profile) to emptySet(),
            setOf(profile) to setOf(""),
            setOf(profile) to setOf(" "),
            setOf(profile) to setOf("com.example.new", "com..invalid"),
            setOf(profile) to setOf("com.example.new", "com.example bad"),
        )

        invalidSelections.forEach { (profileIds, packageNames) ->
            val failure = runCatching { repository.addApps(profileIds, packageNames) }.exceptionOrNull()
            assertTrue("Expected invalid input to fail, got $failure", failure is IllegalArgumentException)
            assertEquals(before, repository.observeProfiles().first())
        }
    }
}
