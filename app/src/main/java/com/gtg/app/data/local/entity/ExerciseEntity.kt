package com.gtg.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "max_reps")
    val maxReps: Int,

    /** Porcentagem alvo em inteiro: 50 = 50%. */
    @ColumnInfo(name = "target_percentage")
    val targetPercentage: Int,

    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,
)
