package com.gtg.app.domain.usecase

import android.util.Log
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.scheduler.AlarmScheduler
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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

/**
 * `true` quando [now] está dentro da [ActivityWindow] em um dia ativo.
 * `window == null` (não configurada) é tratado como "sempre ativo".
 *
 * Compartilhado entre `HomeViewModel.restartCountdown` (gate de `canCheck`
 * no chain mode) e `AlarmReceiver.handleDispatch` (gate dos alertas) para
 * que a noção de "dentro da janela" não derive.
 */
fun isInsideActiveWindow(
    now: LocalDateTime,
    window: ActivityWindow?,
    activeDays: Set<DayOfWeek>,
): Boolean {
    if (now.dayOfWeek !in activeDays) return false
    if (window == null) return true
    val time = now.toLocalTime()
    return !time.isBefore(window.startTime) && !time.isAfter(window.endTime)
}

private const val ROTATION_HELPERS_TAG = "RotationHelpers"

/**
 * Budget para a resolução de blocos em caminhos de rearme
 * ([rescheduleForNextDay] e o rollover de snooze do AlarmViewModel).
 *
 * O caller mais sensível é o [com.gtg.app.presentation.alarm.AlarmReceiver],
 * cujo goAsync inteiro vive sob `withTimeout(9s)` — se o lookahead de blocos
 * (Room + CalendarProvider, até 7 dias) estourasse o budget compartilhado, o
 * alarme disparado seria consumido sem NADA ser rearmado (perda silenciosa).
 * Sub-budget próprio + fallback bare garantem que esses caminhos sempre
 * armam alguma coisa. A composição com os demais budgets do receiver é
 * pinada por teste em RotationHelpersTest.
 */
internal const val FIRST_ALARM_RESOLUTION_BUDGET_MILLIS = 3_000L

/**
 * Empurra a cadeia ativa para o início da janela do próximo dia ativo,
 * preservando o exercício pending atual.
 *
 * Invocado em dois caminhos:
 * - [com.gtg.app.presentation.home.HomeViewModel] no countdown loop quando o
 *   timer cruza o fim da [ActivityWindow] do dia.
 * - [com.gtg.app.presentation.alarm.AlarmReceiver] no goAsync ao detectar
 *   `now > endTime` no momento do disparo (defesa contra overshoots que
 *   escaparam da validação no agendamento).
 *
 * **Precondition: [AlarmScheduler.canScheduleExactAlarms]`() == true`**. Se
 * `false` (permissão `SCHEDULE_EXACT_ALARM` revogada em runtime), loga warn
 * e retorna sem tocar `SessionPreferences` — `AlarmSchedulerImpl` engole
 * `SecurityException` silenciosamente, então ordering `schedule →
 * setNextAlarm` não nos protege; precondition é a única defesa para evitar
 * prefs apontando para alarme inexistente. Idempotente nesse caso.
 *
 * O horário alvo passa por [DynamicSchedulerUseCase.resolveFirstAlarmStartingAt]
 * (fix 2026-06-11): em DYNAMIC, um bloco (manual ou Calendar) cobrindo o início
 * da janela do dia alvo adia o alarme para o fim do cluster + buffer, em vez de
 * tocar dentro do bloco; STRICT continua bare por design. Falha ou estouro de
 * [FIRST_ALARM_RESOLUTION_BUDGET_MILLIS] degrada para o início bare (comportamento
 * pré-fix) — este caminho NUNCA termina sem alarme armado.
 *
 * Side-effects (contrato): cancela primary + overshoot, agenda primary,
 * persiste via [SessionPreferences.setNextAlarm] (ordem schedule → persist) e
 * zera [SessionPreferences.firstAlarmInChainMillis] — nova cadeia amanhã.
 * NUNCA escreve `lastCheckMillis` (âncora de cadência — só Checks reais).
 */
