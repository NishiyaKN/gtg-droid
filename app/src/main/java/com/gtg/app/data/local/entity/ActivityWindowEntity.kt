package com.gtg.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activity_windows")
data class ActivityWindowEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Formato "HH:mm" — ex: "09:00". */
    @ColumnInfo(name = "start_time")
    val startTime: String,

    /** Formato "HH:mm" — ex: "17:00". */
    @ColumnInfo(name = "end_time")
    val endTime: String,

    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,
)
