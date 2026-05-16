package com.gtg.app.domain.model

import java.time.LocalDateTime

/**
 * Resultado do cálculo de agendamento do [DynamicSchedulerUseCase].
 */
sealed interface ScheduleResult {

    /** Alarme agendado para [dateTime] no mesmo dia. */
    data class Scheduled(val dateTime: LocalDateTime) : ScheduleResult

    /** Expediente de hoje encerrado; alarme movido para o início do próximo dia útil. */
    data class ScheduledTomorrow(val dateTime: LocalDateTime) : ScheduleResult

    /** Nenhuma ActivityWindow configurada — o usuário precisa definir uma. */
    data object NoWindowConfigured : ScheduleResult
}
