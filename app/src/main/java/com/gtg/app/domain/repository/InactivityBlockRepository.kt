package com.gtg.app.domain.repository

import com.gtg.app.domain.model.InactivityBlock
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface InactivityBlockRepository {
    fun observeAll(): Flow<List<InactivityBlock>>
    suspend fun getBlocksActiveOn(date: LocalDate): List<InactivityBlock>
    suspend fun getById(id: Long): InactivityBlock?
    suspend fun insert(block: InactivityBlock): Long
    suspend fun update(block: InactivityBlock)
    suspend fun delete(block: InactivityBlock)
}
