package com.gtg.app.domain.usecase

import com.gtg.app.domain.model.Exercise
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Helpers para rotação round-robin entre os exercícios ativos.
 *
 * **Stateless por design:** o índice atual é inferido a partir do `currentExerciseId`
 * (lido de SessionPreferences). Isso significa que:
 * - Adicionar/remover/desativar exercícios entre alarmes é transparente — o próximo
 *   sempre é o próximo no array atual.
 * - Se o exercício atual saiu da lista ativa (deletado/desativado), retorna o
 *   primeiro da lista — recomeça o ciclo.
 *
 * @return próximo exercício da rotação, ou `null` se a lista estiver vazia.
 */
fun pickNextExerciseInRotation(
    currentExerciseId: Long,
    activeExercises: List<Exercise>,
): Exercise? {
    if (activeExercises.isEmpty()) return null
    val currentIndex = activeExercises.indexOfFirst { it.id == currentExerciseId }
    val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % activeExercises.size
    return activeExercises[nextIndex]
}

/**
 * Acha o próximo [LocalDate] ESTRITAMENTE depois de [after] cujo `dayOfWeek`
 * pertence a [activeDaysOfWeek]. Caminha no máximo 7 dias.
 *
 * Compartilhado entre [DynamicSchedulerUseCase] (roll-over normal),
 * [com.gtg.app.presentation.home.HomeViewModel] (roll-over de fim de janela)
 * e [com.gtg.app.presentation.alarm.BootReceiver] (validação pós-reboot),
 * para garantir que **todos** os caminhos que produzem datas futuras respeitem
 * o filtro de dias da semana.
 *
 * Se todos os 7 dias estiverem inativos (config patológica — a UI deve
 * impedir isso, mas defensivamente), cai para `after + 1` para não travar
 * o scheduler indefinidamente.
 */
fun findNextActiveDate(
    after: LocalDate,
    activeDaysOfWeek: Set<DayOfWeek>,
): LocalDate {
    repeat(7) { offset ->
        val candidate = after.plusDays(offset.toLong() + 1)
        if (candidate.dayOfWeek in activeDaysOfWeek) return candidate
    }
    return after.plusDays(1)
}
