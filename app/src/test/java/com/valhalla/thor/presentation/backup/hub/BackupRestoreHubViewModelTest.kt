// SPDX-FileCopyrightText: 2025-2026 Trinadh Thatakula <github.com/trinadhthatakula/Thor>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.valhalla.thor.presentation.backup.hub

import com.valhalla.thor.domain.model.AppInfo
import com.valhalla.thor.domain.model.DetailedAppInfo
import com.valhalla.thor.domain.repository.AppRepository
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

import com.valhalla.thor.presentation.FakeApplication
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreHubViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakeApplication = FakeApplication(File("/tmp"))

    private class FakeScanner(var items: List<BackupArchiveItem> = emptyList()) : BackupArchiveScanner {
        var deletedItems = mutableListOf<BackupArchiveItem>()

        override fun scanBackups(): Flow<List<BackupArchiveItem>> = flowOf(items)

        override suspend fun deleteArchive(item: BackupArchiveItem): Boolean {
            deletedItems.add(item)
            items = items.filter { it.id != item.id }
            return true
        }
    }

    private class FakeAppRepository(private val apps: List<AppInfo> = emptyList()) : AppRepository {
        override fun getAllApps(): Flow<List<AppInfo>> = flowOf(apps)
        override suspend fun getAppDetails(packageName: String): AppInfo? = apps.find { it.packageName == packageName }
        override suspend fun getDetailedAppInfo(packageName: String): DetailedAppInfo? = null
        override suspend fun getApkDetails(apkPath: String): AppInfo? = null
        override suspend fun updateInstallSizes(sizes: Map<String, Long>) = Unit
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
        val vm1 = BackupRestoreHubViewModel(scannerOnlyBackups, FakeAppRepository(), fakeApplication)
        advanceUntilIdle()
        assertFalse("Only backups present -> no filter chips", vm1.state.value.showFilterChips)

        val scannerOnlyBundles = FakeScanner(
            listOf(createItem(2, "app.xapk", BackupArchiveKind.APP_BUNDLE))
        )
        val vm2 = BackupRestoreHubViewModel(scannerOnlyBundles, FakeAppRepository(), fakeApplication)
        advanceUntilIdle()
        assertFalse("Only bundles present -> no filter chips", vm2.state.value.showFilterChips)

        val scannerBoth = FakeScanner(
            listOf(
                createItem(1, "app.thorbak", BackupArchiveKind.DATA_BACKUP),
                createItem(2, "app.xapk", BackupArchiveKind.APP_BUNDLE),
            )
        )
        val vm3 = BackupRestoreHubViewModel(scannerBoth, FakeAppRepository(), fakeApplication)
        advanceUntilIdle()
        assertTrue("Both present -> filter chips shown", vm3.state.value.showFilterChips)
    }

    @Test
    fun filtering_filtersItemsCorrectly() = runTest(testDispatcher) {
        val backup = createItem(1, "backup.thorbak", BackupArchiveKind.DATA_BACKUP)
        val bundle = createItem(2, "bundle.xapk", BackupArchiveKind.APP_BUNDLE)
        val scanner = FakeScanner(listOf(backup, bundle))
        val vm = BackupRestoreHubViewModel(scanner, FakeAppRepository(), fakeApplication)
        advanceUntilIdle()

        assertEquals(2, vm.state.value.filteredArchives.size)

        vm.setFilter(BackupHubFilter.DATA_BACKUPS)
        assertEquals(listOf(backup), vm.state.value.filteredArchives)

        vm.setFilter(BackupHubFilter.APP_BUNDLES)
        assertEquals(listOf(bundle), vm.state.value.filteredArchives)

        vm.setFilter(BackupHubFilter.ALL)
        assertEquals(listOf(backup, bundle), vm.state.value.filteredArchives)
    }

    private fun createAppInfo(
        packageName: String,
        appName: String,
        isSystem: Boolean = false,
    ) = AppInfo(
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
        val app1 = createAppInfo(packageName = "com.spotify.music", appName = "Spotify", isSystem = false)
        val app2 = createAppInfo(packageName = "org.telegram.messenger", appName = "Telegram", isSystem = false)
        val repo = FakeAppRepository(listOf(app1, app2))
        val vm = BackupRestoreHubViewModel(FakeScanner(), repo, fakeApplication)
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
        val vm = BackupRestoreHubViewModel(scanner, FakeAppRepository(), fakeApplication)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.archives.size)

        vm.requestDeleteArchive(item)
        assertEquals(item, vm.state.value.archiveToDelete)

        vm.confirmDeleteArchive()
        advanceUntilIdle()

        assertEquals(listOf(item), scanner.deletedItems)
        assertTrue(vm.state.value.archives.isEmpty())
    }

    @Test
    fun archiveDeletion_resetsFilterToAllWhenNoMatchingItemsRemain() = runTest(testDispatcher) {
        val backupItem = createItem(1, "app.thorbak", BackupArchiveKind.DATA_BACKUP)
        val bundleItem = createItem(2, "app.xapk", BackupArchiveKind.APP_BUNDLE)
        val scanner = FakeScanner(listOf(backupItem, bundleItem))
        val vm = BackupRestoreHubViewModel(scanner, FakeAppRepository(), fakeApplication)
        advanceUntilIdle()

        vm.setFilter(BackupHubFilter.DATA_BACKUPS)
        assertEquals(BackupHubFilter.DATA_BACKUPS, vm.state.value.activeFilter)

        // Delete the only data backup
        vm.requestDeleteArchive(backupItem)
        vm.confirmDeleteArchive()
        advanceUntilIdle()

        assertEquals(listOf(bundleItem), vm.state.value.archives)
        assertEquals(BackupHubFilter.ALL, vm.state.value.activeFilter)
    }

    @Test
    fun refreshArchives_cancelsPreviousScan() = runTest(testDispatcher) {
        val flow1 = kotlinx.coroutines.flow.MutableSharedFlow<List<BackupArchiveItem>>(replay = 1)
        val flow2 = kotlinx.coroutines.flow.MutableSharedFlow<List<BackupArchiveItem>>(replay = 1)
        var flowIndex = 0
        val dynamicScanner = object : BackupArchiveScanner {
            override fun scanBackups(): Flow<List<BackupArchiveItem>> {
                return if (flowIndex++ == 0) flow1 else flow2
            }
            override suspend fun deleteArchive(item: BackupArchiveItem) = true
        }

        val vm = BackupRestoreHubViewModel(dynamicScanner, FakeAppRepository(), fakeApplication)
        advanceUntilIdle()

        // Trigger a second scan
        vm.refreshArchives()
        advanceUntilIdle()

        val item1 = createItem(1, "item1.thorbak", BackupArchiveKind.DATA_BACKUP)
        val item2 = createItem(2, "item2.thorbak", BackupArchiveKind.DATA_BACKUP)

        flow2.emit(listOf(item2))
        advanceUntilIdle()
        assertEquals(listOf(item2), vm.state.value.archives)

        // Stale emission from first scan must not overwrite state
        flow1.emit(listOf(item1))
        advanceUntilIdle()
        assertEquals(listOf(item2), vm.state.value.archives)
    }
}
