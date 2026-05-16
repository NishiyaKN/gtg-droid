package com.gtg.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inactivity_blocks")
data class InactivityBlockEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String = "",

    /** Hora de início do bloco (0-23). */
    @ColumnInfo(name = "start_hour")
    val startHour: Int,

    /** Minuto de início do bloco (0-59). */
    @ColumnInfo(name = "start_minute")
    val startMinute: Int,

    /** Hora de fim do bloco (0-23). */
    @ColumnInfo(name = "end_hour")
    val endHour: Int,

    /** Minuto de fim do bloco (0-59). */
    @ColumnInfo(name = "end_minute")
    val endMinute: Int,

    /** Data específica para blocos NONE — formato "yyyy-MM-dd". Null para recorrentes. */
    @ColumnInfo(name = "specific_date")
    val specificDate: String? = null,

    /** NONE | DAILY | WEEKLY | MONTHLY */
    @ColumnInfo(name = "recurrence")
    val recurrence: String,

    /**
     * Dias da semana para recorrência WEEKLY.
     * Armazenado como CSV dos valores ordinal de DayOfWeek (1=Monday .. 7=Sunday).
     * Ex: "1,3,5" = Monday, Wednesday, Friday.
     */
    @ColumnInfo(name = "recurrence_days")
    val recurrenceDays: String? = null,

    /** Dia do mês para recorrência MONTHLY (1-31). */
    @ColumnInfo(name = "day_of_month")
    val dayOfMonth: Int? = null,
)
