package com.gtg.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.gtg.app.data.local.converter.Converters
import com.gtg.app.data.local.dao.ActivityWindowDao
import com.gtg.app.data.local.dao.ExerciseDao
import com.gtg.app.data.local.dao.ExerciseLogDao
import com.gtg.app.data.local.dao.InactivityBlockDao
import com.gtg.app.data.local.entity.ActivityWindowEntity
import com.gtg.app.data.local.entity.ExerciseEntity
import com.gtg.app.data.local.entity.ExerciseLogEntity
import com.gtg.app.data.local.entity.InactivityBlockEntity

@Database(
    entities = [
        ExerciseEntity::class,
        ExerciseLogEntity::class,
        ActivityWindowEntity::class,
        InactivityBlockEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GtgDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun exerciseLogDao(): ExerciseLogDao
    abstract fun activityWindowDao(): ActivityWindowDao
    abstract fun inactivityBlockDao(): InactivityBlockDao
}
