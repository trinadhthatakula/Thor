package com.valhalla.thor.domain.repository

import com.valhalla.thor.domain.model.HistoryRecord
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun getHistory(): Flow<List<HistoryRecord>>
    suspend fun addRecord(record: HistoryRecord)
    suspend fun clearHistory()
}