suspend fun rescheduleForNextDay(
    alarmScheduler: AlarmScheduler,
    sessionPrefs: SessionPreferences,
    window: ActivityWindow,
    activeDays: Set<DayOfWeek>,
    pendingExerciseId: Long,
    pendingExerciseName: String,
    pendingTargetReps: Int,
    dynamicScheduler: DynamicSchedulerUseCase,
) {
    if (!alarmScheduler.canScheduleExactAlarms()) {
        Log.w(
            ROTATION_HELPERS_TAG,
            "rescheduleForNextDay aborted — SCHEDULE_EXACT_ALARM revoked; encerrando cadeia",
        )
        // Permissão revogada em runtime: não conseguimos reagendar, mas precisamos
        // ao menos encerrar limpamente a cadeia atual para que a UI não fique
        // presa em chain mode mostrando counter crescente sem alarme armado.
        // Cancela overshoots residuais (já não vão tocar de qualquer forma sem
        // SCHEDULE_EXACT_ALARM) e zera o anchor. nextAlarmMillis fica stale —
        // o usuário verá Check disabled (sem cadeia, sem janela) e precisa ir
        // em Settings re-conceder permissão antes do próximo Start.
        alarmScheduler.cancelOvershoot()
        sessionPrefs.setFirstAlarmInChain(0L)
        return
    }

    val nextDate = findNextActiveDate(LocalDate.now(), activeDays)
    val bareStart = nextDate.atTime(window.startTime)

    // Resolução contra blocos do dia alvo. Fail-open por camada:
    // - falha de fetch → o próprio resolver degrada para início bare (R5);
    // - estouro do budget → withTimeoutOrNull retorna null e caímos para
    //   bare aqui (o budget é política DESTE caller, por causa do goAsync).
    val nextDateTime = withTimeoutOrNull(FIRST_ALARM_RESOLUTION_BUDGET_MILLIS) {
        dynamicScheduler.resolveFirstAlarmStartingAt(
            startDate = nextDate,
            activeDaysOfWeek = activeDays,
            intervalMode = sessionPrefs.intervalMode,
            prefetchedWindow = window,
        )
    } ?: bareStart.also {
        Log.w(
            ROTATION_HELPERS_TAG,
            "rescheduleForNextDay: resolução de blocos estourou " +
                "${FIRST_ALARM_RESOLUTION_BUDGET_MILLIS}ms — usando início bare",
        )
    }

    val nextMillis = nextDateTime
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    alarmScheduler.cancel()
    alarmScheduler.cancelOvershoot()
    alarmScheduler.schedule(
        triggerAt = nextDateTime,
        exerciseId = pendingExerciseId,
        exerciseName = pendingExerciseName,
        targetReps = pendingTargetReps,
    )

    sessionPrefs.setNextAlarm(
        epochMillis = nextMillis,
        exerciseId = pendingExerciseId,
        exerciseName = pendingExerciseName,
        targetReps = pendingTargetReps,
    )
    sessionPrefs.setFirstAlarmInChain(0L)
}

/**
 * Supressão de um disparo PRIMARY dentro de bloco (guard fire-time do
 * AlarmReceiver, fix 2026-06-11): rearma para [rearmAt] e atualiza apenas o
 * estado de agendamento. Gêmeo same-day de [rescheduleForNextDay] — vive
 * aqui (e não no receiver) para que a matriz de side-effects seja testável
 * com o harness existente.
 *
 * Matriz de side-effects:
 * - `setNextAlarm` SIM — estado de agendamento, qualquer writer pode.
 * - `setLastCheck` NUNCA — âncora de cadência, exclusiva de Checks reais.
 * - `setFirstAlarmInChain` NÃO — postponement same-day preserva o T0 da
 *   cadeia em andamento (diferente do rollover cross-day, que zera).
 * - `recordAlarmDispatchedNow` NÃO é chamado — nenhum toque aconteceu
 *   (responsabilidade do caller, que simplesmente não toca).
 * - `canScheduleExactAlarms` é re-validado AQUI além da decisão: a janela
 *   entre decisão e aplicação (até 2s de guard) é estreita mas real — sem o
 *   guard de aplicação, uma revogação nesse intervalo faria `schedule()`
 *   engolir SecurityException e `setNextAlarm` persistir um ponteiro para
 *   alarme fantasma.
 * - Ordem `schedule → setNextAlarm` preservada (AlarmSchedulerImpl engole
 *   SecurityException).
 */
fun suppressPrimaryInsideBlock(
    alarmScheduler: AlarmScheduler,
    sessionPrefs: SessionPreferences,
    rearmAt: LocalDateTime,
    pendingExerciseId: Long,
    pendingExerciseName: String,
    pendingTargetReps: Int,
) {
    if (!alarmScheduler.canScheduleExactAlarms()) {
        Log.w(
            ROTATION_HELPERS_TAG,
            "suppressPrimaryInsideBlock aborted — SCHEDULE_EXACT_ALARM revogado entre decisão e aplicação",
        )
        // Sem rearme possível: cancela overshoot residual e NÃO persiste
        // nada — prefs continuam apontando para o disparo já consumido (no
        // passado), estado que o usuário recupera ao abrir o app.
        alarmScheduler.cancelOvershoot()
        return
    }

    val rearmMillis = rearmAt
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    alarmScheduler.cancel()
    alarmScheduler.cancelOvershoot()
    alarmScheduler.schedule(
        triggerAt = rearmAt,
        exerciseId = pendingExerciseId,
        exerciseName = pendingExerciseName,
        targetReps = pendingTargetReps,
    )

    sessionPrefs.setNextAlarm(
        epochMillis = rearmMillis,
        exerciseId = pendingExerciseId,
        exerciseName = pendingExerciseName,
        targetReps = pendingTargetReps,
    )

    Log.i(
        ROTATION_HELPERS_TAG,
        "disparo suprimido por bloco — alarme rearmado para $rearmAt " +
            "(evento/bloco cobrindo o momento do toque)",
    )
}
