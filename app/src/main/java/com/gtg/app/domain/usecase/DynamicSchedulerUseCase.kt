package com.gtg.app.domain.usecase

import android.util.Log
import com.gtg.app.data.local.IntervalMode
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.ScheduleResult
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.CalendarEventRepository
import com.gtg.app.domain.repository.InactivityBlockRepository
import kotlinx.coroutines.CancellationException
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

        /**
         * Limite de dias examinados pelo lookahead de [resolveFirstAlarmStartingAt].
         * Espelha o caminhar máximo de [findNextActiveDate]. Na exaustão o
         * wrapper degrada para início bare de janela — nunca "sem alarme".
         */
        private const val MAX_FIRST_ALARM_LOOKAHEAD_DAYS = 7

        /**
         * Offset fixo do piso de rearme fire-time: o horário rearmado é
         * sempre ≥ `now + este offset` (nunca um best-effort dinâmico).
         * Com a mescla de clusters o rearme natural (fim + buffer) já é
         * sempre > now + buffer; o piso documenta a garantia contra drift
         * futuro (ex.: buffer reduzido a zero).
         */
        private const val FIRE_TIME_REARM_FLOOR_MINUTES = 1L

        private const val TAG = "DynamicScheduler"
    }

    /**
     * Bundle de dependências pré-buscadas. Permite que callers que iteram
     * sobre o scheduler para a mesma data (ex: [PreviewTodayRoutineUseCase])
     * evitem refazer fetches Room idênticos.
     *
     * **Sem campo `date`** — embora o caller pré-busque para uma data
     * específica, [evaluateWithDependencies] usa os blocos com base no
     * `candidateDate` que ele mesmo computa. Quando essas datas divergem
     * (preview cruza meia-noite), o caller filtra o resultado externamente
     * (ex: `nextResult.dateTime.toLocalDate() != referenceDate`). Expor um
     * campo `date` aqui sugeriria que a engine respeita a data, o que não é
     * verdade — era um trap para callers futuros.
     */
    data class PrefetchedDependencies(
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
     * @param resolveRolloverAgainstBlocks Quando `true` (default), resultados
     *   [ScheduleResult.ScheduledTomorrow] em início bare de janela passam pelo
     *   [resolveFirstAlarmStartingAt] para respeitar os blocos do dia alvo
     *   (fix 2026-06-11 — Regra 4 não roda nos early-returns de rollover).
     *   Callers que DESCARTAM ScheduledTomorrow (preview de sessão parada na
     *   Home) passam `false` para não pagar o lookahead à toa. Default `true`:
     *   writers novos ganham a proteção sem opt-in — lição do bypass de
     *   activeDaysOfWeek (docs/solutions/logic-errors).
     * @return [ScheduleResult] com o horário do próximo alarme ou indicação de estado.
     */
    suspend fun calculateNextAlarm(
        checkTime: LocalDateTime,
        baseIntervalMinutes: Long,
        now: LocalDateTime = LocalDateTime.now(),
        activeDaysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
        intervalMode: IntervalMode = IntervalMode.DYNAMIC,
        resolveRolloverAgainstBlocks: Boolean = true,
    ): ScheduleResult {
        // Pre-computa a data alvo para pré-buscar blocos da data correta.
        // Replica o mesmo cálculo do início de [evaluateWithDependencies] mas
        // sem precisar da window — usado só para escolher a data do fetch.
        // Em STRICT, não há clamp de rest mínimo na pré-computação também.
        val initialCandidate = checkTime
            .plusMinutes(baseIntervalMinutes)
            .let { c ->
                if (intervalMode == IntervalMode.STRICT) {
                    c
                } else {
                    val earliest = now.plusMinutes(MINIMUM_REST_MINUTES)
                    if (c.isBefore(earliest)) earliest else c
                }
            }
        val targetDate = initialCandidate.toLocalDate()

        val deps = preFetchForDate(targetDate)
            ?: return ScheduleResult.NoWindowConfigured
        val result = evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = baseIntervalMinutes,
            now = now,
            activeDaysOfWeek = activeDaysOfWeek,
            intervalMode = intervalMode,
            deps = deps,
        )

        if (!resolveRolloverAgainstBlocks) return result
        if (result !is ScheduleResult.ScheduledTomorrow) return result
        // STRICT pode tocar dentro de bloco por design (AE7) — bare é correto.
        if (intervalMode == IntervalMode.STRICT) return result
        // Gate: só re-resolve resultados em início BARE de janela — a assinatura
        // dos três early-returns de scheduleForNextActiveDay (que nunca viram a
        // Regra 4 do dia alvo). O fall-through cross-midnight (4º produtor de
        // ScheduledTomorrow) carrega horário mid-window JÁ resolvido pela Regra 4
        // com os blocos da data correta e NÃO pode ser reescrito — re-resolver do
        // início da janela o anteciparia, podendo violar o descanso mínimo.
        // Quando o fall-through coincide exatamente com o início da janela, a
        // Regra 4 já o liberou contra os blocos do dia → re-resolução idempotente.
        if (result.dateTime.toLocalTime() != deps.window.startTime) return result

        val resolved = resolveFirstAlarmStartingAt(
            startDate = result.dateTime.toLocalDate(),
            activeDaysOfWeek = activeDaysOfWeek,
            intervalMode = intervalMode,
            prefetchedWindow = deps.window,
        ) ?: return result
        return ScheduleResult.ScheduledTomorrow(resolved)
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
        intervalMode: IntervalMode = IntervalMode.DYNAMIC,
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
        //
        // STRICT: pula. `next = lastCheck + N` exato — se cair no passado,
        // AlarmManager dispara imediatamente. Trade-off escolhido pelo usuário.
        // ────────────────────────────────────────────────────────────────
        val earliestAllowed = now.plusMinutes(MINIMUM_REST_MINUTES)
        if (intervalMode == IntervalMode.DYNAMIC && candidate.isBefore(earliestAllowed)) {
            candidate = earliestAllowed
        }

        val window = deps.window
        val todayDate = now.toLocalDate()
        val candidateDate = candidate.toLocalDate()
        val windowStartToday = candidateDate.atTime(window.startTime)
        val windowEndToday = candidateDate.atTime(window.endTime)

        // Verificar se o candidato está ANTES do início da janela do dia.
        // Isso ocorre se o check foi feito de madrugada ou se o intervalo
        // empurrou o candidato para além da meia-noite.
        if (candidate.isBefore(windowStartToday)) {
            candidate = windowStartToday
        }

        // ────────────────────────────────────────────────────────────────
        // Dia da semana inativo — usuário desabilitou este weekday
        // em Configurações (ex: sábado/domingo desligados). Rola para o
        // próximo dia ativo. Avalia ANTES das regras 5 e 4 para não gastar
        // ciclos em um dia que será descartado de qualquer forma.
        // ────────────────────────────────────────────────────────────────
        if (candidateDate.dayOfWeek !in activeDaysOfWeek) {
            return scheduleForNextActiveDay(candidateDate, window.startTime, activeDaysOfWeek)
        }

        // ────────────────────────────────────────────────────────────────
        // REGRA 5 — Fim do Expediente (checagem inicial)
        // Se já ultrapassou o fim da janela de hoje ANTES mesmo de
        // verificar colisões, vai direto para amanhã.
        // ────────────────────────────────────────────────────────────────
        if (!candidate.isBefore(windowEndToday)) {
            return scheduleForNextActiveDay(candidateDate, window.startTime, activeDaysOfWeek)
        }

        // ────────────────────────────────────────────────────────────────
        // REGRA 4 — Colisão com Blocos de Inatividade
        //
        // Itera sobre todos os blocos pré-buscados (manuais + Calendar
        // Provider) para resolver colisões. O loop é necessário porque
        // ajustar para evitar um bloco pode empurrar o horário para dentro
        // de outro bloco adjacente.
        //
        // Limite de MAX_COLLISION_ITERATIONS previne loop infinito em
        // configurações patológicas (blocos sobrepostos, etc).
        //
        // Blocos virtuais do Calendar já são NONE+specificDate, então
        // entram no mesmo pipeline da Regra 4 sem alteração da lógica.
        //
        // STRICT: pula a regra 4 inteira. Em STRICT o alarme pode tocar
        // dentro de um bloco — é o trade-off de cadência exata.
        // ────────────────────────────────────────────────────────────────
        if (intervalMode == IntervalMode.DYNAMIC) {
            val blocks = (deps.manualBlocks + deps.calendarBlocks)
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

                            // Só antecipa se o horário antecipado ainda respeitar o
                            // descanso mínimo E estiver dentro da janela de atividade.
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

                        // Marcamos que houve ajuste → precisamos re-verificar todos os
                        // blocos pois o novo horário pode colidir com outro bloco.
                        collisionResolved = false
                        break // reinicia o for para verificar com o novo candidate
                    }
                }
            } while (!collisionResolved && iterations < MAX_COLLISION_ITERATIONS)

            // ────────────────────────────────────────────────────────────
            // REGRA 5 — Fim do Expediente (checagem final pós-colisão)
            // Após resolver colisões, o candidato pode ter sido empurrado
            // para além do fim da janela. Nesse caso, agenda para amanhã.
            //
            // STRICT: pula. Sem rule 4 o candidato não se move, então a
            // checagem inicial de windowEnd (acima) já cobriu.
            // ────────────────────────────────────────────────────────────
            if (!candidate.isBefore(windowEndToday)) {
                return scheduleForNextActiveDay(candidateDate, window.startTime, activeDaysOfWeek)
            }
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
     * Agenda para o início da ActivityWindow do próximo dia ATIVO da semana.
     * Pula dias removidos pelo usuário em Configurações (ex: sábado/domingo).
     *
     * **Acoplamento:** o gate de re-resolução em [calculateNextAlarm]
     * identifica os resultados deste método por `dateTime.toLocalTime() ==
     * window.startTime`. Se este horário deixar de ser exatamente o início
     * bare da janela, atualize o gate junto — senão a proteção contra blocos
     * evapora em silêncio.
     */
    private fun scheduleForNextActiveDay(
        currentDate: LocalDate,
        windowStartTime: LocalTime,
        activeDaysOfWeek: Set<DayOfWeek>,
    ): ScheduleResult.ScheduledTomorrow {
        val nextDate = findNextActiveDate(currentDate, activeDaysOfWeek)
        return ScheduleResult.ScheduledTomorrow(nextDate.atTime(windowStartTime))
    }

    // ─────────────────────────────────────────────────────────────────
    // Resolver de primeiro alarme do dia (fix 2026-06-11)
    //
    // Bug original: todos os caminhos de rollover armavam o alarme em
    // window.startTime bare, sem consultar os blocos (manuais + Calendar)
    // do dia alvo — janela 09:30 com evento 09:10–09:40 tocava às 09:30.
    // A Regra 4 só protegia candidatos same-day dentro de
    // [evaluateWithDependencies]; estes helpers estendem a garantia a
    // qualquer "primeiro alarme do dia X" e a re-validações fire-time.
    // ─────────────────────────────────────────────────────────────────

    /**
     * Resultado de [resolveFirstAlarmForDay].
     */
    sealed interface FirstAlarmResolution {
        /** Horário válido encontrado dentro da janela do dia. */
        data class Resolved(val dateTime: LocalDateTime) : FirstAlarmResolution

        /**
         * O adiamento empurrou o candidato para além do fim da janela —
         * o caller deve rolar para o próximo dia ativo e tentar de novo.
         */
        data object OverflowsWindowEnd : FirstAlarmResolution
    }

    /** Intervalo half-open [start, end) de blocos mesclados de um dia. */
    private data class BlockCluster(val start: LocalTime, val end: LocalTime)

    /**
     * Resolve um candidato a "primeiro alarme" contra os blocos de [date].
     * Primitivo DYNAMIC-only — STRICT (que toca dentro de bloco por design,
     * contrato AE7) é tratado nos pontos de entrada reais:
     * [resolveFirstAlarmStartingAt], [decideFireTimeDispatch] e
     * [calculateNextAlarm].
     *
     * Diferenças deliberadas vs. a Regra 4 da engine:
     * - **Postpone-only.** Nunca antecipa: no início de janela a antecipação
     *   cairia antes da janela por construção, e em chamadas fire-time ela
     *   poderia cair no passado e gerar loop de disparo imediato.
     * - **Sem descanso mínimo.** Rollover e fire-time não têm "momento do
     *   Check" como referência; o conceito da Regra 3 não se aplica.
     * - **Mescla de clusters.** Blocos com gap ≤ [INACTIVITY_BUFFER_MINUTES]
     *   são unidos antes da colisão. Consequência: após UM adiamento para
     *   `fimDoCluster + buffer`, o candidato não pode cair dentro de outro
     *   bloco (qualquer bloco tão próximo teria sido mesclado) — resolução
     *   em passada única, sem o ping-pong possível na Regra 4.
     *
     * @param candidate ponto de partida; default = início da janela de [date].
     */
    fun resolveFirstAlarmForDay(
        date: LocalDate,
        window: ActivityWindow,
        blocks: List<InactivityBlock>,
        candidate: LocalDateTime = date.atTime(window.startTime),
    ): FirstAlarmResolution {
        val clusters = mergeBlocksIntoClusters(blocks)

        val containing = clusters.firstOrNull { cluster ->
            candidate >= date.atTime(cluster.start) && candidate < date.atTime(cluster.end)
        }
        val resolved = if (containing != null) {
            date.atTime(containing.end).plusMinutes(INACTIVITY_BUFFER_MINUTES)
        } else {
            candidate
        }

        return if (!resolved.isBefore(date.atTime(window.endTime))) {
            FirstAlarmResolution.OverflowsWindowEnd
        } else {
            FirstAlarmResolution.Resolved(resolved)
        }
    }

    /**
     * Wrapper suspend: resolve o primeiro alarme válido a partir de
     * [startDate], buscando os blocos dos dias candidatos e rolando para
     * o próximo dia ativo quando o adiamento estoura o fim da janela.
     *
     * Garantias:
     * - [startDate] em dia inativo é normalizado para o próximo dia ativo.
     * - Lookahead limitado a [MAX_FIRST_ALARM_LOOKAHEAD_DAYS]; na exaustão
     *   (ex.: bloco DAILY 00:00–23:59 permanente) degrada para o início
     *   bare do último dia examinado + warn — nunca retorna "sem alarme";
     *   o guard fire-time é a rede para esse estado.
     * - **Fail-open interno (R5):** falha na busca de blocos degrada para o
     *   início bare do primeiro dia candidato + warn, nunca propaga — a
     *   política "falha de resolução nunca vira alarme nenhum" vive AQUI,
     *   não em cada caller (CancellationException é re-lançada).
     * - STRICT não consulta blocos (Regra 4 desligada por design).
     *
     * I/O: as datas candidatas dependem só de [activeDaysOfWeek] e são
     * precomputáveis, então o Calendar Provider é consultado UMA vez via
     * range (1 binder round-trip cross-process em vez de até 7 sequenciais —
     * relevante dentro dos sub-budgets do receiver); os blocos manuais
     * (Room, local e barato) são buscados por dia em paralelo.
     *
     * @param prefetchedWindow evita refetch quando o caller já tem a window
     *   (ex.: rescheduleForNextDay) — espelha o padrão de pré-busca do cache.
     * @return horário resolvido, ou `null` se não há janela configurada.
     */
    suspend fun resolveFirstAlarmStartingAt(
        startDate: LocalDate,
        activeDaysOfWeek: Set<DayOfWeek>,
        intervalMode: IntervalMode = IntervalMode.DYNAMIC,
        prefetchedWindow: ActivityWindow? = null,
    ): LocalDateTime? {
        val window = prefetchedWindow
            ?: activityWindowRepository.getActiveWindow()
            ?: return null

        val firstDate = if (startDate.dayOfWeek in activeDaysOfWeek) {
            startDate
        } else {
            findNextActiveDate(startDate, activeDaysOfWeek)
        }

        if (intervalMode == IntervalMode.STRICT) {
            return firstDate.atTime(window.startTime)
        }

        // Datas candidatas precomputadas — overflow só decide SE continua,
        // nunca PARA ONDE vai.
        val candidateDates = buildList {
            var date = firstDate
            repeat(MAX_FIRST_ALARM_LOOKAHEAD_DAYS) {
                add(date)
                date = findNextActiveDate(date, activeDaysOfWeek)
            }
        }

        val blocksByDate = try {
            coroutineScope {
                val calendarDef = async {
                    calendarEventRepository.getBlocksInRange(
                        startDate = candidateDates.first(),
                        endDateInclusive = candidateDates.last(),
                    )
                }
                val manualDefs = candidateDates.map { date ->
                    date to async { inactivityBlockRepository.getBlocksActiveOn(date) }
                }
                val calendarByDate = calendarDef.await()
                manualDefs.associate { (date, deferred) ->
                    date to (deferred.await() + calendarByDate[date].orEmpty())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                TAG,
                "resolveFirstAlarmStartingAt: busca de blocos falhou — " +
                    "degradando para início bare de $firstDate",
                e,
            )
            return firstDate.atTime(window.startTime)
        }

        for (date in candidateDates) {
            when (val resolution = resolveFirstAlarmForDay(date, window, blocksByDate.getValue(date))) {
                is FirstAlarmResolution.Resolved -> return resolution.dateTime
                FirstAlarmResolution.OverflowsWindowEnd -> Unit // tenta o próximo dia
            }
        }

        Log.w(
            TAG,
            "resolveFirstAlarmStartingAt: lookahead de $MAX_FIRST_ALARM_LOOKAHEAD_DAYS dias " +
                "exaurido a partir de $startDate — degradando para início bare de ${candidateDates.last()}",
        )
        return candidateDates.last().atTime(window.startTime)
    }

    /**
     * Decisão do guard fire-time do AlarmReceiver (fix 2026-06-11).
     */
    sealed interface FireTimeDecision {
        /** Disparo válido — seguir o fluxo normal de notificação. */
        data object Ring : FireTimeDecision

        /**
         * `now` caiu dentro de um bloco: suprimir o toque e rearmar para
         * [rearmAt] (fim do cluster + buffer, piso estritamente após now;
         * garantido same-day e dentro da janela por construção).
         */
        data class SuppressAndReschedule(val rearmAt: LocalDateTime) : FireTimeDecision

        /**
         * `now` está em bloco cujo rearme cairia fora da janela do dia (ou
         * cruzaria meia-noite via clamp 23:59) — nunca armar esse horário
         * verbatim. Primary: rolar para o próximo dia via
         * [rescheduleForNextDay]; overshoot: deixar a cadeia estancar, como
         * já acontece no fim da janela.
         */
        data object SuppressAndRollToNextDay : FireTimeDecision
    }

    /**
     * Decide se um disparo às [now] deve tocar ou ser suprimido por um bloco
     * (manual ou Calendar) que cobre o momento — tipicamente porque o evento
     * foi criado/movido DEPOIS do alarme ter sido armado (defesa em
     * profundidade; o conserto principal é no schedule-site).
     *
     * Regras:
     * - STRICT → [FireTimeDecision.Ring] sempre (contrato AE7).
     * - `canScheduleExactAlarms == false` → Ring: a incapacidade de rearmar
     *   converte a decisão ANTES da supressão — suprimir sem rearme seria um
     *   alarme perdido em silêncio (pior que tocar durante a reunião).
     * - Fora de qualquer cluster → Ring.
     * - Dentro de cluster → suprimir; rearme em fim do cluster + buffer,
     *   piso fixo `now + FIRE_TIME_REARM_FLOOR_MINUTES` (postpone-only — o
     *   braço de antecipação da Regra 4 rearmaria no passado e geraria loop
     *   de disparo imediato). Se o rearme estourar a janela →
     *   [FireTimeDecision.SuppressAndRollToNextDay].
     *
     * Pura — recebe os blocos do dia de [now] por parâmetro; o overload
     * suspend abaixo faz o fetch para o receiver.
     */
    fun decideFireTimeDispatch(
        now: LocalDateTime,
        window: ActivityWindow,
        blocks: List<InactivityBlock>,
        intervalMode: IntervalMode,
        canScheduleExactAlarms: Boolean,
    ): FireTimeDecision {
        if (intervalMode == IntervalMode.STRICT) return FireTimeDecision.Ring
        if (!canScheduleExactAlarms) return FireTimeDecision.Ring

        val probe = resolveFirstAlarmForDay(
            date = now.toLocalDate(),
            window = window,
            blocks = blocks,
            candidate = now,
        )

        return when (probe) {
            is FirstAlarmResolution.Resolved ->
                if (probe.dateTime == now) {
                    // Fora de qualquer cluster — o candidato não se moveu.
                    FireTimeDecision.Ring
                } else {
                    // Dentro de cluster. O rearme natural (fim + buffer) já é
                    // > now por construção; o maxOf materializa o contrato do
                    // piso fixo contra drift futuro.
                    FireTimeDecision.SuppressAndReschedule(
                        maxOf(probe.dateTime, now.plusMinutes(FIRE_TIME_REARM_FLOOR_MINUTES)),
                    )
                }
            FirstAlarmResolution.OverflowsWindowEnd -> FireTimeDecision.SuppressAndRollToNextDay
        }
    }

    /**
     * Overload suspend de [decideFireTimeDispatch]: busca os blocos do dia
     * de [now] (manual + Calendar, em paralelo) e delega à decisão pura.
     * Caller: AlarmReceiver, dentro do sub-budget do guard.
     *
     * Os early-returns de STRICT/exact-alarm vêm ANTES do fetch: a decisão
     * deles não depende de blocos, e pagar uma query cross-process ao
     * provider (potencialmente lenta pós-doze) para descartá-la atrasaria
     * todo dispatch de um usuário STRICT à toa. Falha do fetch → Ring
     * (fail-open, mesma política do guard).
     */
    suspend fun decideFireTimeDispatch(
        now: LocalDateTime,
        window: ActivityWindow,
        intervalMode: IntervalMode,
        canScheduleExactAlarms: Boolean,
    ): FireTimeDecision {
        if (intervalMode == IntervalMode.STRICT) return FireTimeDecision.Ring
        if (!canScheduleExactAlarms) return FireTimeDecision.Ring

        val date = now.toLocalDate()
        val blocks = try {
            coroutineScope {
                val manualDef = async { inactivityBlockRepository.getBlocksActiveOn(date) }
                val calendarDef = async { calendarEventRepository.getBlocksOn(date) }
                manualDef.await() + calendarDef.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "decideFireTimeDispatch: busca de blocos falhou — fail-open (Ring)", e)
            return FireTimeDecision.Ring
        }
        return decideFireTimeDispatch(
            now = now,
            window = window,
            blocks = blocks,
            intervalMode = intervalMode,
            canScheduleExactAlarms = canScheduleExactAlarms,
        )
    }

    /**
     * Mescla blocos de um mesmo dia em clusters, unindo blocos cujo gap é
     * ≤ [INACTIVITY_BUFFER_MINUTES] (além de sobrepostos/contidos).
     *
     * Comparação em segundos-do-dia para evitar wrap de [LocalTime] perto
     * de meia-noite (23:59 + 5min viraria 00:04 e quebraria a ordenação).
     */
    private fun mergeBlocksIntoClusters(blocks: List<InactivityBlock>): List<BlockCluster> {
        if (blocks.isEmpty()) return emptyList()

        val bufferSeconds = INACTIVITY_BUFFER_MINUTES * 60
        val sorted = blocks.sortedBy { it.startTime }
        val clusters = mutableListOf<BlockCluster>()

        var start = sorted.first().startTime
        var end = sorted.first().endTime
        for (block in sorted.drop(1)) {
            if (block.startTime.toSecondOfDay() <= end.toSecondOfDay() + bufferSeconds) {
                end = maxOf(end, block.endTime)
            } else {
                clusters += BlockCluster(start, end)
                start = block.startTime
                end = block.endTime
            }
        }
        clusters += BlockCluster(start, end)
        return clusters
    }
}
