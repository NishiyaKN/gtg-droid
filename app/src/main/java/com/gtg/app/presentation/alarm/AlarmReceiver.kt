package com.gtg.app.presentation.alarm

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.gtg.app.GtgApplication
import com.gtg.app.R
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.scheduler.AlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * BroadcastReceiver que recebe o disparo do [AlarmManager] e exibe
 * uma notificação com Full-Screen Intent.
 *
 * Fluxo:
 * 1. Adquire WakeLock parcial (CPU) por até 30s.
 * 2. Marca alarme como pendente no [SessionPreferences].
 * 3. Constrói a notificação usando o canal criado em [GtgApplication]:
 *    - [setFullScreenIntent] → o sistema lança [AlarmActivity] sobre o lockscreen
 *      quando a tela está desligada. Se a tela está ligada, mostra heads-up.
 *    - PRIORITY_HIGH + CATEGORY_ALARM → máxima visibilidade.
 * 4. Dispara via [NotificationManagerCompat.notify].
 * 5. Libera o WakeLock.
 *
 * O canal de notificação é criado em [GtgApplication.onCreate] com IMPORTANCE_HIGH,
 * som de alarme e bypass DND. NÃO criar canal aqui — duplicação causa inconsistências.
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var sessionPrefs: SessionPreferences
    @Inject lateinit var alarmScheduler: AlarmScheduler

    companion object {
        const val EXTRA_EXERCISE_ID = "extra_exercise_id"
        const val EXTRA_EXERCISE_NAME = "extra_exercise_name"
        const val EXTRA_TARGET_REPS = "extra_target_reps"
        /** Marca disparos vindos do re-alerta automático (overshoot). */
        const val EXTRA_IS_OVERSHOOT = "extra_is_overshoot"

        const val NOTIFICATION_ID = 7001
    }

    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "gtg:alarm_receiver_wake_lock",
        )
        wakeLock.acquire(30_000L)

        try {
            val exerciseId = intent.getLongExtra(EXTRA_EXERCISE_ID, -1L)
            val exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME) ?: "Exercício"
            val targetReps = intent.getIntExtra(EXTRA_TARGET_REPS, 0)
            val isOvershoot = intent.getBooleanExtra(EXTRA_IS_OVERSHOOT, false)

            // Guard de overshoot em dia inativo: o re-alerta foi agendado para
            // `now + N min`. Se esse N min cruza meia-noite para um dia que
            // ficou inativo, o disparo cai em dia que o usuário desabilitou.
            // O guard original (no momento do agendamento) não captura essa
            // travessia. Aqui validamos no FIRE — return early sem tocar nada.
            // Não aplicamos ao alarme primário porque o BootReceiver/HomeVM já
            // empurram nextAlarmMillis para dia ativo no schedule.
            if (isOvershoot && LocalDateTime.now().dayOfWeek !in sessionPrefs.activeDaysOfWeek) {
                return
            }

            // Marcar como pendente para o HomeViewModel
            sessionPrefs.setAlarmPending(true)

            // Escolhe o canal de notificação baseado na preferência do usuário.
            // Os dois canais são idênticos exceto por setBypassDnd — ver
            // [GtgApplication.createAlarmNotificationChannels].
            val channelId = if (sessionPrefs.bypassDnd) {
                GtgApplication.ALARM_CHANNEL_PRIORITY_ID
            } else {
                GtgApplication.ALARM_CHANNEL_DEFAULT_ID
            }

            // Intent para a AlarmActivity (full-screen e tap na notificação)
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

            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            }

            // Re-alerta agendado ANTES de play() para fechar a janela de race em que
            // heads-up notification permite ao usuário tocar Check/Skip/Snooze antes
            // do scheduleOvershoot rodar. Se isso acontecesse, o cancelOvershoot
            // do AlarmViewModel viraria no-op (nada armado ainda) e o overshoot
            // seria armado em seguida com extras antigas — exatamente o bug que
            // o cancelamento via AlarmActivity deveria eliminar.
            //
            // Cancelamento: [HomeViewModel.dismissActiveAlarm] ou
            // [AlarmViewModel.dismissActiveAlarmSideEffects].
            //
            // Guard de dias ativos: se o usuário desabilitou o weekday de hoje
            // (corner case: filtro mudou entre o agendamento e o disparo), não
            // reentra na cadeia de overshoot — ela continuaria batendo num dia
            // que o usuário marcou como off.
            if (sessionPrefs.overshootRepeatEnabled && sessionPrefs.isSessionActive) {
                val now = LocalDateTime.now()
                if (now.dayOfWeek in sessionPrefs.activeDaysOfWeek) {
                    val nextOvershoot = now
                        .plusMinutes(sessionPrefs.overshootRepeatMinutes.toLong())
                    alarmScheduler.scheduleOvershoot(
                        triggerAt = nextOvershoot,
                        exerciseId = exerciseId,
                        exerciseName = exerciseName,
                        targetReps = targetReps,
                    )
                }
            }

            // Toca o som configurado pelo usuário (canal é silencioso de propósito —
            // ver [GtgApplication.buildAlarmChannel]). USAGE_ALARM passa por DND,
            // USAGE_NOTIFICATION_RINGTONE respeita DND.
            val soundUri = sessionPrefs.alarmSoundUri?.let(Uri::parse)
            AlarmSoundPlayer.play(
                context = context,
                soundUri = soundUri,
                bypassDnd = sessionPrefs.bypassDnd,
            )
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
