package com.gtg.app.domain.repository

import com.gtg.app.domain.model.ActivityWindow
import kotlinx.coroutines.flow.Flow

interface ActivityWindowRepository {
    suspend fun getActiveWindow(): ActivityWindow?
    fun observeActiveWindow(): Flow<ActivityWindow?>
    suspend fun save(window: ActivityWindow): Long
}
