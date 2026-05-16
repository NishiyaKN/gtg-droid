package com.gtg.app.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class InactivityBlock(
    val id: Long = 0,
    val title: String = "",
    val startTime: LocalTime,
    val endTime: LocalTime,
    val specificDate: LocalDate? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    val recurrenceDays: Set<DayOfWeek> = emptySet(),
    val dayOfMonth: Int? = null,
) {
    companion object {
        /** Início canônico de um bloco "dia inteiro". */
        val FULL_DAY_START: LocalTime = LocalTime.of(0, 0)

        /**
         * Fim canônico de um bloco "dia inteiro" (23:59).
         * Não usamos LocalTime.MAX para preservar legibilidade quando
         * exibido como "00:00 → 23:59" na UI.
         */
        val FULL_DAY_END: LocalTime = LocalTime.of(23, 59)
    }

    /**
     * Determina se este bloco está ativo para uma data específica.
     *
     * - NONE: só ativo na [specificDate] exata.
     * - DAILY: ativo todos os dias.
     * - WEEKLY: ativo nos dias da semana definidos em [recurrenceDays].
     * - MONTHLY: ativo no dia do mês definido em [dayOfMonth].
     */
    fun isActiveOn(date: LocalDate): Boolean = when (recurrence) {
        Recurrence.NONE -> specificDate == date
        Recurrence.DAILY -> true
        Recurrence.WEEKLY -> date.dayOfWeek in recurrenceDays
        Recurrence.MONTHLY -> dayOfMonth == date.dayOfMonth
    }

    /**
     * True se o bloco cobre o dia inteiro (00:00 → 23:59) — usado pela vista
     * calendário para distinguir marcações de dia inteiro de blocos parciais.
     * Independe da recorrência: um bloco DAILY full-day também conta como tal.
     */
    fun isFullDay(): Boolean =
        startTime == FULL_DAY_START && endTime == FULL_DAY_END
}
