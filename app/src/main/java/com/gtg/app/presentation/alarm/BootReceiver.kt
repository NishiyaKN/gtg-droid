package com.gtg.app.presentation.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.scheduler.AlarmScheduler
import com.gtg.app.domain.usecase.findNextActiveDate
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * Reagenda o alarme pendente após reboot do dispositivo ou atualização do app.
 *
 * O Android apaga TODOS os alarmes do [AlarmManager] quando o dispositivo reinicia
 * ou quando o pacote do app é substituído (update). Este receiver restaura o estado.
 *
 * Escuta:
 * - [Intent.ACTION_BOOT_COMPLETED]: dispositivo reiniciou.
 * - [Intent.ACTION_MY_PACKAGE_REPLACED]: app foi atualizado (novo APK instalado).
 *
 * Fluxo:
 * 1. Lê o estado salvo em [SessionPreferences] (sícrono — SharedPreferences).
 * 2. Se a sessão está ativa e há um alarme salvo:
 *    a. Se o horário ainda está no futuro → reagenda via [AlarmScheduler.schedule].
 *    b. Se já passou → marca como pendente para que o [HomeViewModel] exiba o Check.
 * 3. Se a sessão não está ativa → noop.
 *
 * NOTA: Não usa coroutines — tudo é síncrono (SharedPreferences + AlarmManager).
 * Isso é intencional: BroadcastReceiver.onReceive() tem ~10s para completar,
 * e não precisamos de acesso ao Room aqui.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmScheduler: AlarmScheduler
    @Inject lateinit var sessionPrefs: SessionPreferences

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // Sessão inativa → nada a fazer
        if (!sessionPrefs.isSessionActive) return

        val nextAlarmMillis = sessionPrefs.nextAlarmMillis
        if (nextAlarmMillis <= 0L) return

        val now = System.currentTimeMillis()

        if (nextAlarmMillis > now) {
            // Alarme ainda no futuro → reagendar
            val original = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(nextAlarmMillis),
                ZoneId.systemDefault(),
            )

            // Filtro de dias da semana pode ter sido alterado entre o
            // momento em que o alarme foi agendado e este reboot. Se o dia
            // agora está inativo, empurra para o próximo dia ativo,
            // preservando o time-of-day original (já validado contra a janela
            // de atividade quando o alarme foi computado). Persiste o novo
            // millis para manter o resto do app sincronizado.
            val activeDays = sessionPrefs.activeDaysOfWeek
            val triggerAt = if (original.dayOfWeek in activeDays) {
                original
            } else {
                val adjustedDate = findNextActiveDate(original.toLocalDate(), activeDays)
                val adjusted = adjustedDate.atTime(original.toLocalTime())
                sessionPrefs.setNextAlarm(
                    epochMillis = adjusted.atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli(),
                    exerciseId = sessionPrefs.pendingExerciseId,
                    exerciseName = sessionPrefs.pendingExerciseName,
                    targetReps = sessionPrefs.pendingTargetReps,
                )
                adjusted
            }

            alarmScheduler.schedule(
                triggerAt = triggerAt,
                exerciseId = sessionPrefs.pendingExerciseId,
                exerciseName = sessionPrefs.pendingExerciseName,
                targetReps = sessionPrefs.pendingTargetReps,
            )
        } else {
            // Alarme já deveria ter disparado → marcar como pendente.
            // Na próxima vez que o usuário abrir o app, o HomeViewModel
            // mostrará o timer em overdue e o botão de Check habilitado
            // (countdown unificado, sem estado PENDING_CHECK separado).
            sessionPrefs.setAlarmPending(true)
        }
    }
}
