package com.gtg.app.domain.model

import java.time.LocalDateTime

data class ExerciseLog(
    val id: Long = 0,
    val exerciseId: Long,
    val timestamp: LocalDateTime,
    val repsCompleted: Int,
)
