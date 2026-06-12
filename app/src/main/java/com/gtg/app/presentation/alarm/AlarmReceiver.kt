package com.gtg.app.presentation.alarm

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gtg.app.GtgApplication
import com.gtg.app.R
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.scheduler.AlarmScheduler
import com.gtg.app.domain.usecase.DynamicSchedulerUseCase
import com.gtg.app.domain.usecase.isInsideActiveWindow
import com.gtg.app.domain.usecase.rescheduleForNextDay
import com.gtg.app.domain.usecase.suppressPrimaryInsideBlock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * BroadcastReceiver que recebe o disparo do [AlarmManager] e exibe
 * uma notificação com Full-Screen Intent.
 *
 * Migrado para `goAsync()` + corrotina para permitir suspend reads
 * ([ActivityWindowRepository.getActiveWindow]) antes de tocar. Toda a lógica
 * suspend roda dentro de `try { withTimeout(9_000L) { ... } } finally {
 * wakeLock.release(); pendingResult.finish() }` — qualquer branch ou exceção
 * passa pelo `finally`, garantindo que o `PendingResult` é sempre fechado
 * (ANR window seria ~10s sem isso).
 *
 * `wakeLock.acquire(15_000L)` cobre o budget de 9s do `withTimeout` mais
 * folga para teardown — não estendido para 60s porque o PendingResult system
 * deadline é ~10s e wakelock longo sem contexto válido só drena bateria.
 *
 * **Ordem dos efeitos** (preserva race invariant — schedule de overshoot ANTES
 * de notify, ver `docs/solutions/logic-errors/alarm-receiver-overshoot-schedule-race-2026-05-19.md`):
 * 1. Lê [ActivityWindow] (suspend).
 * 2. Out-of-window? → [rescheduleForNextDay] e retorna sem tocar.
 * 3. Block guard fire-time (fix 2026-06-11): `now` dentro de bloco
 *    (manual/Calendar)? → suprime e rearma, sub-budget de 2s com fail-open
 *    para Ring. Seguro ANTES do scheduleOvershoot: a race do invariant exige
 *    notificação visível, que ainda não existe aqui.
 * 4. Valida que `now + overshootRepeatMinutes ≤ today.atTime(window.endTime)` —
 *    se passaria, NÃO agenda próximo overshoot (cadeia para sozinha).
 * 5. [AlarmScheduler.scheduleOvershoot] (race-safe gate).
 * 6. [NotificationManagerCompat.notify] (Full-Screen Intent → [AlarmActivity]).
 * 7. [SessionPreferences.recordAlarmDispatchedNow] (atomic — marca
 *    `isAlarmPending=true` e escreve `firstAlarmInChainMillis=now` se for o
 *    primeiro disparo da cadeia; após notify para evitar partial-state se o
 *    budget expirar entre o write e o notify).
 * 8. [AlarmSoundPlayer.play] + [VibrationPlayer.start] (modalidades).
 *
 * O canal de notificação é criado em [GtgApplication.onCreate] com IMPORTANCE_HIGH,
 * som de alarme e bypass DND. NÃO criar canal aqui — duplicação causa inconsistências.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var sessionPrefs: SessionPreferences
    @Inject lateinit var alarmScheduler: AlarmScheduler
    @Inject lateinit var activityWindowRepository: ActivityWindowRepository
    @Inject lateinit var dynamicScheduler: DynamicSchedulerUseCase

    companion object {
        private const val TAG = "AlarmReceiver"

        const val EXTRA_EXERCISE_ID = "extra_exercise_id"
        const val EXTRA_EXERCISE_NAME = "extra_exercise_name"
        const val EXTRA_TARGET_REPS = "extra_target_reps"
        /** Marca disparos vindos do re-alerta automático (overshoot). */
        const val EXTRA_IS_OVERSHOOT = "extra_is_overshoot"

        const val NOTIFICATION_ID = 7001

        /** Budget para query Room + agendamento + I/O dentro do goAsync. */
        private const val SUSPEND_BUDGET_MILLIS = 9_000L
        /** WakeLock cobre o budget + folga; menor que 60s pra evitar drain
         * com PendingResult já morto. */
        private const val WAKELOCK_TIMEOUT_MILLIS = 15_000L

        /**
         * Sub-budget do guard fire-time de blocos (fix 2026-06-11), bem menor
         * que [SUSPEND_BUDGET_MILLIS]: se a consulta ao CalendarProvider
         * estiver lenta (pós-doze), o guard degrada para Ring (fail-open) em
         * vez de estourar o budget compartilhado e perder o alarme inteiro.
         */
        private const val BLOCK_GUARD_BUDGET_MILLIS = 2_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "gtg:alarm_receiver_wake_lock",
        ).apply { acquire(WAKELOCK_TIMEOUT_MILLIS) }

        val exerciseId = intent.getLongExtra(EXTRA_EXERCISE_ID, -1L)
        val exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME) ?: "Exercício"
        val targetReps = intent.getIntExtra(EXTRA_TARGET_REPS, 0)
        val isOvershoot = intent.getBooleanExtra(EXTRA_IS_OVERSHOOT, false)

        // Guard de overshoot em dia inativo: o re-alerta foi agendado para
        // `now + N min`. Se esse N min cruza meia-noite para um dia que
        // ficou inativo, o disparo cai em dia que o usuário desabilitou.
        // Defesa em profundidade — também coberto pelo window guard abaixo
        // em casos típicos, mas mantém invariante quando window não está
        // configurada e dia ficou inativo entre agendamento e disparo.
        if (isOvershoot && LocalDateTime.now().dayOfWeek !in sessionPrefs.activeDaysOfWeek) {
            if (wakeLock.isHeld) wakeLock.release()
            pendingResult.finish()
            return
        }

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeout(SUSPEND_BUDGET_MILLIS) {
                    handleDispatch(
                        context = context,
                        exerciseId = exerciseId,
                        exerciseName = exerciseName,
                        targetReps = targetReps,
                        isOvershoot = isOvershoot,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "AlarmReceiver work exceeded ${SUSPEND_BUDGET_MILLIS}ms budget", e)
            } catch (e: Exception) {
                Log.e(TAG, "AlarmReceiver work failed", e)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleDispatch(
        context: Context,
        exerciseId: Long,
        exerciseName: String,
        targetReps: Int,
        isOvershoot: Boolean,
    ) {
        val now = LocalDateTime.now()
        val window = activityWindowRepository.getActiveWindow()

        // Fora da janela? Empurra cadeia para o próximo dia ativo e não toca
        // nem agenda overshoot. Espelha clampSnoozeToBounds do AlarmViewModel.
        if (window != null && now > now.toLocalDate().atTime(window.endTime)) {
            rollChainToNextDay(window, exerciseId, exerciseName, targetReps)
            return
        }

        // Guard fire-time de blocos (fix 2026-06-11): eventos de calendário
        // criados/movidos APÓS o arme nunca passaram pelo schedule-site —
        // re-valida aqui. Posição: DEPOIS do guard de janela, ANTES de
        // scheduleOvershoot/notify. A race que o invariant "overshoot antes
        // de notify" protege exige uma notificação visível para o usuário
        // tocar Check/Snooze — que ainda não existe neste ponto. Ver doc do
        // invariant (amendada).
        if (window != null &&
            suppressedByBlockGuard(now, window, isOvershoot, exerciseId, exerciseName, targetReps)
        ) {
            return
        }

        // Build da notificação (sem disparar ainda — race invariant exige
        // scheduleOvershoot antes).
        val channelId = if (sessionPrefs.bypassDnd) {
            GtgApplication.ALARM_CHANNEL_PRIORITY_ID
        } else {
            GtgApplication.ALARM_CHANNEL_DEFAULT_ID
        }

        val activityIntent = Intent(context, AlarmActivity::class.java).apply {
            putExtra(EXTRA_EXERCISE_ID, exerciseId)
            putExtra(EXTRA_EXERCISE_NAME, exerciseName)
            putExtra(EXTRA_TARGET_REPS, targetReps)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_fitness)
            .setContentTitle(context.getString(R.string.alarm_notification_title))
            .setContentText(
                context.getString(R.string.alarm_notification_text, exerciseName, targetReps),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        // Próximo overshoot — agendado ANTES de notify para fechar a janela de
        // race em que heads-up notification permite ao usuário tocar
        // Check/Snooze antes do scheduleOvershoot rodar. Ver
        // docs/solutions/logic-errors/alarm-receiver-overshoot-schedule-race-2026-05-19.md.
        //
        // Validação de window endTime no agendamento (não só no fire) — espelha
        // o pattern do clampSnoozeToBounds. Se o próximo overshoot cairia depois
        // do endTime de hoje, NÃO agenda — cadeia para sozinha; usuário virá ver
        // o estado pendente quando abrir o app, e o rollover acontece ou via
        // HomeViewModel.countdown ou no próximo dispatch que entrar aqui fora da
        // janela.
        if (sessionPrefs.overshootRepeatEnabled && sessionPrefs.isSessionActive) {
            val nextOvershoot = now.plusMinutes(sessionPrefs.overshootRepeatMinutes.toLong())
            // Cross-midnight guard: o overshoot deve cair no mesmo dia ativo
            // e dentro da janela do dia atual (isInsideActiveWindow cobre
            // dia + janela, mas precisamos do same-day extra para evitar
            // agendar em dia subsequente sem rollover explícito).
            val sameDay = nextOvershoot.toLocalDate() == now.toLocalDate()
            if (sameDay && isInsideActiveWindow(nextOvershoot, window, sessionPrefs.activeDaysOfWeek)) {
                alarmScheduler.scheduleOvershoot(
                    triggerAt = nextOvershoot,
                    exerciseId = exerciseId,
                    exerciseName = exerciseName,
                    targetReps = targetReps,
                )
            }
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        // Marca dispatch atomicamente: isAlarmPending=true + (se for primeiro da
        // cadeia) firstAlarmInChainMillis=now. Single .edit().apply() para que
        // o listener da Home emita um tick só.
        //
        // Posicionado APÓS notify (não antes) para evitar partial-state window
        // se withTimeout(9s) expirar entre o write e o notify — UI veria
        // pending=true sem notificação correspondente. Race invariant doc
        // exige scheduleOvershoot antes de notify, mas é silente sobre o
        // chain anchor write; aqui está depois do gate visível.
        sessionPrefs.recordAlarmDispatchedNow(System.currentTimeMillis())

        // Modalidades — Som + Vibração. Visual é responsabilidade da
        // AlarmActivity (lê visualEnabled e aplica o pulse na UI).
        if (sessionPrefs.soundEnabled) {
            val soundUri = sessionPrefs.alarmSoundUri?.let(Uri::parse)
            AlarmSoundPlayer.play(
                context = context,
                soundUri = soundUri,
                bypassDnd = sessionPrefs.bypassDnd,
            )
        }

        if (sessionPrefs.vibrationEnabled) {
            VibrationPlayer.start(context, bypassDnd = sessionPrefs.bypassDnd)
        }
    }

    /**
     * Avalia o block guard e executa os side-effects de supressão.
     *
     * @return `true` quando o dispatch foi suprimido (caller retorna sem
     *   tocar); `false` quando o fluxo normal de notificação deve seguir.
     *
     * Sub-budget próprio com fail-open para Ring: o guard falhando ou
     * estourando NUNCA pode custar o alarme (perda silenciosa é pior que
     * tocar durante a reunião).
     */
    private suspend fun suppressedByBlockGuard(
        now: LocalDateTime,
        window: ActivityWindow,
        isOvershoot: Boolean,
        fallbackExerciseId: Long,
        fallbackExerciseName: String,
        fallbackTargetReps: Int,
    ): Boolean {
        val decision = try {
            withTimeoutOrNull(BLOCK_GUARD_BUDGET_MILLIS) {
                dynamicScheduler.decideFireTimeDispatch(
                    now = now,
                    window = window,
                    intervalMode = sessionPrefs.intervalMode,
                    canScheduleExactAlarms = alarmScheduler.canScheduleExactAlarms(),
                )
            } ?: DynamicSchedulerUseCase.FireTimeDecision.Ring.also {
                Log.w(TAG, "block guard estourou ${BLOCK_GUARD_BUDGET_MILLIS}ms — fail-open (Ring)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "block guard falhou — fail-open (Ring)", e)
            DynamicSchedulerUseCase.FireTimeDecision.Ring
        }

        when (decision) {
            DynamicSchedulerUseCase.FireTimeDecision.Ring -> return false

            is DynamicSchedulerUseCase.FireTimeDecision.SuppressAndReschedule -> {
                if (isOvershoot) {
                    // Re-alerta dentro de reunião: silencia e re-arma o
                    // overshoot para depois do cluster, SEM tocar prefs —
                    // setNextAlarm flips isAlarmPending e evaporaria o set
                    // pendente do usuário; o anchor da cadeia (T0) também
                    // precisa sobreviver. rearmAt é same-day/in-window por
                    // construção da decisão.
                    if (sessionPrefs.overshootRepeatEnabled && sessionPrefs.isSessionActive) {
                        alarmScheduler.scheduleOvershoot(
                            triggerAt = decision.rearmAt,
                            exerciseId = fallbackExerciseId,
                            exerciseName = fallbackExerciseName,
                            targetReps = fallbackTargetReps,
                        )
                    }
                } else {
                    val (pendingId, pendingName, pendingReps) =
                        resolvePendingExercise(fallbackExerciseId, fallbackExerciseName, fallbackTargetReps)
                    suppressPrimaryInsideBlock(
                        alarmScheduler = alarmScheduler,
                        sessionPrefs = sessionPrefs,
                        rearmAt = decision.rearmAt,
                        pendingExerciseId = pendingId,
                        pendingExerciseName = pendingName,
                        pendingTargetReps = pendingReps,
                    )
                }
            }

            DynamicSchedulerUseCase.FireTimeDecision.SuppressAndRollToNextDay -> {
                if (!isOvershoot) {
                    // Cluster estoura a janela do dia → mesmo tratamento do
                    // guard de fim de janela: rolar para o próximo dia ativo.
                    rollChainToNextDay(window, fallbackExerciseId, fallbackExerciseName, fallbackTargetReps)
                }
                // Overshoot: cadeia estanca em silêncio — idêntico ao
                // comportamento existente quando o próximo overshoot
                // cairia após o fim da janela.
            }
        }
        return true
    }

    /**
     * Rola a cadeia para o próximo dia ativo, preservando o exercício pending.
     * Compartilhado entre o guard de fim de janela e o block guard.
     */
    private suspend fun rollChainToNextDay(
        window: ActivityWindow,
        fallbackExerciseId: Long,
        fallbackExerciseName: String,
        fallbackTargetReps: Int,
    ) {
        val (pendingId, pendingName, pendingReps) =
            resolvePendingExercise(fallbackExerciseId, fallbackExerciseName, fallbackTargetReps)
        rescheduleForNextDay(
            alarmScheduler = alarmScheduler,
            sessionPrefs = sessionPrefs,
            window = window,
            activeDays = sessionPrefs.activeDaysOfWeek,
            pendingExerciseId = pendingId,
            pendingExerciseName = pendingName,
            pendingTargetReps = pendingReps,
            dynamicScheduler = dynamicScheduler,
        )
    }

    /**
     * Exercício pending do sessionPrefs com fallback nos extras do intent —
     * o intent pode ser de um overshoot stale cujos extras não refletem o
     * estado atual da rotação. Política única para todos os caminhos de
     * reagendamento do receiver.
     */
    private fun resolvePendingExercise(
        fallbackExerciseId: Long,
        fallbackExerciseName: String,
        fallbackTargetReps: Int,
    ): Triple<Long, String, Int> = Triple(
        sessionPrefs.pendingExerciseId.takeIf { it > 0L } ?: fallbackExerciseId,
        sessionPrefs.pendingExerciseName.takeIf { it.isNotBlank() } ?: fallbackExerciseName,
        sessionPrefs.pendingTargetReps.takeIf { it > 0 } ?: fallbackTargetReps,
    )
}
