package com.gtg.app.presentation.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.scheduler.AlarmScheduler
import com.gtg.app.domain.usecase.findNextActiveDate
import com.gtg.app.domain.usecase.toEpochMillis
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

        // Defesa em profundidade contra ghost-chain: device reboot encerra a
        // cadeia mental do usuário; zera o anchor para que o próximo dispatch
        // escreva timestamp fresco. MY_PACKAGE_REPLACED é update silencioso do
        // Play Store — preserva firstAlarmInChainMillis (usuário pode estar
        // mid-cadeia e não tem motivo para perder o histórico de delay).
        //
        // ATENÇÃO: este reset roda ANTES do guard de sessão inativa para curar
        // o ghost-state em que clearSession() foi interrompido por process kill
        // entre a escrita em memória e o flush async para disco — cenário em
        // que isSessionActive=false mas firstAlarmInChainMillis ainda > 0L.
        // Sem isso, próxima sessão inicia herdando T0 stale.
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            sessionPrefs.setFirstAlarmInChain(0L)
        }

        // Sessão inativa → resto do receiver não tem nada a fazer
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
            // de atividade quando o alarme foi computado).
            val activeDays = sessionPrefs.activeDaysOfWeek
            val needsShift = original.dayOfWeek !in activeDays
            val triggerAt = if (!needsShift) {
                original
            } else {
                val adjustedDate = findNextActiveDate(original.toLocalDate(), activeDays)
                adjustedDate.atTime(original.toLocalTime())
            }

            // Schedule PRIMEIRO; persiste DEPOIS — sem essa ordem, se
            // AlarmManager rejeitar (SCHEDULE_EXACT_ALARM revogado em
            // runtime), AlarmSchedulerImpl engole SecurityException
            // silenciosamente e o app fica com prefs novos apontando para
            // alarme inexistente.
            alarmScheduler.schedule(
                triggerAt = triggerAt,
                exerciseId = sessionPrefs.pendingExerciseId,
                exerciseName = sessionPrefs.pendingExerciseName,
                targetReps = sessionPrefs.pendingTargetReps,
            )
            if (needsShift) {
                sessionPrefs.setNextAlarm(
                    epochMillis = triggerAt.toEpochMillis(),
                    exerciseId = sessionPrefs.pendingExerciseId,
                    exerciseName = sessionPrefs.pendingExerciseName,
                    targetReps = sessionPrefs.pendingTargetReps,
                )
            }
        } else {
            // Alarme já deveria ter disparado → marcar como pendente.
            // Na próxima vez que o usuário abrir o app, o HomeViewModel
            // mostrará o timer em overdue e o botão de Check habilitado
            // (countdown unificado, sem estado PENDING_CHECK separado).
            sessionPrefs.setAlarmPending(true)

            // O reboot apagou também o overshoot do AlarmManager — sem este
            // rearme, a cadeia de re-alertas morre em silêncio e o usuário só
            // descobre o set perdido ao abrir o app. Um único overshoot em
            // now + overshootRepeatMinutes retoma a cadeia; os seguintes são
            // rearmados pelo fluxo normal do AlarmReceiver. Nada é persistido
            // (overshoot nunca é) — falha de permissão é benigna.
            if (sessionPrefs.overshootRepeatEnabled) {
                alarmScheduler.scheduleOvershoot(
                    triggerAt = LocalDateTime.now()
                        .plusMinutes(sessionPrefs.overshootRepeatMinutes.toLong()),
                    exerciseId = sessionPrefs.pendingExerciseId,
                    exerciseName = sessionPrefs.pendingExerciseName,
                    targetReps = sessionPrefs.pendingTargetReps,
                )
            }
        }
    }
}
