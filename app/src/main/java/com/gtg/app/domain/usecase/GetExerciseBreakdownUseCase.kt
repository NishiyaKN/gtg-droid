package com.gtg.app.domain.usecase

import com.gtg.app.domain.model.ExerciseBreakdown
import com.gtg.app.domain.repository.ExerciseLogRepository
import com.gtg.app.domain.repository.ExerciseRepository
import java.time.LocalDate
import javax.inject.Inject

/**
 * Agrega [com.gtg.app.domain.model.ExerciseLog]s por exercício em um intervalo,
 * resolvendo nomes via [ExerciseRepository] e ordenando por volume decrescente.
 *
 * Usado pela Home (resumo do dia) e por Estatísticas (detalhamento por período).
 *
 * Custo: 1 query de logs no intervalo + 1 query de exercício por exercicio
 * distinto encontrado. Para volumes típicos GtG (poucos exercícios ativos),
 * o overhead é desprezível e mantém a consulta simples sem JOIN no Room.
 */
class GetExerciseBreakdownUseCase @Inject constructor(
    private val logRepository: ExerciseLogRepository,
    private val exerciseRepository: ExerciseRepository,
) {
    suspend operator fun invoke(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<ExerciseBreakdown> {
        val logs = logRepository.getLogsBetween(startDate, endDate)
        if (logs.isEmpty()) return emptyList()

        return logs
            .groupBy { it.exerciseId }
            .map { (exerciseId, group) ->
                val exercise = exerciseRepository.getById(exerciseId)
                ExerciseBreakdown(
                    exerciseId = exerciseId,
                    name = exercise?.name ?: "Exercício #$exerciseId",
                    sets = group.size,
                    totalReps = group.sumOf { it.repsCompleted },
                )
            }
            .sortedByDescending { it.totalReps }
    }
}
