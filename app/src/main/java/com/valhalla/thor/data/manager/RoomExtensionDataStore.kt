package com.valhalla.thor.data.manager

import com.valhalla.thor.data.source.local.room.ExtensionDataDao
import com.valhalla.thor.data.source.local.room.ExtensionDataEntity
import com.valhalla.thor.extension.api.ExtensionDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomExtensionDataStore(
    private val packageName: String,
    private val extensionDataDao: ExtensionDataDao
) : ExtensionDataStore {
    // suspend + withContext(IO): never blocks the caller's thread (the Room DAO calls are blocking).
    override suspend fun saveString(key: String, value: String) = withContext(Dispatchers.IO) {
        extensionDataDao.insert(ExtensionDataEntity(packageName, key, value))
    }

    override suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        extensionDataDao.getValue(packageName, key)
    }

    override suspend fun deleteString(key: String) = withContext(Dispatchers.IO) {
        extensionDataDao.delete(packageName, key)
    }

    override suspend fun getAllKeys(): List<String> = withContext(Dispatchers.IO) {
        extensionDataDao.getAllKeys(packageName)
    }
}
