package com.gtg.app.data.mapper

import com.gtg.app.data.local.entity.ActivityWindowEntity
import com.gtg.app.data.local.entity.ExerciseEntity
import com.gtg.app.data.local.entity.ExerciseLogEntity
import com.gtg.app.data.local.entity.InactivityBlockEntity
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.model.ExerciseLog
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.Recurrence
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ──────────────────────────────────────────────
// Exercise
// ──────────────────────────────────────────────

fun ExerciseEntity.toDomain(): Exercise = Exercise(
    id = id,
    name = name,
    maxReps = maxReps,
    targetPercentage = targetPercentage,
    isActive = isActive,
)

fun Exercise.toEntity(): ExerciseEntity = ExerciseEntity(
    id = id,
    name = name,
    maxReps = maxReps,
    targetPercentage = targetPercentage,
    isActive = isActive,
)

// ──────────────────────────────────────────────
// ExerciseLog
// ──────────────────────────────────────────────

fun ExerciseLogEntity.toDomain(): ExerciseLog = ExerciseLog(
    id = id,
    exerciseId = exerciseId,
    timestamp = Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime(),
    repsCompleted = repsCompleted,
)

fun ExerciseLog.toEntity(): ExerciseLogEntity = ExerciseLogEntity(
    id = id,
    exerciseId = exerciseId,
    timestamp = timestamp
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli(),
    repsCompleted = repsCompleted,
)

// ──────────────────────────────────────────────
// ActivityWindow
// ──────────────────────────────────────────────

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

fun ActivityWindowEntity.toDomain(): ActivityWindow = ActivityWindow(
    id = id,
    startTime = LocalTime.parse(startTime, TIME_FORMAT),
    endTime = LocalTime.parse(endTime, TIME_FORMAT),
    isActive = isActive,
)

fun ActivityWindow.toEntity(): ActivityWindowEntity = ActivityWindowEntity(
    id = id,
    startTime = startTime.format(TIME_FORMAT),
    endTime = endTime.format(TIME_FORMAT),
    isActive = isActive,
)

// ──────────────────────────────────────────────
// InactivityBlock
// ──────────────────────────────────────────────

fun InactivityBlockEntity.toDomain(): InactivityBlock = InactivityBlock(
    id = id,
    title = title,
    startTime = LocalTime.of(startHour, startMinute),
    endTime = LocalTime.of(endHour, endMinute),
    specificDate = specificDate?.let { LocalDate.parse(it) },
    recurrence = Recurrence.valueOf(recurrence),
    recurrenceDays = recurrenceDays
        ?.split(",")
        ?.filter { it.isNotBlank() }
        ?.map { DayOfWeek.of(it.trim().toInt()) }
        ?.toSet()
        ?: emptySet(),
    dayOfMonth = dayOfMonth,
)

fun InactivityBlock.toEntity(): InactivityBlockEntity = InactivityBlockEntity(
    id = id,
    title = title,
    startHour = startTime.hour,
    startMinute = startTime.minute,
    endHour = endTime.hour,
    endMinute = endTime.minute,
    specificDate = specificDate?.toString(),
    recurrence = recurrence.name,
    recurrenceDays = recurrenceDays
        .takeIf { it.isNotEmpty() }
        ?.joinToString(",") { it.value.toString() },
    dayOfMonth = dayOfMonth,
)
