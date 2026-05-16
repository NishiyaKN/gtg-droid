package com.gtg.app.domain.usecase

import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.ScheduleResult
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.CalendarEventRepository
import com.gtg.app.domain.repository.InactivityBlockRepository
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
    ): ScheduleResult {
        // ────────────────────────────────────────────────────────────────
        // REGRA 2 — Cálculo Base
        // O ponto de partida é sempre relativo ao momento do Check,
        // não ao momento atual. Isso preserva a cadência do usuário.
        // ────────────────────────────────────────────────────────────────
        var candidate = checkTime.plusMinutes(baseIntervalMinutes)

        // ────────────────────────────────────────────────────────────────
        // REGRA 3 — Descanso Mínimo Obrigatório (20 min)
        // Se o usuário atrasou o Check (ex: fez Check 60min depois do alarme),
        // o candidato poderia cair no passado ou muito próximo de "agora".
        // Garantimos pelo menos MINIMUM_REST_MINUTES a partir de NOW.
        // ────────────────────────────────────────────────────────────────
        val earliestAllowed = now.plusMinutes(MINIMUM_REST_MINUTES)
        if (candidate.isBefore(earliestAllowed)) {
            candidate = earliestAllowed
        }

        // ────────────────────────────────────────────────────────────────
        // Obter ActivityWindow ativa
        // Sem janela configurada, não há como agendar.
        // ────────────────────────────────────────────────────────────────
        val window = activityWindowRepository.getActiveWindow()
            ?: return ScheduleResult.NoWindowConfigured

        // ────────────────────────────────────────────────────────────────
        // Verificar se o candidato está ANTES do início da janela do dia.
        // Isso ocorre se o check foi feito de madrugada ou se o intervalo
        // empurrou o candidato para além da meia-noite.
        // ────────────────────────────────────────────────────────────────
        val todayDate = now.toLocalDate()
        val candidateDate = candidate.toLocalDate()
        val windowStartToday = candidateDate.atTime(window.startTime)
        val windowEndToday = candidateDate.atTime(window.endTime)

        if (candidate.isBefore(windowStartToday)) {
            candidate = windowStartToday
        }

        // ────────────────────────────────────────────────────────────────
        // REGRA 5 — Fim do Expediente (checagem inicial)
        // Se já ultrapassou o fim da janela de hoje ANTES mesmo de
        // verificar colisões, vai direto para amanhã.
        // ────────────────────────────────────────────────────────────────
        if (!candidate.isBefore(windowEndToday)) {
            return scheduleForNextDay(candidateDate, window.startTime)
        }

        // ────────────────────────────────────────────────────────────────
        // REGRA 4 — Colisão com Blocos de Inatividade
        //
        // Busca todos os blocos ativos para a data do candidato e
        // itera para resolver colisões. O loop é necessário porque
        // ajustar para evitar um bloco pode empurrar o horário para
        // dentro de outro bloco adjacente.
        //
        // Limite de MAX_COLLISION_ITERATIONS previne loop infinito
        // em configurações patológicas (blocos sobrepostos, etc.).
        // ────────────────────────────────────────────────────────────────
        // Agrega bloqueios manuais (Room) com eventos do Calendar Provider
        // (CalendarEventRepository). Os virtuais já são NONE+specificDate, então
        // entram no mesmo pipeline da Regra 4 sem alteração da lógica.
        val manualBlocks = inactivityBlockRepository.getBlocksActiveOn(candidateDate)
        val calendarBlocks = calendarEventRepository.getBlocksOn(candidateDate)
        val blocks = (manualBlocks + calendarBlocks)
            .sortedBy { it.startTime } // ordenar por início para processamento sequencial

        var collisionResolved: Boolean
        var iterations = 0

        do {
            collisionResolved = true
            iterations++

            for (block in blocks) {
                val blockStart = candidateDate.atTime(block.startTime)
                val blockEnd = candidateDate.atTime(block.endTime)

                // Candidato está dentro deste bloco? [blockStart, blockEnd)
                if (candidate >= blockStart && candidate < blockEnd) {
                    // Quantos minutos se passaram desde o início do bloco
                    val minutesPastStart = Duration.between(blockStart, candidate).toMinutes()

                    if (minutesPastStart < INACTIVITY_PROXIMITY_MINUTES) {
                        // ─── Caso A: Perto do INÍCIO do bloco ───
                        // Antecipa para BUFFER minutos antes do início do bloco.
                        val anticipatedTime = blockStart.minusMinutes(INACTIVITY_BUFFER_MINUTES)

                        // Só antecipa se o horário antecipado ainda respeitar o descanso mínimo
                        // E estiver dentro da janela de atividade.
                        if (!anticipatedTime.isBefore(earliestAllowed) &&
                            !anticipatedTime.isBefore(windowStartToday)
                        ) {
                            candidate = anticipatedTime
                        } else {
                            // Não é possível antecipar (violaria descanso mínimo ou
                            // cairia antes da janela) → adia para depois do bloco.
                            candidate = blockEnd.plusMinutes(INACTIVITY_BUFFER_MINUTES)
                        }
                    } else {
                        // ─── Caso B: Meio ou fim do bloco ───
                        // Adia para BUFFER minutos depois do fim do bloco.
                        candidate = blockEnd.plusMinutes(INACTIVITY_BUFFER_MINUTES)
                    }

                    // Marcamos que houve ajuste → precisamos re-verificar todos os blocos
                    // pois o novo horário pode colidir com outro bloco.
                    collisionResolved = false
                    break // reinicia o for para verificar com o novo candidate
                }
            }
        } while (!collisionResolved && iterations < MAX_COLLISION_ITERATIONS)

        // ────────────────────────────────────────────────────────────────
        // REGRA 5 — Fim do Expediente (checagem final)
        // Após resolver colisões, o candidato pode ter sido empurrado
        // para além do fim da janela. Nesse caso, agenda para amanhã.
        // ────────────────────────────────────────────────────────────────
        if (!candidate.isBefore(windowEndToday)) {
            return scheduleForNextDay(candidateDate, window.startTime)
        }

        // Se o candidato caiu em um dia diferente de "hoje" (ex: intervalo
        // cruzou meia-noite e empurrou para windowStart do dia seguinte),
        // retornar ScheduledTomorrow para que a UI mostre a mensagem correta.
        return if (candidate.toLocalDate().isAfter(todayDate)) {
            ScheduleResult.ScheduledTomorrow(candidate)
        } else {
            ScheduleResult.Scheduled(candidate)
        }
    }

    /**
     * Agenda para o início da ActivityWindow do dia seguinte.
     *
     * @param currentDate Data de referência (hoje).
     * @param windowStartTime Horário de início da janela.
     * @return [ScheduleResult.ScheduledTomorrow] com o datetime do próximo dia.
     */
    private fun scheduleForNextDay(
        currentDate: LocalDate,
        windowStartTime: LocalTime,
    ): ScheduleResult.ScheduledTomorrow {
        val tomorrowStart = currentDate.plusDays(1).atTime(windowStartTime)
        return ScheduleResult.ScheduledTomorrow(tomorrowStart)
    }
}
