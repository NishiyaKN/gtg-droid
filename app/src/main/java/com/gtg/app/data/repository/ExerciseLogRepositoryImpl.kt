package com.gtg.app.data.repository

import com.gtg.app.data.local.dao.ExerciseLogDao
import com.gtg.app.data.mapper.toDomain
import com.gtg.app.data.mapper.toEntity
import com.gtg.app.domain.model.ExerciseLog
import com.gtg.app.domain.repository.ExerciseLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

class ExerciseLogRepositoryImpl @Inject constructor(
    private val dao: ExerciseLogDao,
) : ExerciseLogRepository {

    override suspend fun insert(log: ExerciseLog): Long =
        dao.insert(log.toEntity())

    override fun observeAll(): Flow<List<ExerciseLog>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeByExercise(exerciseId: Long): Flow<List<ExerciseLog>> =
        dao.observeByExercise(exerciseId).map { list -> list.map { it.toDomain() } }

    override suspend fun getLogsForDay(date: LocalDate): List<ExerciseLog> {
        val (start, end) = dayBoundsMillis(date)
        return dao.getLogsForDay(start, end).map { it.toDomain() }
    }

    override suspend fun getLogsBetween(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<ExerciseLog> {
        val startMillis = startDate.atStartOfDay().toEpochMilli()
        val endMillis = endDate.plusDays(1).atStartOfDay().toEpochMilli()
        return dao.getLogsBetween(startMillis, endMillis).map { it.toDomain() }
    }

    override suspend fun totalRepsBetween(startDate: LocalDate, endDate: LocalDate): Int =
        dao.totalRepsBetween(
            startMillis = startDate.atStartOfDay().toEpochMilli(),
            endMillis = endDate.plusDays(1).atStartOfDay().toEpochMilli(),
        )

    override suspend fun totalSetsBetween(startDate: LocalDate, endDate: LocalDate): Int =
        dao.totalSetsBetween(
            startMillis = startDate.atStartOfDay().toEpochMilli(),
            endMillis = endDate.plusDays(1).atStartOfDay().toEpochMilli(),
        )

    override suspend fun getLastLog(): ExerciseLog? =
        dao.getLastLog()?.toDomain()

    private fun dayBoundsMillis(date: LocalDate): Pair<Long, Long> {
        val start = date.atStartOfDay().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay().toEpochMilli()
        return start to end
    }

    private fun java.time.LocalDateTime.toEpochMilli(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
