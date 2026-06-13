package com.gtg.app.data.repository

import com.gtg.app.data.local.dao.InactivityBlockDao
import com.gtg.app.data.mapper.toDomain
import com.gtg.app.data.mapper.toEntity
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.repository.InactivityBlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

class InactivityBlockRepositoryImpl @Inject constructor(
    private val dao: InactivityBlockDao,
) : InactivityBlockRepository {

    override fun observeAll(): Flow<List<InactivityBlock>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getBlocksActiveOn(date: LocalDate): List<InactivityBlock> =
        // SQL poda os NONE de outras datas (categoria que cresce sem limite);
        // o filtro fino de recorrência continua no domínio via isActiveOn.
        dao.getCandidatesForDate(date.toString())
            .map { it.toDomain() }
            .filter { it.isActiveOn(date) }

    override suspend fun getById(id: Long): InactivityBlock? =
        dao.getById(id)?.toDomain()

    override suspend fun insert(block: InactivityBlock): Long =
        dao.insert(block.toEntity())

    override suspend fun update(block: InactivityBlock) =
        dao.update(block.toEntity())

    override suspend fun delete(block: InactivityBlock) =
        dao.delete(block.toEntity())
}
