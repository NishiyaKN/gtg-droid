package com.gtg.app.domain.repository

import com.gtg.app.domain.model.ExerciseLog
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface ExerciseLogRepository {
    suspend fun insert(log: ExerciseLog): Long
    fun observeAll(): Flow<List<ExerciseLog>>

    /** Sinal de mudança barato (COUNT) para observers que descartam o payload. */
    fun observeCount(): Flow<Int>
    fun observeByExercise(exerciseId: Long): Flow<List<ExerciseLog>>
    suspend fun getLogsForDay(date: LocalDate): List<ExerciseLog>
    suspend fun getLogsBetween(startDate: LocalDate, endDate: LocalDate): List<ExerciseLog>
    suspend fun totalRepsBetween(startDate: LocalDate, endDate: LocalDate): Int
    suspend fun totalSetsBetween(startDate: LocalDate, endDate: LocalDate): Int
    suspend fun getLastLog(): ExerciseLog?
}
