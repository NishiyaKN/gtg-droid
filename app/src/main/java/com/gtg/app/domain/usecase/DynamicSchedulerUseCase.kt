package com.gtg.app.domain.usecase

import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.ScheduleResult
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.CalendarEventRepository
import com.gtg.app.domain.repository.InactivityBlockRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/**
 * Algoritmo de agendamento dinâmico GtG.
 *
 * Calcula o próximo horário de alarme respeitando estritamente as 5 regras:
 *
 * 1. **Gatilho Sequencial:** Apenas um alarme ativo por vez. O próximo é calculado
 *    somente quando o usuário faz o "Check" do alarme atual.
 *
 * 2. **Cálculo Base:** `próximo = momentoDoCheck + intervaloBase`.
 *
 * 3. **Descanso Mínimo:** O próximo alarme nunca pode ser antes de `agora + 20min`,
 *    mesmo que o Check tenha sido atrasado.
 *
 * 4. **Colisão com Inatividade:** Se o horário cair dentro de um [InactivityBlock]:
 *    - A menos de 15min do INÍCIO do bloco → antecipa para `inícioBloco - 5min`.
 *    - No meio ou perto do fim → adia para `fimBloco + 5min`.
 *
 * 5. **Fim do Expediente:** Se ultrapassar o fim da ActivityWindow,
 *    agenda para o início da janela do dia seguinte.
 */
