package com.gtg.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Testes de [InactivityBlock.isActiveOn] — a lógica de recorrência é o
 * predicado central que decide se um bloco suprime alarmes em uma data;
 * cada variante de [Recurrence] tem caminho próprio no `when`.
 */
class InactivityBlockTest {

    // Quarta-feira, 2026-06-10 (dayOfMonth = 10)
    private val wednesday: LocalDate = LocalDate.of(2026, 6, 10)
    private val thursday: LocalDate = wednesday.plusDays(1)

    private fun block(
        recurrence: Recurrence,
        specificDate: LocalDate? = null,
        recurrenceDays: Set<DayOfWeek> = emptySet(),
        dayOfMonth: Int? = null,
    ) = InactivityBlock(
        startTime = LocalTime.of(10, 0),
        endTime = LocalTime.of(11, 0),
        specificDate = specificDate,
        recurrence = recurrence,
        recurrenceDays = recurrenceDays,
        dayOfMonth = dayOfMonth,
    )

    @Test
    fun `NONE ativo apenas na specificDate exata`() {
        val b = block(Recurrence.NONE, specificDate = wednesday)

        assertTrue(b.isActiveOn(wednesday))
        assertFalse(b.isActiveOn(thursday))
    }

    @Test
    fun `NONE sem specificDate nunca esta ativo`() {
        val b = block(Recurrence.NONE, specificDate = null)

        assertFalse(b.isActiveOn(wednesday))
    }

    @Test
    fun `DAILY ativo em qualquer data`() {
        val b = block(Recurrence.DAILY)

        assertTrue(b.isActiveOn(wednesday))
        assertTrue(b.isActiveOn(thursday))
        assertTrue(b.isActiveOn(wednesday.plusYears(1)))
    }

    @Test
    fun `WEEKLY ativo somente nos dias de recurrenceDays`() {
        val b = block(
            Recurrence.WEEKLY,
            recurrenceDays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        )

        assertTrue(b.isActiveOn(wednesday))
        assertFalse(b.isActiveOn(thursday))
        assertTrue(b.isActiveOn(wednesday.plusDays(2))) // sexta
    }

    @Test
    fun `WEEKLY com recurrenceDays vazio nunca esta ativo`() {
        val b = block(Recurrence.WEEKLY, recurrenceDays = emptySet())

        assertFalse(b.isActiveOn(wednesday))
        assertFalse(b.isActiveOn(thursday))
    }

    @Test
    fun `MONTHLY ativo somente no dayOfMonth configurado`() {
        val b = block(Recurrence.MONTHLY, dayOfMonth = 10)

        assertTrue(b.isActiveOn(wednesday)) // dia 10
        assertFalse(b.isActiveOn(thursday)) // dia 11
        assertTrue(b.isActiveOn(LocalDate.of(2026, 7, 10))) // dia 10 do mês seguinte
    }

    @Test
    fun `MONTHLY sem dayOfMonth nunca esta ativo`() {
        val b = block(Recurrence.MONTHLY, dayOfMonth = null)

        assertFalse(b.isActiveOn(wednesday))
    }

    @Test
    fun `isFullDay reconhece bloco 0000 a 2359 independente da recorrencia`() {
        val fullDay = InactivityBlock(
            startTime = InactivityBlock.FULL_DAY_START,
            endTime = InactivityBlock.FULL_DAY_END,
            recurrence = Recurrence.DAILY,
        )
        val partial = block(Recurrence.DAILY)

        assertTrue(fullDay.isFullDay())
        assertFalse(partial.isFullDay())
    }
}
