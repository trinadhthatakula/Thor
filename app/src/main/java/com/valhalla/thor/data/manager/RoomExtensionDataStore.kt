package com.valhalla.thor.data.manager

import com.valhalla.thor.data.source.local.room.ExtensionDataDao
import com.valhalla.thor.data.source.local.room.ExtensionDataEntity
import com.valhalla.thor.extension.api.ExtensionDataStore

class RoomExtensionDataStore(
    private val packageName: String,
    private val extensionDataDao: ExtensionDataDao
) : ExtensionDataStore {
    // The DAO functions are 'suspend' (Room coroutines contract): Room dispatches them on its
    // own executor, so they are main-safe and never block the caller's thread.
    override suspend fun saveString(key: String, value: String) =
        extensionDataDao.insert(ExtensionDataEntity(packageName, key, value))

    override suspend fun getString(key: String): String? =
        extensionDataDao.getValue(packageName, key)

    override suspend fun deleteString(key: String) =
        extensionDataDao.delete(packageName, key)

    override suspend fun getAllKeys(): List<String> =
        extensionDataDao.getAllKeys(packageName)
}
