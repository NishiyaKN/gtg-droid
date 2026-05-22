package com.gtg.app.domain.usecase

import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.scheduler.AlarmScheduler
import java.time.DayOfWeek
import java.time.LocalDate
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
 * `false` (permissão `SCHEDULE_EXACT_ALARM` revogada em runtime), retorna
 * sem tocar `SessionPreferences` — `AlarmSchedulerImpl` engole
 * `SecurityException` silenciosamente, então ordering `schedule → setNextAlarm`
 * não nos protege; precondition é a única defesa para evitar prefs apontando
 * para alarme inexistente.
 *
 * Cancela alarme primary + overshoot, computa próximo dia ativo via
 * [findNextActiveDate], agenda primary para `nextDate.atTime(window.startTime)`,
 * persiste em [SessionPreferences.setNextAlarm] e zera
 * [SessionPreferences.firstAlarmInChainMillis] — nova cadeia amanhã.
 *
 * @return `true` se o reschedule foi executado, `false` se permissão revogada
 *   (caller pode logar/notificar). Idempotente em ambos os casos.
 */
fun rescheduleForNextDay(
    alarmScheduler: AlarmScheduler,
    sessionPrefs: SessionPreferences,
    window: ActivityWindow,
    activeDays: Set<DayOfWeek>,
    pendingExerciseId: Long,
    pendingExerciseName: String,
    pendingTargetReps: Int,
): Boolean {
    if (!alarmScheduler.canScheduleExactAlarms()) return false

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
    return true
}
