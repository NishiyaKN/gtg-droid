package com.gtg.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gtg.app.data.local.entity.ExerciseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {

    @Query("SELECT * FROM exercises WHERE is_active = 1 ORDER BY name ASC")
    fun observeActiveExercises(): Flow<List<ExerciseEntity>>

    /** Snapshot one-shot da lista de exercícios ativos. Usado pelos schedulers
     *  que precisam escolher o próximo exercício da rotação sem manter Flow vivo. */
    @Query("SELECT * FROM exercises WHERE is_active = 1 ORDER BY name ASC")
    suspend fun getActiveExercises(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises ORDER BY name ASC")
    fun observeAllExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): ExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exercise: ExerciseEntity): Long

    @Update
    suspend fun update(exercise: ExerciseEntity)

    @Delete
    suspend fun delete(exercise: ExerciseEntity)
}
