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
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.scheduler.AlarmScheduler
import com.gtg.app.domain.usecase.isInsideActiveWindow
import com.gtg.app.domain.usecase.rescheduleForNextDay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
 * 3. Em-window: [SessionPreferences.recordAlarmDispatchedNow] (atomic — marca
 *    `isAlarmPending=true` e escreve `firstAlarmInChainMillis=now` se for o
 *    primeiro disparo da cadeia).
 * 4. Valida que `now + overshootRepeatMinutes ≤ today.atTime(window.endTime)` —
 *    se passaria, NÃO agenda próximo overshoot (cadeia para sozinha).
 * 5. [AlarmScheduler.scheduleOvershoot] (race-safe gate).
 * 6. [NotificationManagerCompat.notify] (Full-Screen Intent → [AlarmActivity]).
 * 7. [AlarmSoundPlayer.play] + [VibrationPlayer.start] (modalidades).
 *
 * O canal de notificação é criado em [GtgApplication.onCreate] com IMPORTANCE_HIGH,
 * som de alarme e bypass DND. NÃO criar canal aqui — duplicação causa inconsistências.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var sessionPrefs: SessionPreferences
    @Inject lateinit var alarmScheduler: AlarmScheduler
    @Inject lateinit var activityWindowRepository: ActivityWindowRepository

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
    ) {
        val now = LocalDateTime.now()
        val window = activityWindowRepository.getActiveWindow()

        // Fora da janela? Empurra cadeia para o próximo dia ativo e não toca
        // nem agenda overshoot. Espelha clampSnoozeToBounds do AlarmViewModel.
        if (window != null && now > now.toLocalDate().atTime(window.endTime)) {
            // Reagenda preservando exercício pending — usa o do sessionPrefs em vez
            // dos extras do intent porque o intent pode ser de um overshoot stale
            // cujos extras não refletem o estado atual da rotação.
            rescheduleForNextDay(
                alarmScheduler = alarmScheduler,
                sessionPrefs = sessionPrefs,
                window = window,
                activeDays = sessionPrefs.activeDaysOfWeek,
                pendingExerciseId = sessionPrefs.pendingExerciseId.takeIf { it > 0L } ?: exerciseId,
                pendingExerciseName = sessionPrefs.pendingExerciseName.takeIf { it.isNotBlank() } ?: exerciseName,
                pendingTargetReps = sessionPrefs.pendingTargetReps.takeIf { it > 0 } ?: targetReps,
            )
            return
        }

        // Marca dispatch atomicamente: isAlarmPending=true + (se for primeiro da
        // cadeia) firstAlarmInChainMillis=now. Single .edit().apply() para que
        // o listener da Home emita um tick só.
        sessionPrefs.recordAlarmDispatchedNow(System.currentTimeMillis())

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
            .setContentTitle("Hora do GtG!")
            .setContentText("$exerciseName — $targetReps reps")
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
}
