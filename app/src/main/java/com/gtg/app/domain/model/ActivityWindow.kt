package com.gtg.app.domain.model

import java.time.LocalTime

data class ActivityWindow(
    val id: Long = 0,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val isActive: Boolean = true,
)
