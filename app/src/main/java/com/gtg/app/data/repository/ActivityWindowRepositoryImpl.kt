package com.gtg.app.data.repository

import com.gtg.app.data.local.dao.ActivityWindowDao
import com.gtg.app.data.mapper.toDomain
import com.gtg.app.data.mapper.toEntity
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.repository.ActivityWindowRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ActivityWindowRepositoryImpl @Inject constructor(
    private val dao: ActivityWindowDao,
) : ActivityWindowRepository {

    override suspend fun getActiveWindow(): ActivityWindow? =
        dao.getActiveWindow()?.toDomain()

    override fun observeActiveWindow(): Flow<ActivityWindow?> =
        dao.observeActiveWindow().map { it?.toDomain() }

    override suspend fun save(window: ActivityWindow): Long {
        val entity = window.toEntity()
        return if (entity.id == 0L) {
            dao.insert(entity)
        } else {
            dao.update(entity)
            entity.id
        }
    }
}
