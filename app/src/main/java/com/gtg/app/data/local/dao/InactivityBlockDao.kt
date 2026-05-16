package com.gtg.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gtg.app.data.local.entity.InactivityBlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InactivityBlockDao {

    @Query("SELECT * FROM inactivity_blocks ORDER BY start_hour ASC, start_minute ASC")
    fun observeAll(): Flow<List<InactivityBlockEntity>>

    @Query("SELECT * FROM inactivity_blocks")
    suspend fun getAll(): List<InactivityBlockEntity>

    @Query("SELECT * FROM inactivity_blocks WHERE id = :id")
    suspend fun getById(id: Long): InactivityBlockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(block: InactivityBlockEntity): Long

    @Update
    suspend fun update(block: InactivityBlockEntity)

    @Delete
    suspend fun delete(block: InactivityBlockEntity)
}
