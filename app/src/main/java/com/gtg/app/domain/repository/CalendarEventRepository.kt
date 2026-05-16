package com.gtg.app.domain.repository

import com.gtg.app.domain.model.InactivityBlock
import java.time.LocalDate

/**
 * Acessa eventos do Calendar Provider do device (Google Calendar, Outlook, etc.
 * sincronizados localmente). Não persiste nada — o ContentResolver já é o cache.
 */
interface CalendarEventRepository {

    /** Calendários visíveis e sincronizados no device, em qualquer conta. */
    suspend fun listAvailableCalendars(): List<CalendarInfo>

    /**
     * Eventos do dia [date] virtualizados como [InactivityBlock] (NONE +
     * specificDate). Aplica os filtros configurados pelo usuário (calendários
     * selecionados, blacklist de eventos sobrescritos, all-day, busy, etc).
     *
     * Retorna lista vazia se a integração está desabilitada ou se a permissão
     * READ_CALENDAR não foi concedida — chamadores não precisam tratar erro.
     */
    suspend fun getBlocksOn(date: LocalDate): List<InactivityBlock>

    /**
     * Versão em batch para a UI da Agenda: 1 query única no provider em vez
     * de N queries por dia. Retorna mapa data → blocos.
     */
    suspend fun getBlocksInRange(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
    ): Map<LocalDate, List<InactivityBlock>>
}

/** Calendário sincronizado no device. */
data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val colorArgb: Int,
)
