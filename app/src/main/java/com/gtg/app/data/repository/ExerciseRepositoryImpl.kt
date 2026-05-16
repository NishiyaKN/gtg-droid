package com.gtg.app.data.repository

import com.gtg.app.data.local.dao.ExerciseDao
import com.gtg.app.data.mapper.toDomain
import com.gtg.app.data.mapper.toEntity
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.repository.ExerciseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ExerciseRepositoryImpl @Inject constructor(
    private val dao: ExerciseDao,
) : ExerciseRepository {

    override fun observeActiveExercises(): Flow<List<Exercise>> =
        dao.observeActiveExercises().map { list -> list.map { it.toDomain() } }

    override fun observeAllExercises(): Flow<List<Exercise>> =
        dao.observeAllExercises().map { list -> list.map { it.toDomain() } }

    override suspend fun getActiveExercises(): List<Exercise> =
        dao.getActiveExercises().map { it.toDomain() }

    override suspend fun getById(id: Long): Exercise? =
        dao.getById(id)?.toDomain()

    override suspend fun insert(exercise: Exercise): Long =
        dao.insert(exercise.toEntity())

    override suspend fun update(exercise: Exercise) =
        dao.update(exercise.toEntity())

    override suspend fun delete(exercise: Exercise) =
        dao.delete(exercise.toEntity())
}
