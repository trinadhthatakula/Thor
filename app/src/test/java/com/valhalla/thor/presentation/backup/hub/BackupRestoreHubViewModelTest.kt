// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup.hub

import com.valhalla.thor.data.source.local.room.AppDao
import com.valhalla.thor.data.source.local.room.AppEntity
import com.valhalla.thor.data.source.local.room.PackageInstallSize
import com.valhalla.thor.domain.repository.BackupArchiveItem
import com.valhalla.thor.domain.repository.BackupArchiveKind
import com.valhalla.thor.domain.repository.BackupArchiveScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreHubViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeScanner(var items: List<BackupArchiveItem> = emptyList()) : BackupArchiveScanner {
        var deletedItems = mutableListOf<BackupArchiveItem>()

        override fun scanBackups(): Flow<List<BackupArchiveItem>> = flowOf(items)

        override suspend fun deleteArchive(item: BackupArchiveItem): Boolean {
            deletedItems.add(item)
            items = items.filter { it.id != item.id }
            return true
        }
    }

    private class FakeAppDao(private val apps: List<AppEntity> = emptyList()) : AppDao {
        override fun getAllAppsFlow(): Flow<List<AppEntity>> = flowOf(apps)
        override suspend fun getAllApps(): List<AppEntity> = apps
        override suspend fun getApp(packageName: String): AppEntity? = apps.find { it.packageName == packageName }
        override suspend fun insertApps(apps: List<AppEntity>) {}
        override suspend fun deleteApp(packageName: String) {}
        override suspend fun getInstallSizes(packages: List<String>): List<PackageInstallSize> = emptyList()
        override suspend fun updateInstallSize(packageName: String, size: Long?) {}
        override suspend fun clearAll() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createItem(
        id: Long,
        name: String,
        kind: BackupArchiveKind,
        size: Long = 1024,
    ): BackupArchiveItem = BackupArchiveItem(
        id = id,
        uriString = "content://downloads/$id",
        displayName = name,
        packageName = "com.test.app",
        sizeBytes = size,
        dateModifiedEpochSec = 1000L + id,
        kind = kind,
        extension = if (kind == BackupArchiveKind.DATA_BACKUP) "thorbak" else "xapk",
    )

    @Test
    fun dynamicFilterChips_onlyShownWhenBothBackupsAndBundlesExist() = runTest(testDispatcher) {
        val scannerOnlyBackups = FakeScanner(
            listOf(createItem(1, "app.thorbak", BackupArchiveKind.DATA_BACKUP))
        )
        val vm1 = BackupRestoreHubViewModel(scannerOnlyBackups, FakeAppDao())
        advanceUntilIdle()
        assertFalse("Only backups present -> no filter chips", vm1.state.value.showFilterChips)

        val scannerOnlyBundles = FakeScanner(
            listOf(createItem(2, "app.xapk", BackupArchiveKind.APP_BUNDLE))
        )
        val vm2 = BackupRestoreHubViewModel(scannerOnlyBundles, FakeAppDao())
        advanceUntilIdle()
        assertFalse("Only bundles present -> no filter chips", vm2.state.value.showFilterChips)

        val scannerBoth = FakeScanner(
            listOf(
                createItem(1, "app.thorbak", BackupArchiveKind.DATA_BACKUP),
                createItem(2, "app.xapk", BackupArchiveKind.APP_BUNDLE),
            )
        )
        val vm3 = BackupRestoreHubViewModel(scannerBoth, FakeAppDao())
        advanceUntilIdle()
        assertTrue("Both present -> filter chips shown", vm3.state.value.showFilterChips)
    }

    @Test
    fun filtering_filtersItemsCorrectly() = runTest(testDispatcher) {
        val backup = createItem(1, "backup.thorbak", BackupArchiveKind.DATA_BACKUP)
        val bundle = createItem(2, "bundle.xapk", BackupArchiveKind.APP_BUNDLE)
        val scanner = FakeScanner(listOf(backup, bundle))
        val vm = BackupRestoreHubViewModel(scanner, FakeAppDao())
        advanceUntilIdle()

        assertEquals(2, vm.state.value.filteredArchives.size)

        vm.setFilter(BackupHubFilter.DATA_BACKUPS)
        assertEquals(listOf(backup), vm.state.value.filteredArchives)

        vm.setFilter(BackupHubFilter.APP_BUNDLES)
        assertEquals(listOf(bundle), vm.state.value.filteredArchives)

        vm.setFilter(BackupHubFilter.ALL)
        assertEquals(listOf(backup, bundle), vm.state.value.filteredArchives)
    }

    private fun createAppEntity(
        packageName: String,
        appName: String,
        isSystem: Boolean = false,
    ) = AppEntity(
        packageName = packageName,
        appName = appName,
        versionName = "1.0",
        versionCode = 1L,
        minSdk = 26,
        targetSdk = 34,
        isSystem = isSystem,
        installerPackageName = null,
        publicSourceDir = null,
        splitPublicSourceDirs = emptyList(),
        enabled = true,
        dataDir = null,
        nativeLibraryDir = null,
        deviceProtectedDataDir = null,
        sharedLibraryFiles = null,
        obbFilePath = null,
        sourceDir = null,
        sharedDataDir = "",
        lastUpdateTime = 0L,
        firstInstallTime = 0L,
        isDebuggable = false,
        isSuspended = false,
        installSize = null,
    )

    @Test
    fun appPickerSearch_filtersAppsByNameAndPackage() = runTest(testDispatcher) {
        val app1 = createAppEntity(packageName = "com.spotify.music", appName = "Spotify", isSystem = false)
        val app2 = createAppEntity(packageName = "org.telegram.messenger", appName = "Telegram", isSystem = false)
        val dao = FakeAppDao(listOf(app1, app2))
        val vm = BackupRestoreHubViewModel(FakeScanner(), dao)
        advanceUntilIdle()

        assertEquals(2, vm.state.value.installedApps.size)

        vm.updateAppPickerSearch("Spot")
        assertEquals(listOf(app1), vm.state.value.filteredInstalledApps)

        vm.updateAppPickerSearch("telegram")
        assertEquals(listOf(app2), vm.state.value.filteredInstalledApps)

        vm.updateAppPickerSearch("org.")
        assertEquals(listOf(app2), vm.state.value.filteredInstalledApps)

        vm.updateAppPickerSearch("")
        assertEquals(2, vm.state.value.filteredInstalledApps.size)
    }

    @Test
    fun archiveDeletion_removesItemAndRefreshes() = runTest(testDispatcher) {
        val item = createItem(1, "app.thorbak", BackupArchiveKind.DATA_BACKUP)
        val scanner = FakeScanner(listOf(item))
        val vm = BackupRestoreHubViewModel(scanner, FakeAppDao())
        advanceUntilIdle()

        assertEquals(1, vm.state.value.archives.size)

        vm.requestDeleteArchive(item)
        assertEquals(item, vm.state.value.archiveToDelete)

        vm.confirmDeleteArchive()
        advanceUntilIdle()

        assertEquals(listOf(item), scanner.deletedItems)
        assertTrue(vm.state.value.archives.isEmpty())
    }
}
