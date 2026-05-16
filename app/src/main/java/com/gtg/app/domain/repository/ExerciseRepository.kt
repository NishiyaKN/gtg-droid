package com.gtg.app.domain.repository

import com.gtg.app.domain.model.Exercise
import kotlinx.coroutines.flow.Flow

interface ExerciseRepository {
    fun observeActiveExercises(): Flow<List<Exercise>>
    fun observeAllExercises(): Flow<List<Exercise>>
    suspend fun getActiveExercises(): List<Exercise>
    suspend fun getById(id: Long): Exercise?
    suspend fun insert(exercise: Exercise): Long
    suspend fun update(exercise: Exercise)
    suspend fun delete(exercise: Exercise)
}
