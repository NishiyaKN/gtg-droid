package com.gtg.app.presentation.home

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.model.ExerciseBreakdown
import com.gtg.app.domain.model.ExerciseLog
import com.gtg.app.domain.model.ScheduleResult
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.ExerciseLogRepository
import com.gtg.app.domain.repository.ExerciseRepository
import com.gtg.app.domain.scheduler.AlarmScheduler
import com.gtg.app.domain.usecase.DynamicSchedulerUseCase
import com.gtg.app.domain.usecase.GetExerciseBreakdownUseCase
import com.gtg.app.domain.usecase.PlannedSet
import com.gtg.app.domain.usecase.PreviewTodayRoutineUseCase
import com.gtg.app.domain.usecase.pickNextExerciseInRotation
import com.gtg.app.presentation.alarm.AlarmReceiver
import com.gtg.app.presentation.alarm.AlarmSoundPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

// ──────────────────────────────────────────────────────────────
// UI State
// ──────────────────────────────────────────────────────────────

data class HomeUiState(
    /** Lista completa de exercícios ativos (na ordem da rotação round-robin). */
    val activeExercises: List<Exercise> = emptyList(),
    /** Primeiro da [activeExercises] — null se não há exercício ativo. */
    val currentExercise: Exercise? = null,
    /** Nome do exercício pendente (vindo de SessionPrefs para exibição rápida). */
    val pendingExerciseName: String = "",
    /** Reps alvo do exercício pendente. */
    val pendingTargetReps: Int = 0,
    /**
     * Segundos restantes até o próximo alarme. Pode ser **negativo** quando o
     * usuário não fez Check dentro do horário — indica overdue.
     */
    val remainingSeconds: Long = 0,
    /** true quando o alarme já disparou e aguarda o Check do usuário. */
    val isAlarmPending: Boolean = false,
    /**
     * True quando o Check manual está habilitado:
     * - Últimos 5 minutos antes do alarme programado, OU
     * - Já em overdue (timer negativo, ainda dentro da janela do dia).
     * Antes disso, o botão fica desabilitado para evitar checks acidentais.
     */
    val canCheck: Boolean = false,
    /** True quando [remainingSeconds] < 0 — alarme já passou e ainda não houve Check. */
    val isOverdue: Boolean = false,
    /** Sessão GtG em andamento. */
    val isSessionActive: Boolean = false,
    /** Sets completos hoje. */
    val todaySetsCompleted: Int = 0,
    /** Total de reps hoje. */
    val todayTotalReps: Int = 0,
    /** Meta de sets diários configurada. */
    val dailySetTarget: Int = SessionPreferences.DEFAULT_DAILY_SET_TARGET,
    /** true se não há ActivityWindow configurada. */
    val noWindowConfigured: Boolean = false,
    /** Intervalo base atual (minutos). */
    val baseIntervalMinutes: Long = SessionPreferences.DEFAULT_BASE_INTERVAL,
    /** Detalhamento por exercício realizado hoje (ordenado por reps desc). */
    val todayBreakdown: List<ExerciseBreakdown> = emptyList(),
    /** Projeção dos próximos alarmes do dia (dinâmica). */
    val routinePreview: List<PlannedSet> = emptyList(),
    /** Janela de atividade ativa (`null` = não configurada). */
    val activeWindow: ActivityWindow? = null,
    /** Conveniência derivada — true sse [activeWindow] != null. */
    val hasActivityWindow: Boolean = false,
)

