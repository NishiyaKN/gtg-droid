package com.gtg.app.presentation.alarm

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.model.ExerciseLog
import com.gtg.app.domain.model.ScheduleResult
import com.gtg.app.domain.repository.ExerciseLogRepository
import com.gtg.app.domain.repository.ExerciseRepository
import com.gtg.app.domain.scheduler.AlarmScheduler
import com.gtg.app.domain.usecase.DynamicSchedulerUseCase
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
    private val dynamicScheduler: DynamicSchedulerUseCase,
    private val alarmScheduler: AlarmScheduler,
    private val sessionPrefs: SessionPreferences,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // Dados vindos do Intent (propagados automaticamente pelo SavedStateHandle)
    val exerciseId: Long = savedStateHandle[AlarmReceiver.EXTRA_EXERCISE_ID] ?: -1L
    val exerciseName: String = savedStateHandle[AlarmReceiver.EXTRA_EXERCISE_NAME] ?: "Exercício"
    val targetReps: Int = savedStateHandle[AlarmReceiver.EXTRA_TARGET_REPS] ?: 0

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

            // 2. Reagendar (rotação avança mesmo se log foi pulado)
            scheduleNext(checkTime = now)

            _actionCompleted.value = true
        }
    }

    /**
     * Usuário pulou a série. Reagenda SEM registrar log.
     * Usa "agora" como checkTime para que o intervalo base conte a partir deste momento.
     */
    fun performSkip() {
        viewModelScope.launch {
            dismissActiveAlarmSideEffects()
            scheduleNext(checkTime = LocalDateTime.now())
            _actionCompleted.value = true
        }
    }

    /**
     * Dispensa o alarme atual: para som, cancela notificação heads-up e
     * cancela qualquer re-alerta automático (overshoot) pendente.
     *
     * Paralelo a [com.gtg.app.presentation.home.HomeViewModel.dismissActiveAlarm].
     * Replicado aqui em vez de extraído — KD1 do brainstorm:
     * helpers de 3 linhas com 2 chamadores não justificam abstração compartilhada.
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
