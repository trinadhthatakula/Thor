package com.valhalla.thor.data.manager

import com.valhalla.thor.data.source.local.room.ExtensionDataDao
import com.valhalla.thor.data.source.local.room.ExtensionDataEntity
import com.valhalla.thor.extension.api.ExtensionDataStore

class RoomExtensionDataStore(
    private val packageName: String,
    private val extensionDataDao: ExtensionDataDao
) : ExtensionDataStore {
    override fun saveString(key: String, value: String) {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            extensionDataDao.insert(ExtensionDataEntity(packageName, key, value))
        }
    }

    override fun getString(key: String): String? {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            extensionDataDao.getValue(packageName, key)
        }
    }

    override fun deleteString(key: String) {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            extensionDataDao.delete(packageName, key)
        }
    }

    override fun getAllKeys(): List<String> {
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            extensionDataDao.getAllKeys(packageName)
        }
    }
}
