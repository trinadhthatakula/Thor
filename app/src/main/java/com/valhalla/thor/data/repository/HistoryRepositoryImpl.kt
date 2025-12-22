package com.valhalla.thor.data.repository

import android.content.Context
import com.valhalla.thor.data.db.ThorDatabase
import com.valhalla.thor.data.db.toEntity
import com.valhalla.thor.domain.model.HistoryRecord
import com.valhalla.thor.domain.repository.HistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class HistoryRepositoryImpl(
    private val db: ThorDatabase
) : HistoryRepository {

    override fun getHistory(): Flow<List<HistoryRecord>> {
        return db.historyDao().getAll().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun addRecord(record: HistoryRecord) = withContext(Dispatchers.IO) {
        db.historyDao().insert(record.toEntity())
    }

    override suspend fun clearHistory() = withContext(Dispatchers.IO) {
        db.historyDao().deleteAll()
    }
}