// ──────────────────────────────────────────────────────────────
// ViewModel
// ──────────────────────────────────────────────────────────────

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exerciseRepository: ExerciseRepository,
    private val exerciseLogRepository: ExerciseLogRepository,
    private val activityWindowRepository: ActivityWindowRepository,
    private val dynamicScheduler: DynamicSchedulerUseCase,
    private val alarmScheduler: AlarmScheduler,
    private val sessionPrefs: SessionPreferences,
    private val getExerciseBreakdown: GetExerciseBreakdownUseCase,
    private val previewTodayRoutine: PreviewTodayRoutineUseCase,
) : ViewModel() {

    companion object {
        /** Janela em que o botão "Fazer Check" fica habilitado — 5 min antes do alarme. */
        private const val CHECK_WINDOW_SECONDS = 5L * 60
    }

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    // Cancela job anterior antes de relançar — sem isso, dois recálculos
    // concorrentes podem escrever no state fora de ordem.
    private var previewJob: Job? = null

    private data class PrefsSnapshot(
        val nextAlarmMillis: Long,
        val isSessionActive: Boolean,
        val pendingExerciseId: Long,
        val baseIntervalMinutes: Long,
    )

    // `null` no boot: garante que a 1ª emissão do observer não dispare lógica
    // de "mudou" antes de termos uma baseline.
    private var lastPrefsSnapshot: PrefsSnapshot? = null

    init {
        observeSessionPreferences()
        observeDailyStats()
        observeExercises()
        observeActivityWindow()
    }

    private fun observeActivityWindow() {
        viewModelScope.launch {
            activityWindowRepository.observeActiveWindow().collectLatest { window ->
                _state.update {
                    it.copy(
                        activeWindow = window,
                        hasActivityWindow = window != null,
                    )
                }
                // Janela mudou → projeção precisa ser reconstruída usando os
                // novos limites (do contrário a Home segue mostrando os horários
                // calculados com a janela antiga). Countdown também depende da
                // janela para decidir overdue vs. roll-over para o dia seguinte.
                recalculateRoutinePreview()
                restartCountdown()
            }
        }
    }

    // ── Observers ────────────────────────────────────────────────

    /**
     * Observa mudanças no [SessionPreferences] (escrito por [AlarmViewModel]
     * ou pelo próprio HomeViewModel). Atualiza UI state e reinicia countdown.
     */
    private fun observeSessionPreferences() {
        viewModelScope.launch {
            sessionPrefs.observeChanges().collectLatest {
                val snapshot = PrefsSnapshot(
                    nextAlarmMillis = sessionPrefs.nextAlarmMillis,
                    isSessionActive = sessionPrefs.isSessionActive,
                    pendingExerciseId = sessionPrefs.pendingExerciseId,
                    baseIntervalMinutes = sessionPrefs.baseIntervalMinutes,
                )

                _state.update { current ->
                    current.copy(
                        isSessionActive = snapshot.isSessionActive,
                        pendingExerciseName = sessionPrefs.pendingExerciseName,
                        pendingTargetReps = sessionPrefs.pendingTargetReps,
                        isAlarmPending = sessionPrefs.isAlarmPending,
                        dailySetTarget = sessionPrefs.dailySetTarget,
                        baseIntervalMinutes = snapshot.baseIntervalMinutes,
                    )
                }

                val previous = lastPrefsSnapshot
                lastPrefsSnapshot = snapshot

                // Só relança countdown/preview quando algum campo deles muda —
                // evita N relançamentos por write em prefs irrelevantes (som,
                // bypass DND, lastCheckMillis, etc.).
                if (snapshot != previous) {
                    restartCountdown()
                    recalculateRoutinePreview()
                }

                // baseInterval mudou em sessão ativa → recalcular cadência.
                val intervalChangedDuringSession = previous != null &&
                    previous.baseIntervalMinutes != snapshot.baseIntervalMinutes &&
                    snapshot.isSessionActive
                if (intervalChangedDuringSession) {
                    rescheduleOnIntervalChange(snapshot.baseIntervalMinutes)
                }
            }
        }
    }

    /**
     * Observa logs de exercício via Room Flow.
     * Recalcula stats diárias quando um novo log é inserido
     * (inclusive por [AlarmViewModel] em outra Activity).
     */
    private fun observeDailyStats() {
        viewModelScope.launch {
            exerciseLogRepository.observeAll().collectLatest {
                val today = LocalDate.now()
                val sets = exerciseLogRepository.totalSetsBetween(today, today)
                val reps = exerciseLogRepository.totalRepsBetween(today, today)
                val breakdown = getExerciseBreakdown(today, today)

                _state.update { current ->
                    current.copy(
                        todaySetsCompleted = sets,
                        todayTotalReps = reps,
                        todayBreakdown = breakdown,
                    )
                }
            }
        }
    }

    /**
     * Observa a lista de exercícios ativos. Usa o primeiro como exercício atual
     * (rotação multi-exercício será implementada na tela de gerenciamento).
     */
    private fun observeExercises() {
        viewModelScope.launch {
            exerciseRepository.observeActiveExercises().collectLatest { exercises ->
                _state.update { current ->
                    current.copy(
                        activeExercises = exercises,
                        currentExercise = exercises.firstOrNull(),
                    )
                }
                recalculateRoutinePreview()
            }
        }
    }

    /**
     * Recalcula a projeção de [PlannedSet]s para o resto do dia.
     *
     * Origem do primeiro item:
     * - Se há alarme agendado (sessão ativa): usa o `nextAlarmMillis` real.
     * - Senão: usa "agora + intervalo base" como projeção hipotética.
     *
     * Roda em coroutine porque o UseCase faz queries Room (window + blocks).
     */
    private fun recalculateRoutinePreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val activeExercises = _state.value.activeExercises
            if (activeExercises.isEmpty()) {
                _state.update { it.copy(routinePreview = emptyList()) }
                return@launch
            }

            val nextMillis = sessionPrefs.nextAlarmMillis
            val firstAlarm: LocalDateTime
            val isScheduled: Boolean
            val firstIndex: Int
            if (sessionPrefs.isSessionActive && nextMillis > 0L) {
                firstAlarm = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(nextMillis),
                    ZoneId.systemDefault(),
                )
                isScheduled = true
                // Onde estamos na rotação? Encontra o exercício pending na lista.
                // Se sumiu (deletado/desativado), recomeça do 0.
                val pendingId = sessionPrefs.pendingExerciseId
                firstIndex = activeExercises.indexOfFirst { it.id == pendingId }
                    .takeIf { it >= 0 } ?: 0
            } else {
                // Sessão parada — projeta "se eu iniciar agora", mas passando
                // pelo scheduler para respeitar a janela de atividade. Sem isso
                // a UI mostra horários fora da janela (ex: 23h01 quando janela
                // termina às 17h30) por usar `now + interval` cego.
                val candidate = dynamicScheduler.calculateNextAlarm(
                    checkTime = LocalDateTime.now(),
                    baseIntervalMinutes = sessionPrefs.baseIntervalMinutes,
                    activeDaysOfWeek = sessionPrefs.activeDaysOfWeek,
                )
                when (candidate) {
                    is ScheduleResult.Scheduled -> {
                        firstAlarm = candidate.dateTime
                    }
                    is ScheduleResult.ScheduledTomorrow,
                    ScheduleResult.NoWindowConfigured -> {
                        // Fora da janela hoje (ou sem janela) → não há projeção
                        // significativa "do dia" para mostrar.
                        _state.update { it.copy(routinePreview = emptyList()) }
                        return@launch
                    }
                }
                isScheduled = false
                firstIndex = 0
            }

            val preview = previewTodayRoutine(
                firstAlarmAt = firstAlarm,
                activeExercises = activeExercises,
                firstExerciseIndex = firstIndex,
                baseIntervalMinutes = sessionPrefs.baseIntervalMinutes,
                isFirstAlarmScheduled = isScheduled,
                activeDaysOfWeek = sessionPrefs.activeDaysOfWeek,
            )

            _state.update { it.copy(routinePreview = preview) }
        }
    }

    // ── Countdown Timer ──────────────────────────────────────────

    /**
     * Loop de countdown. Lê o `nextAlarmMillis` real e atualiza estado a cada
     * segundo, suportando 3 fases:
     *
     * 1. **Positivo (> 5 min):** countdown normal. Check desabilitado.
     * 2. **Positivo (≤ 5 min) ou overdue:** Check habilitado. Timer continua
     *    em negativo se passar de zero.
     * 3. **Passou do fim da janela hoje:** reagenda automaticamente para o
     *    início da janela do dia seguinte, mantendo o MESMO exercício pending
     *    (alarme não foi resolvido — segue como próximo a fazer).
     *
     * Tick a cada segundo. Detecta roll-over comparando "agora" com windowEnd.
     */
    private fun restartCountdown() {
        countdownJob?.cancel()

        val nextMillis = sessionPrefs.nextAlarmMillis
        if (nextMillis <= 0L || !sessionPrefs.isSessionActive) return

        countdownJob = viewModelScope.launch {
            while (isActive) {
                val nowMillis = System.currentTimeMillis()
                val remaining = (nextMillis - nowMillis) / 1000

                // Roll-over: se passou do fim da janela de hoje E o alarme já
                // está em overdue, agenda para amanhã com mesmo exercício.
                val window = _state.value.activeWindow
                if (window != null && remaining < 0) {
                    val now = LocalDateTime.now()
                    val windowEndToday = now.toLocalDate().atTime(window.endTime)
                    if (now.isAfter(windowEndToday)) {
                        rescheduleForNextDayKeepingExercise(window)
                        return@launch
                    }
                }

                val canCheck = remaining <= CHECK_WINDOW_SECONDS
                val isOverdue = remaining < 0

                _state.update {
                    it.copy(
                        remainingSeconds = remaining,
                        canCheck = canCheck,
                        isOverdue = isOverdue,
                    )
                }

                // Marca alarme pending exatamente ao cruzar zero — útil pra que
                // outros consumidores (AlarmReceiver) saibam que o alarme está
                // aguardando resolução do usuário.
                if (remaining == 0L && !sessionPrefs.isAlarmPending) {
                    sessionPrefs.setAlarmPending(true)
                }

                delay(1000)
            }
        }
    }

    /**
     * Reagenda silenciosamente para o início da janela do dia seguinte,
     * preservando o exercício atual pending (não avança a rotação — o set foi
     * perdido, não pulado deliberadamente).
     */
    private fun rescheduleForNextDayKeepingExercise(window: ActivityWindow) {
        val nextDateTime = LocalDate.now().plusDays(1).atTime(window.startTime)
        val nextMillis = nextDateTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val exerciseId = sessionPrefs.pendingExerciseId
        val exerciseName = sessionPrefs.pendingExerciseName
        val targetReps = sessionPrefs.pendingTargetReps

        alarmScheduler.cancel()
        // Evita que um overshoot agendado ontem dispare hoje — limpa antes do
        // reschedule para o dia seguinte.
        alarmScheduler.cancelOvershoot()
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
        // setNextAlarm já zera isAlarmPending. Observer captura e reinicia
        // o countdown apontando para o novo nextAlarmMillis (positivo agora).
    }

    // ── Ações do Usuário ─────────────────────────────────────────

    /**
     * Inicia uma nova sessão GtG.
     * Agenda o primeiro alarme a partir de agora + intervaloBase.
     */
    fun startSession() {
        val exercise = _state.value.currentExercise ?: return

        viewModelScope.launch {
            sessionPrefs.setSessionActive(true)
            // Âncora para o recálculo de cadência (mudança de intervalo).
            sessionPrefs.setLastCheck(System.currentTimeMillis())

            val now = LocalDateTime.now()
            // Para o primeiro alarme, o "checkTime" é agora — simula um check imediato
            when (val result = dynamicScheduler.calculateNextAlarm(
                checkTime = now,
                baseIntervalMinutes = sessionPrefs.baseIntervalMinutes,
                activeDaysOfWeek = sessionPrefs.activeDaysOfWeek,
            )) {
                is ScheduleResult.Scheduled -> scheduleAndPersist(result.dateTime, exercise)
                is ScheduleResult.ScheduledTomorrow -> scheduleAndPersist(result.dateTime, exercise)
                is ScheduleResult.NoWindowConfigured -> {
                    _state.update { it.copy(noWindowConfigured = true) }
                    sessionPrefs.setSessionActive(false)
                }
            }
        }
    }

    /**
     * Para a sessão GtG. Cancela alarme pendente e limpa estado.
     */
    fun stopSession() {
        dismissActiveAlarm()
        alarmScheduler.cancel()
        sessionPrefs.clearSession()
        countdownJob?.cancel()
        _state.update {
            it.copy(
                isSessionActive = false,
                remainingSeconds = 0,
                isAlarmPending = false,
            )
        }
    }

    /**
     * Para o som do alarme e cancela a notificação heads-up.
     *
     * Necessário porque o [AlarmReceiver] dispara `AlarmSoundPlayer.play()` quando
     * o alarme bate. Se o app está em foreground, o sistema NÃO abre a
     * [com.gtg.app.presentation.alarm.AlarmActivity] full-screen (só mostra
     * heads-up notification) — o usuário interage com a Home, então cabe aqui
     * limpar som e notificação. A AlarmActivity já cobre o caso de tela cheia.
     */
    private fun dismissActiveAlarm() {
        AlarmSoundPlayer.stop()
        NotificationManagerCompat.from(context).cancel(AlarmReceiver.NOTIFICATION_ID)
        // Cancela qualquer re-alerta pendente do overshoot — qualquer ação do
        // usuário que dispense o alarme atual deve interromper a cadeia de
        // re-alertas que o [AlarmReceiver] poderia ter agendado.
        alarmScheduler.cancelOvershoot()
    }

    /**
     * Silencia o alarme sem fazer Check nem reagendar.
     *
     * - Para o som e remove a notificação.
     * - Limpa o flag `isAlarmPending` (usuário já reconheceu o alarme).
     * - **Não** registra log, **não** avança a rotação, **não** mexe no timer.
     * - O timer continua rodando em overdue até o usuário decidir fazer Check
     *   (ou Pular, ou até a janela de atividade acabar e rolar para o dia seguinte).
     */
    fun dismissAlarm() {
        dismissActiveAlarm()
        sessionPrefs.setAlarmPending(false)
        _state.update { it.copy(isAlarmPending = false) }
    }

    /**
     * Check manual — o usuário antecipa a série antes do alarme tocar.
     * Registra log do EXERCÍCIO ATUAL e reagenda com o PRÓXIMO da rotação.
     */
    fun performManualCheck() {
        val currentId = sessionPrefs.pendingExerciseId
        val currentTargetReps = sessionPrefs.pendingTargetReps.takeIf { it > 0 }
            ?: _state.value.currentExercise?.targetReps ?: return
        val activeList = _state.value.activeExercises
        if (activeList.isEmpty()) return

        viewModelScope.launch {
            val now = LocalDateTime.now()
            val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            // Para som + notificação se o alarme estava ativo
            dismissActiveAlarm()

            sessionPrefs.setLastCheck(nowMillis)

            // Log: o exercício que ACABOU de ser feito (pending atual).
            if (currentId > 0L) {
                exerciseLogRepository.insert(
                    ExerciseLog(
                        exerciseId = currentId,
                        timestamp = now,
                        repsCompleted = currentTargetReps,
                    ),
                )
            }

            // Cancelar alarme atual
            alarmScheduler.cancel()

            // Rotação: o PRÓXIMO alarme usa o próximo exercício do ciclo.
            val nextExercise = pickNextExerciseInRotation(currentId, activeList) ?: return@launch

            // Reagendar
            when (val result = dynamicScheduler.calculateNextAlarm(
                checkTime = now,
                baseIntervalMinutes = sessionPrefs.baseIntervalMinutes,
                activeDaysOfWeek = sessionPrefs.activeDaysOfWeek,
            )) {
                is ScheduleResult.Scheduled -> scheduleAndPersist(result.dateTime, nextExercise)
                is ScheduleResult.ScheduledTomorrow -> scheduleAndPersist(result.dateTime, nextExercise)
                is ScheduleResult.NoWindowConfigured -> {
                    _state.update { it.copy(noWindowConfigured = true) }
                }
            }

            _state.update { it.copy(isAlarmPending = false) }
        }
    }

    /** Limpa o flag de "sem janela configurada" (após o usuário ver o aviso). */
    fun dismissNoWindowWarning() {
        _state.update { it.copy(noWindowConfigured = false) }
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Reagenda o alarme da sessão ativa quando o usuário muda o `baseInterval`
     * em Settings. Semântica: `próximo = lastCheck + novoIntervalo`, com regras
     * 3/4/5 do [DynamicSchedulerUseCase] aplicadas por cima.
     *
     * Para o som — o alarme original está sendo substituído, manter o som
     * seria inconsistente com o novo agendamento.
     */
    private suspend fun rescheduleOnIntervalChange(newIntervalMinutes: Long) {
        val pendingId = sessionPrefs.pendingExerciseId
        if (pendingId <= 0L) return

        val anchorMillis = resolveLastCheckMillis()
        val anchor = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(anchorMillis),
            ZoneId.systemDefault(),
        )

        dismissActiveAlarm()

        val nextDateTime = when (
            val result = dynamicScheduler.calculateNextAlarm(
                checkTime = anchor,
                baseIntervalMinutes = newIntervalMinutes,
                activeDaysOfWeek = sessionPrefs.activeDaysOfWeek,
            )
        ) {
            is ScheduleResult.Scheduled -> result.dateTime
            is ScheduleResult.ScheduledTomorrow -> result.dateTime
            is ScheduleResult.NoWindowConfigured -> {
                _state.update { it.copy(noWindowConfigured = true) }
                return
            }
        }

        alarmScheduler.cancel()

        // Se o exercício pending sumiu da lista ativa (desativado/removido
        // durante a sessão), reagenda usando os dados que já estão em prefs —
        // preserva o estado da rotação sem precisar ressincronizar com Room.
        val exercise = _state.value.activeExercises.firstOrNull { it.id == pendingId }
        if (exercise != null) {
            scheduleAndPersist(nextDateTime, exercise.id, exercise.name, exercise.targetReps)
        } else {
            scheduleAndPersist(
                nextDateTime = nextDateTime,
                exerciseId = pendingId,
                exerciseName = sessionPrefs.pendingExerciseName,
                targetReps = sessionPrefs.pendingTargetReps,
            )
        }
    }

    // Sessão pré-migração (ou nunca houve check) → trata "agora" como check 0
    // e persiste para que o próximo recálculo tenha âncora estável.
    private fun resolveLastCheckMillis(): Long {
        val stored = sessionPrefs.lastCheckMillis
        if (stored > 0L) return stored
        val now = System.currentTimeMillis()
        sessionPrefs.setLastCheck(now)
        return now
    }

    private fun scheduleAndPersist(nextDateTime: LocalDateTime, exercise: Exercise) =
        scheduleAndPersist(nextDateTime, exercise.id, exercise.name, exercise.targetReps)

    private fun scheduleAndPersist(
        nextDateTime: LocalDateTime,
        exerciseId: Long,
        exerciseName: String,
        targetReps: Int,
    ) {
        val nextMillis = nextDateTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

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
    }
}