class DynamicSchedulerUseCase @Inject constructor(
    private val activityWindowRepository: ActivityWindowRepository,
    private val inactivityBlockRepository: InactivityBlockRepository,
    private val calendarEventRepository: CalendarEventRepository,
) {
    companion object {
        /** Intervalo mínimo absoluto entre check e próximo alarme. */
        const val MINIMUM_REST_MINUTES = 20L

        /** Se o horário calculado cair até 15min após o INÍCIO de um bloco, antecipa. */
        const val INACTIVITY_PROXIMITY_MINUTES = 15L

        /** Buffer de segurança antes/depois de um bloco de inatividade. */
        const val INACTIVITY_BUFFER_MINUTES = 5L

        /** Limite de iterações para resolver colisões em cascata entre blocos. */
        private const val MAX_COLLISION_ITERATIONS = 10
    }

    /**
     * Bundle de dependências pré-buscadas para uma data específica. Permite que
     * callers que iteram sobre o scheduler para a mesma data (ex:
     * [PreviewTodayRoutineUseCase]) evitem refazer fetches Room idênticos.
     *
     * Os blocos pré-buscados aplicam-se à `date` informada. Se uma chamada
     * subsequente landar em data diferente, [evaluateWithDependencies] usará
     * os blocos mesmo assim — o caller é responsável por validar a aderência
     * (preview filtra resultados que cruzam o dia de referência).
     */
    data class PrefetchedDependencies(
        val date: LocalDate,
        val window: ActivityWindow,
        val manualBlocks: List<InactivityBlock>,
        val calendarBlocks: List<InactivityBlock>,
    )

    /**
     * Calcula o próximo alarme.
     *
     * @param checkTime Momento exato em que o usuário apertou "Check" no alarme atual.
     *                  Regra 1 garante que este método só é chamado nesse momento.
     * @param baseIntervalMinutes Intervalo base configurado pelo usuário (ex: 45).
     * @param now Horário atual do sistema. Parâmetro injetável para facilitar testes.
     * @return [ScheduleResult] com o horário do próximo alarme ou indicação de estado.
     */
    suspend fun calculateNextAlarm(
        checkTime: LocalDateTime,
        baseIntervalMinutes: Long,
        now: LocalDateTime = LocalDateTime.now(),
        activeDaysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    ): ScheduleResult {
        // Pre-computa a data alvo para pré-buscar blocos da data correta.
        // Replica o mesmo cálculo do início de [evaluateWithDependencies] mas
        // sem precisar da window — usado só para escolher a data do fetch.
        val initialCandidate = checkTime
            .plusMinutes(baseIntervalMinutes)
            .let { c ->
                val earliest = now.plusMinutes(MINIMUM_REST_MINUTES)
                if (c.isBefore(earliest)) earliest else c
            }
        val targetDate = initialCandidate.toLocalDate()

        val deps = preFetchForDate(targetDate)
            ?: return ScheduleResult.NoWindowConfigured
        return evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = baseIntervalMinutes,
            now = now,
            activeDaysOfWeek = activeDaysOfWeek,
            deps = deps,
        )
    }

    /**
     * Pré-busca window + blocos (manuais + calendar) para [date], em paralelo.
     *
     * Exposto para callers que iteram múltiplas vezes sobre a mesma data — o
     * grande exemplo é [PreviewTodayRoutineUseCase], que simula até 12
     * checks consecutivos no `referenceDate`. Sem esta otimização, cada
     * iteração refetchava window + blocks (~36 queries Room para 12
     * iterações); com a pré-busca, são 3 queries no total.
     *
     * Retorna `null` se não há janela de atividade configurada — o caller
     * deve mapear isso para [ScheduleResult.NoWindowConfigured] ou equivalente.
     */
    suspend fun preFetchForDate(date: LocalDate): PrefetchedDependencies? {
        val window = activityWindowRepository.getActiveWindow() ?: return null
        return coroutineScope {
            val manualDef = async { inactivityBlockRepository.getBlocksActiveOn(date) }
            val calendarDef = async { calendarEventRepository.getBlocksOn(date) }
            PrefetchedDependencies(
                date = date,
                window = window,
                manualBlocks = manualDef.await(),
                calendarBlocks = calendarDef.await(),
            )
        }
    }

    /**
     * Avalia o algoritmo das 5 regras usando dependências já pré-buscadas. Não
     * faz I/O — pode ser chamada repetidamente sem custo de Room.
     *
     * É a engine pura do scheduler; [calculateNextAlarm] e o loop de
     * [PreviewTodayRoutineUseCase] ambos convergem aqui.
     */
    fun evaluateWithDependencies(
        checkTime: LocalDateTime,
        baseIntervalMinutes: Long,
        now: LocalDateTime,
        activeDaysOfWeek: Set<DayOfWeek>,
        deps: PrefetchedDependencies,
    ): ScheduleResult {
        // ────────────────────────────────────────────────────────────────
        // REGRA 2 — Cálculo Base
        // ────────────────────────────────────────────────────────────────
        var candidate = checkTime.plusMinutes(baseIntervalMinutes)

        // ────────────────────────────────────────────────────────────────
        // REGRA 3 — Descanso Mínimo Obrigatório (20 min)
        // ────────────────────────────────────────────────────────────────
        val earliestAllowed = now.plusMinutes(MINIMUM_REST_MINUTES)
        if (candidate.isBefore(earliestAllowed)) {
            candidate = earliestAllowed
        }

        val window = deps.window
        val todayDate = now.toLocalDate()
        val candidateDate = candidate.toLocalDate()
        val windowStartToday = candidateDate.atTime(window.startTime)
        val windowEndToday = candidateDate.atTime(window.endTime)

        if (candidate.isBefore(windowStartToday)) {
            candidate = windowStartToday
        }

        // ────────────────────────────────────────────────────────────────
        // Dia da semana inativo
        // ────────────────────────────────────────────────────────────────
        if (candidateDate.dayOfWeek !in activeDaysOfWeek) {
            return scheduleForNextActiveDay(candidateDate, window.startTime, activeDaysOfWeek)
        }

        // ────────────────────────────────────────────────────────────────
        // REGRA 5 — Fim do Expediente (checagem inicial)
        // ────────────────────────────────────────────────────────────────
        if (!candidate.isBefore(windowEndToday)) {
            return scheduleForNextActiveDay(candidateDate, window.startTime, activeDaysOfWeek)
        }

        // ────────────────────────────────────────────────────────────────
        // REGRA 4 — Colisão com Blocos de Inatividade
        // ────────────────────────────────────────────────────────────────
        // Usa blocos pré-buscados. Em chamadas onde candidateDate != deps.date
        // (raro — só em iterações de preview que cruzam meia-noite), o caller
        // já filtra o resultado, então não há impacto observável.
        val blocks = (deps.manualBlocks + deps.calendarBlocks)
            .sortedBy { it.startTime }

        var collisionResolved: Boolean
        var iterations = 0

        do {
            collisionResolved = true
            iterations++

            for (block in blocks) {
                val blockStart = candidateDate.atTime(block.startTime)
                val blockEnd = candidateDate.atTime(block.endTime)

                if (candidate >= blockStart && candidate < blockEnd) {
                    val minutesPastStart = Duration.between(blockStart, candidate).toMinutes()

                    if (minutesPastStart < INACTIVITY_PROXIMITY_MINUTES) {
                        val anticipatedTime = blockStart.minusMinutes(INACTIVITY_BUFFER_MINUTES)
                        if (!anticipatedTime.isBefore(earliestAllowed) &&
                            !anticipatedTime.isBefore(windowStartToday)
                        ) {
                            candidate = anticipatedTime
                        } else {
                            candidate = blockEnd.plusMinutes(INACTIVITY_BUFFER_MINUTES)
                        }
                    } else {
                        candidate = blockEnd.plusMinutes(INACTIVITY_BUFFER_MINUTES)
                    }

                    collisionResolved = false
                    break
                }
            }
        } while (!collisionResolved && iterations < MAX_COLLISION_ITERATIONS)

        // ────────────────────────────────────────────────────────────────
        // REGRA 5 — Fim do Expediente (checagem final)
        // ────────────────────────────────────────────────────────────────
        if (!candidate.isBefore(windowEndToday)) {
            return scheduleForNextActiveDay(candidateDate, window.startTime, activeDaysOfWeek)
        }

        return if (candidate.toLocalDate().isAfter(todayDate)) {
            ScheduleResult.ScheduledTomorrow(candidate)
        } else {
            ScheduleResult.Scheduled(candidate)
        }
    }

    /**
     * Agenda para o início da ActivityWindow do próximo dia ATIVO da semana.
     * Pula dias removidos pelo usuário em Configurações (ex: sábado/domingo).
     */
    private fun scheduleForNextActiveDay(
        currentDate: LocalDate,
        windowStartTime: LocalTime,
        activeDaysOfWeek: Set<DayOfWeek>,
    ): ScheduleResult.ScheduledTomorrow {
        val nextDate = findNextActiveDate(currentDate, activeDaysOfWeek)
        return ScheduleResult.ScheduledTomorrow(nextDate.atTime(windowStartTime))
    }
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
