package com.gtg.app.domain.usecase

import com.gtg.app.data.local.IntervalMode
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.model.ScheduleResult
import java.time.DayOfWeek
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * Item projetado da rotina do dia. Representa um set futuro estimado.
 *
 * @property time Horário projetado do alarme.
 * @property exerciseName Nome do exercício na rotação.
 * @property targetReps Repetições alvo para esta série.
 * @property isScheduled True se este é o alarme realmente agendado no sistema;
 *           false para projeções subsequentes baseadas em "se cumprir no horário".
 */
data class PlannedSet(
    val time: LocalDateTime,
    val exerciseName: String,
    val targetReps: Int,
    val isScheduled: Boolean,
)

/**
 * Gera a projeção dos próximos alarmes do dia atual, simulando o
 * [DynamicSchedulerUseCase] em cascata: cada alarme assume que o usuário
 * fará Check no horário exato e calcula o próximo. Os exercícios rotacionam
 * round-robin pela lista de ativos.
 *
 * **Importante:** essa projeção é dinâmica — os horários reais mudam conforme
 * o usuário atrasa/antecipa Checks e conforme blocos de inatividade são
 * adicionados. A UI deve comunicar isso (ex: "Projeção — sujeito a mudanças").
 *
 * A simulação para quando:
 * - O próximo cálculo retorna [ScheduleResult.ScheduledTomorrow] (cruzou o dia)
 * - O próximo cálculo retorna [ScheduleResult.NoWindowConfigured]
 * - [maxIterations] é atingido (proteção contra loops)
 */
class PreviewTodayRoutineUseCase @Inject constructor(
    private val dynamicScheduler: DynamicSchedulerUseCase,
) {
    /**
     * @param firstAlarmAt Horário do primeiro alarme (real ou projetado).
     * @param activeExercises Lista de exercícios ativos na ordem de rotação.
     * @param firstExerciseIndex Índice (na lista) do exercício do primeiro alarme.
     *        Tipicamente 0 quando sessão parada, ou o índice do pending quando ativa.
     * @param baseIntervalMinutes Intervalo base configurado.
     * @param isFirstAlarmScheduled True se o primeiro item é o alarme real agendado
     *        (vs. uma projeção "se eu começar agora").
     * @param maxIterations Cap de itens — protege contra ciclos com config patológica.
     */
    suspend operator fun invoke(
        firstAlarmAt: LocalDateTime,
        activeExercises: List<Exercise>,
        firstExerciseIndex: Int,
        baseIntervalMinutes: Long,
        isFirstAlarmScheduled: Boolean,
        activeDaysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
        intervalMode: IntervalMode = IntervalMode.DYNAMIC,
        maxIterations: Int = 12,
    ): List<PlannedSet> {
        if (activeExercises.isEmpty()) return emptyList()

        // "Dia de referência" = dia do primeiro alarme. Importante: NÃO comparar
        // com `today` (LocalDate.now), porque após roll-over o primeiro alarme
        // pode estar agendado para amanhã. Restringir a hoje deixaria a preview
        // vazia mesmo com alarme válido.
        val referenceDate = firstAlarmAt.toLocalDate()
        val result = mutableListOf<PlannedSet>()

        // Normaliza o índice inicial — defensivo contra valores fora do range
        // (ex: lista mudou desde o último agendamento).
        var idx = ((firstExerciseIndex % activeExercises.size) + activeExercises.size) %
            activeExercises.size

        val firstEx = activeExercises[idx]
        result += PlannedSet(
            time = firstAlarmAt,
            exerciseName = firstEx.name,
            targetReps = firstEx.targetReps,
            isScheduled = isFirstAlarmScheduled,
        )

        // Pré-busca window + blocos (manuais + calendar) para o referenceDate
        // UMA vez antes de iterar. Sem isto, cada iteração refetchava as 3
        // dependências (~36 queries Room para 12 iterações). Com pré-busca,
        // são 3 queries no total — restante do loop é puro CPU.
        val deps = dynamicScheduler.preFetchForDate(referenceDate)
            ?: return result // sem window não há mais nada a projetar

        var previousAlarm = firstAlarmAt

        repeat(maxIterations - 1) {
            // Simula "check no horário do alarme anterior" + "agora = aquele momento"
            // para que a regra de descanso mínimo (20min) não seja triggerada
            // erroneamente pelo "agora" real do sistema.
            val nextResult = dynamicScheduler.evaluateWithDependencies(
                checkTime = previousAlarm,
                baseIntervalMinutes = baseIntervalMinutes,
                now = previousAlarm,
                activeDaysOfWeek = activeDaysOfWeek,
                deps = deps,
                intervalMode = intervalMode,
            )

            when (nextResult) {
                is ScheduleResult.Scheduled -> {
                    if (nextResult.dateTime.toLocalDate() != referenceDate) return result
                    idx = (idx + 1) % activeExercises.size
                    val ex = activeExercises[idx]
                    result += PlannedSet(
                        time = nextResult.dateTime,
                        exerciseName = ex.name,
                        targetReps = ex.targetReps,
                        isScheduled = false,
                    )
                    previousAlarm = nextResult.dateTime
                }
                is ScheduleResult.ScheduledTomorrow,
                ScheduleResult.NoWindowConfigured -> return result
            }
        }

        return result
    }
}
