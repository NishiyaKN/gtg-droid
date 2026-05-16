package com.gtg.app.domain.model

import kotlin.math.roundToInt

data class Exercise(
    val id: Long = 0,
    val name: String,
    val maxReps: Int,
    val targetPercentage: Int,
    val isActive: Boolean = true,
) {
    /** Reps alvo calculadas: maxReps * targetPercentage / 100, arredondado. */
    val targetReps: Int
        get() = (maxReps * targetPercentage / 100.0).roundToInt()
}
