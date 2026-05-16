package com.gtg.app.domain.model

/**
 * Agregação de [ExerciseLog]s por exercício em um intervalo de tempo.
 * Usado pelo Dashboard (Home) e por Estatísticas para mostrar o detalhamento
 * de quantas séries e repetições cada exercício teve em um período.
 */
data class ExerciseBreakdown(
    val exerciseId: Long,
    val name: String,
    val sets: Int,
    val totalReps: Int,
)
