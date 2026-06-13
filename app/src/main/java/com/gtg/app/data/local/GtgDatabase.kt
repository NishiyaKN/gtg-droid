package com.gtg.app.data.local

import androidx.room.AutoMigration
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
    version = 2,
    exportSchema = true,
    // Migrações automáticas a partir dos schemas exportados em app/schemas/.
    // v1→v2: índice em exercise_logs.timestamp (additivo, auto-migrável).
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
    ],
)
@TypeConverters(Converters::class)
abstract class GtgDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun exerciseLogDao(): ExerciseLogDao
    abstract fun activityWindowDao(): ActivityWindowDao
    abstract fun inactivityBlockDao(): InactivityBlockDao
}
