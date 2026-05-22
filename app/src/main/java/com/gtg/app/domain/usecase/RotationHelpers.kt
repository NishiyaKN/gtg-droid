package com.gtg.app.domain.usecase

import android.util.Log
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.scheduler.AlarmScheduler
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
 * Cancela alarme primary + overshoot, computa próximo dia ativo via
 * [findNextActiveDate], agenda primary para `nextDate.atTime(window.startTime)`,
 * persiste em [SessionPreferences.setNextAlarm] e zera
 * [SessionPreferences.firstAlarmInChainMillis] — nova cadeia amanhã.
 */
fun rescheduleForNextDay(
    alarmScheduler: AlarmScheduler,
    sessionPrefs: SessionPreferences,
    window: ActivityWindow,
    activeDays: Set<DayOfWeek>,
    pendingExerciseId: Long,
    pendingExerciseName: String,
    pendingTargetReps: Int,
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
    val nextDateTime = nextDate.atTime(window.startTime)
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
