package com.gtg.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gtg.app.data.local.entity.ActivityWindowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityWindowDao {

    @Query("SELECT * FROM activity_windows WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveWindow(): ActivityWindowEntity?

    @Query("SELECT * FROM activity_windows WHERE is_active = 1 LIMIT 1")
    fun observeActiveWindow(): Flow<ActivityWindowEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(window: ActivityWindowEntity): Long

    @Update
    suspend fun update(window: ActivityWindowEntity)
}
