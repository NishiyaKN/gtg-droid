package com.gtg.app.data.local.converter

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * TypeConverters para Room.
 * Converte LocalDateTime <-> Long (epoch millis em UTC) para colunas timestamp.
 */
class Converters {

    @TypeConverter
    fun fromLocalDateTime(dateTime: LocalDateTime?): Long? =
        dateTime?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()

    @TypeConverter
    fun toLocalDateTime(epochMilli: Long?): LocalDateTime? =
        epochMilli?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
}
