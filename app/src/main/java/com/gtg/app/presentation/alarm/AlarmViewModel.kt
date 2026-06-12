package com.gtg.app.presentation.alarm

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.model.ExerciseLog
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.ScheduleResult
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.ExerciseLogRepository
import com.gtg.app.domain.repository.ExerciseRepository
import com.gtg.app.domain.scheduler.AlarmScheduler
import com.gtg.app.domain.usecase.DynamicSchedulerUseCase
import com.gtg.app.domain.usecase.findNextActiveDate
import com.gtg.app.domain.usecase.pickNextExerciseInRotation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * ViewModel da [AlarmActivity].
 *
 * Gerencia o fluxo de Check/Skip quando o alarme dispara em full-screen:
 *
 * **Check:**
 * 1. Insere [ExerciseLog] no Room com timestamp atual e reps alvo.
 * 2. Chama [DynamicSchedulerUseCase.calculateNextAlarm] para obter o próximo horário.
 * 3. Agenda o novo alarme via [AlarmScheduler].
 * 4. Atualiza [SessionPreferences] com o novo estado.
 *
 * **Skip:**
 * Mesma lógica de reagendamento, mas SEM registrar log de exercício.
 */
@HiltViewModel
class AlarmViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val exerciseRepository: ExerciseRepository,
    private val exerciseLogRepository: ExerciseLogRepository,
    private val activityWindowRepository: ActivityWindowRepository,
    private val dynamicScheduler: DynamicSchedulerUseCase,
    private val alarmScheduler: AlarmScheduler,
    private val sessionPrefs: SessionPreferences,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // Dados vindos do Intent (propagados automaticamente pelo SavedStateHandle)
    val exerciseId: Long = savedStateHandle[AlarmReceiver.EXTRA_EXERCISE_ID] ?: -1L
    val exerciseName: String = savedStateHandle[AlarmReceiver.EXTRA_EXERCISE_NAME] ?: "Exercício"
    val targetReps: Int = savedStateHandle[AlarmReceiver.EXTRA_TARGET_REPS] ?: 0

    /**
     * Intervalo de snooze exibido no botão da [AlarmActivity].
     * Snapshot lido uma vez por instância — a Activity não sobrevive a
     * mudanças de Settings durante o disparo, então streaming reativo seria
     * over-engineering.
     */
    val snoozeMinutes: Int = sessionPrefs.overshootRepeatMinutes

    /**
     * Snapshot one-shot da modalidade Visual no momento do disparo. Mesma
     * justificativa do [snoozeMinutes] — a Activity não sobrevive a mudanças
     * mid-alarm em Settings.
     */
    val visualEnabled: Boolean = sessionPrefs.visualEnabled

    private val _actionCompleted = MutableStateFlow(false)
    val actionCompleted: StateFlow<Boolean> = _actionCompleted.asStateFlow()

    /**
     * Usuário confirmou a série. Registra log + reagenda.
     */
    fun performCheck() {
        viewModelScope.launch {
            // Cancela overshoot + para som + fecha notificação ANTES de reagendar,
            // para não deixar overshoot disparando com extras antigas após o Check.
            dismissActiveAlarmSideEffects()

            val now = LocalDateTime.now()
            val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            // 1. Registrar ExerciseLog — guard contra exerciseId inválido
            // (intent corrompido / extras faltando). Sem isso, inseriríamos
            // log com FK -1L apontando para "nenhum exercício".
            if (exerciseId > 0L) {
                exerciseLogRepository.insert(
                    ExerciseLog(
                        exerciseId = exerciseId,
                        timestamp = now,
                        repsCompleted = targetReps,
                    ),
                )
            }

            // 2. Atualizar âncora de cadência (U16a do lote 2026-05-20).
            // Check via full-screen É Check real do usuário — move o anchor
            // junto com HomeViewModel.startSession/performManualCheck. Sem
            // isso, modo STRICT em rescheduleFromAnchor mid-session usaria
            // o lastCheck antigo após Check pela AlarmActivity.
            sessionPrefs.setLastCheck(nowMillis)

            // 2b. Encerra a cadeia de alerta — Check é o evento que zera
            // firstAlarmInChainMillis (cadeia mental do usuário terminou com
            // sucesso). Próximo dispatch via AlarmReceiver escreverá fresh
            // timestamp via recordAlarmDispatchedNow.
            sessionPrefs.setFirstAlarmInChain(0L)

            // 3. Reagendar (rotação avança mesmo se log foi pulado)
            scheduleNext(checkTime = now)

            _actionCompleted.value = true
        }
    }

    /**
     * Usuário pediu para adiar este set por [SessionPreferences.overshootRepeatMinutes].
     *
     * Reagenda o alarme PRIMARY para `now + overshootRepeatMinutes` mantendo
     * o MESMO exercício. Não rotaciona, não registra log.
     *
     * Deliberadamente NÃO passa pelo [DynamicSchedulerUseCase] (regra 3, descanso
     * mínimo 20min, empurraria snooze=5min para 20min). Mas aplica DOIS limites
     * do session bound: [SessionPreferences.activeDaysOfWeek] (snooze não pode
     * disparar em dia desativado) e [ActivityWindow.endTime] (snooze não pode
     * cair após o fim da janela do dia). Se algum dos dois falhar, snooze faz
     * rollover para o início da janela do próximo dia ativo, paralelo ao que
     * [com.gtg.app.presentation.home.HomeViewModel.rescheduleForNextDayKeepingExercise]
     * faz quando alarme vence fora da janela.
     *
     * NÃO grava `lastCheckMillis`: gravar âncora falsa no snooze corrompe
     * o `rescheduleFromAnchor` se o usuário mudar `baseInterval` durante o
     * intervalo de snooze. Trade-off: mid-snooze interval change pode
     * clobber o snooze, o que é menos pior do que contaminar a cadência futura.
     */
    fun performSnooze() {
        viewModelScope.launch {
            dismissActiveAlarmSideEffects()

            val now = LocalDateTime.now()
            val delayMinutes = sessionPrefs.overshootRepeatMinutes.toLong()
            val rawNextDateTime = now.plusMinutes(delayMinutes)
            val activeWindow = activityWindowRepository.getActiveWindow()
            val activeDays = sessionPrefs.activeDaysOfWeek
            val nextDateTime = clampSnoozeToBounds(rawNextDateTime, activeWindow, activeDays)

            val nextMillis = nextDateTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            alarmScheduler.cancel()
            alarmScheduler.schedule(
                triggerAt = nextDateTime,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                targetReps = targetReps,
            )

            sessionPrefs.setNextAlarm(
                epochMillis = nextMillis,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                targetReps = targetReps,
            )

            // Cross-day rollover (clampSnoozeToBounds empurrou pro próximo dia
            // ativo): encerra a cadeia atual — o T0 de ontem não tem mais
            // significado pro contador. Sem isso, o counter exibe ex.: "+14h+"
            // ao abrir a Home no dia seguinte. Idêntico ao reset que
            // rescheduleForNextDay já faz no rollover do countdown.
            if (nextDateTime.toLocalDate() != now.toLocalDate()) {
                sessionPrefs.setFirstAlarmInChain(0L)
            }

            _actionCompleted.value = true
        }
    }

    /**
     * Garante que o snooze fica dentro de `activeDaysOfWeek` e antes de
     * [ActivityWindow.endTime]. Se cair fora de qualquer um, faz rollover
     * para o início da janela do próximo dia ativo — passando por
     * [DynamicSchedulerUseCase.resolveFirstAlarmStartingAt] (fix 2026-06-11):
     * em DYNAMIC, um bloco cobrindo o início da janela do dia alvo adia o
     * alarme para o fim do cluster + buffer; STRICT continua bare por design.
     * Sem [ActivityWindow] configurada, valida apenas `activeDaysOfWeek` e
     * mantém o fallback meia-noite (o resolver exige janela).
     */
    private suspend fun clampSnoozeToBounds(
        candidate: LocalDateTime,
        window: ActivityWindow?,
        activeDays: Set<java.time.DayOfWeek>,
    ): LocalDateTime {
        val candidateDate = candidate.toLocalDate()
        val dayOk = candidateDate.dayOfWeek in activeDays
        val withinWindow = window == null || !candidate.toLocalTime().isAfter(window.endTime)
        if (dayOk && withinWindow) return candidate

        val nextDate = findNextActiveDate(candidateDate, activeDays)
        if (window == null) return nextDate.atTime(java.time.LocalTime.of(0, 0))

        return dynamicScheduler.resolveFirstAlarmStartingAt(
            startDate = nextDate,
            activeDaysOfWeek = activeDays,
            intervalMode = sessionPrefs.intervalMode,
            prefetchedWindow = window,
        ) ?: nextDate.atTime(window.startTime)
    }

    /**
     * Dispensa o alarme atual: para som, cancela notificação heads-up e
     * cancela qualquer re-alerta automático (overshoot) pendente.
     *
     * Paralelo a [com.gtg.app.presentation.home.HomeViewModel.dismissActiveAlarm].
     * Replicado aqui em vez de extraído: helpers de 3 linhas com 2 chamadores
     * não justificam abstração compartilhada.
     */
    private fun dismissActiveAlarmSideEffects() {
        AlarmSoundPlayer.stop()
        NotificationManagerCompat.from(appContext).cancel(AlarmReceiver.NOTIFICATION_ID)
        alarmScheduler.cancelOvershoot()
    }

    /**
     * Calcula o próximo alarme e agenda via [AlarmScheduler].
     * Rotaciona para o próximo exercício do ciclo round-robin antes de agendar.
     */
    private suspend fun scheduleNext(checkTime: LocalDateTime) {
        val interval = sessionPrefs.baseIntervalMinutes

        // Resolve próximo exercício do ciclo. Sem exercícios ativos → limpa sessão
        // (não há para que agendar).
        val activeExercises = exerciseRepository.getActiveExercises()
        val nextExercise = pickNextExerciseInRotation(exerciseId, activeExercises) ?: run {
            sessionPrefs.clearSession()
            return
        }

        when (
            val result = dynamicScheduler.calculateNextAlarm(
                checkTime = checkTime,
                baseIntervalMinutes = interval,
                activeDaysOfWeek = sessionPrefs.activeDaysOfWeek,
                intervalMode = sessionPrefs.intervalMode,
            )
        ) {
            is ScheduleResult.Scheduled -> {
                scheduleAndPersist(result.dateTime, nextExercise)
            }
            is ScheduleResult.ScheduledTomorrow -> {
                scheduleAndPersist(result.dateTime, nextExercise)
            }
            is ScheduleResult.NoWindowConfigured -> {
                // Sem janela → limpa sessão; o usuário precisa configurar ActivityWindow
                sessionPrefs.clearSession()
            }
        }
    }

    /**
     * Agenda o alarme no sistema e persiste o estado na sessão.
     */
    private fun scheduleAndPersist(nextDateTime: LocalDateTime, exercise: Exercise) {
        val nextMillis = nextDateTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        alarmScheduler.schedule(
            triggerAt = nextDateTime,
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            targetReps = exercise.targetReps,
        )

        sessionPrefs.setNextAlarm(
            epochMillis = nextMillis,
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            targetReps = exercise.targetReps,
        )
    }
}
