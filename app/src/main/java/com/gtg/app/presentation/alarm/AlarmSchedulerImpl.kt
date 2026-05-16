package com.gtg.app.presentation.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.gtg.app.domain.scheduler.AlarmScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementação do [AlarmScheduler] usando [AlarmManager].
 *
 * Usa [AlarmManager.setAlarmClock] como método primário:
 * - Isento das restrições de alarmes exatos do Android 12+ (não precisa de SCHEDULE_EXACT_ALARM).
 * - Mostra ícone de alarme na status bar (comportamento desejável para GtG).
 * - Garante disparo mesmo em Doze mode e App Standby.
 * - PendingIntent usa FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE (obrigatório Android 12+).
 */
@Singleton
class AlarmSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AlarmScheduler {

    companion object {
        const val ALARM_REQUEST_CODE = 1001

        /**
         * RequestCode separado para o re-alerta de overshoot. Usar requestCode
         * próprio impede que o overshoot sobrescreva o alarme principal (e
         * vice-versa) — eles convivem como dois PendingIntents distintos.
         */
        const val OVERSHOOT_REQUEST_CODE = 1002
    }

    override fun schedule(
        triggerAt: LocalDateTime,
        exerciseId: Long,
        exerciseName: String,
        targetReps: Int,
    ) {
        scheduleInternal(
            triggerAt = triggerAt,
            pendingIntent = buildPendingIntent(
                requestCode = ALARM_REQUEST_CODE,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                targetReps = targetReps,
                isOvershoot = false,
            ),
            useAlarmClock = true,
        )
    }

    override fun cancel() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(
            requestCode = ALARM_REQUEST_CODE,
            exerciseId = 0,
            exerciseName = "",
            targetReps = 0,
            isOvershoot = false,
        )
        alarmManager.cancel(pendingIntent)
    }

    override fun scheduleOvershoot(
        triggerAt: LocalDateTime,
        exerciseId: Long,
        exerciseName: String,
        targetReps: Int,
    ) {
        // Re-alerta usa setExactAndAllowWhileIdle — não precisa do ícone na
        // status bar nem do flujo de "AlarmClock" (já houve um disparo
        // principal). Mais leve e não compete pelo slot de alarmClock.
        scheduleInternal(
            triggerAt = triggerAt,
            pendingIntent = buildPendingIntent(
                requestCode = OVERSHOOT_REQUEST_CODE,
                exerciseId = exerciseId,
                exerciseName = exerciseName,
                targetReps = targetReps,
                isOvershoot = true,
            ),
            useAlarmClock = false,
        )
    }

    override fun cancelOvershoot() {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = buildPendingIntent(
            requestCode = OVERSHOOT_REQUEST_CODE,
            exerciseId = 0,
            exerciseName = "",
            targetReps = 0,
            isOvershoot = true,
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun scheduleInternal(
        triggerAt: LocalDateTime,
        pendingIntent: PendingIntent,
        useAlarmClock: Boolean,
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerMillis = triggerAt
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        try {
            if (useAlarmClock) {
                val showIntent = buildShowIntent()
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMillis, showIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerMillis,
                    pendingIntent,
                )
            }
        } catch (e: SecurityException) {
            Log.e("AlarmScheduler", "alarm scheduling blocked by system", e)
            // Último fallback: tentar setExactAndAllowWhileIdle se ainda não foi.
            if (useAlarmClock) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerMillis,
                        pendingIntent,
                    )
                } catch (e2: SecurityException) {
                    Log.e("AlarmScheduler", "Exact alarm also blocked — not scheduled", e2)
                }
            }
        }
    }

    override fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * PendingIntent que dispara o [AlarmReceiver].
     *
     * Dois requestCodes possíveis:
     * - [ALARM_REQUEST_CODE] para o alarme principal (Regra 1: 1 por vez).
     * - [OVERSHOOT_REQUEST_CODE] para o re-alerta após o zero.
     */
    private fun buildPendingIntent(
        requestCode: Int,
        exerciseId: Long,
        exerciseName: String,
        targetReps: Int,
        isOvershoot: Boolean,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_EXERCISE_ID, exerciseId)
            putExtra(AlarmReceiver.EXTRA_EXERCISE_NAME, exerciseName)
            putExtra(AlarmReceiver.EXTRA_TARGET_REPS, targetReps)
            putExtra(AlarmReceiver.EXTRA_IS_OVERSHOOT, isOvershoot)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** PendingIntent exibido ao tocar no ícone de alarme na status bar → abre o app. */
    private fun buildShowIntent(): PendingIntent {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
