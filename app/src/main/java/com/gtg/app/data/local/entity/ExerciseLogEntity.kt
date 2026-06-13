package com.gtg.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "exercise_logs",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id"],
            childColumns = ["exercise_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // Index em timestamp: StatisticsViewModel dispara 13 queries com range
    // WHERE timestamp >= :start AND timestamp < :end a cada mudança de log —
    // sem o índice, cada uma é full scan.
    indices = [Index("exercise_id"), Index("timestamp")],
)
data class ExerciseLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "exercise_id")
    val exerciseId: Long,

    /** Epoch milliseconds (UTC) do momento em que o check foi feito. */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "reps_completed")
    val repsCompleted: Int,
)
