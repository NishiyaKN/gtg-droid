package com.gtg.app.domain.scheduler

import java.time.LocalDateTime

/**
 * Contrato para agendamento de alarmes do sistema.
 *
 * A interface vive no domain layer (sem dependências Android).
 * A implementação ([AlarmSchedulerImpl]) usa [AlarmManager] e vive no presentation layer
 * junto ao [AlarmReceiver] que ela referencia.
 */
interface AlarmScheduler {

    /**
     * Agenda um alarme para o [triggerAt] especificado.
     * Cancela automaticamente qualquer alarme anterior (Regra 1: apenas um ativo por vez).
     */
    fun schedule(
        triggerAt: LocalDateTime,
        exerciseId: Long,
        exerciseName: String,
        targetReps: Int,
    )

    /** Cancela o alarme pendente. */
    fun cancel()

    /**
     * Agenda um re-alerta de "overshoot" — segundo (ou Nésimo) disparo do mesmo
     * alarme após o zero, quando o usuário não fez Check. Usa um PendingIntent
     * distinto do alarme principal para não sobrescrevê-lo. Múltiplas chamadas
     * substituem o overshoot anterior (FLAG_UPDATE_CURRENT).
     */
    fun scheduleOvershoot(
        triggerAt: LocalDateTime,
        exerciseId: Long,
        exerciseName: String,
        targetReps: Int,
    )

    /** Cancela qualquer overshoot pendente. Idempotente. */
    fun cancelOvershoot()

    /** true se o app tem permissão para agendar alarmes exatos (Android 12+). */
    fun canScheduleExactAlarms(): Boolean
}
