package com.gtg.app.data.mapper

import com.gtg.app.data.local.entity.InactivityBlockEntity
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Testes dos mappers entity↔domain — o parsing de CSV de recurrenceDays e a
 * serialização de horários são os pontos onde um regression corromperia
 * blocos/janela silenciosamente (o scheduler consumiria dados errados sem
 * lançar exceção).
 */
class EntityMappersTest {

    private fun domainBlock(
        recurrence: Recurrence,
        specificDate: LocalDate? = null,
        recurrenceDays: Set<DayOfWeek> = emptySet(),
        dayOfMonth: Int? = null,
    ) = InactivityBlock(
        id = 7L,
        title = "Bloco",
        startTime = LocalTime.of(9, 30),
        endTime = LocalTime.of(10, 45),
        specificDate = specificDate,
        recurrence = recurrence,
        recurrenceDays = recurrenceDays,
        dayOfMonth = dayOfMonth,
    )

    @Test
    fun `round-trip NONE preserva specificDate`() {
        val original = domainBlock(Recurrence.NONE, specificDate = LocalDate.of(2026, 6, 12))

        val roundTripped = original.toEntity().toDomain()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `round-trip DAILY preserva horarios`() {
        val original = domainBlock(Recurrence.DAILY)

        val roundTripped = original.toEntity().toDomain()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `round-trip WEEKLY preserva recurrenceDays`() {
        val original = domainBlock(
            Recurrence.WEEKLY,
            recurrenceDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY),
        )

        val roundTripped = original.toEntity().toDomain()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `round-trip MONTHLY preserva dayOfMonth`() {
        val original = domainBlock(Recurrence.MONTHLY, dayOfMonth = 15)

        val roundTripped = original.toEntity().toDomain()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `recurrenceDays null produz emptySet`() {
        val entity = InactivityBlockEntity(
            startHour = 9,
            startMinute = 0,
            endHour = 10,
            endMinute = 0,
            recurrence = Recurrence.WEEKLY.name,
            recurrenceDays = null,
        )

        assertEquals(emptySet<DayOfWeek>(), entity.toDomain().recurrenceDays)
    }

    @Test
    fun `recurrenceDays com virgula final nao lanca`() {
        val entity = InactivityBlockEntity(
            startHour = 9,
            startMinute = 0,
            endHour = 10,
            endMinute = 0,
            recurrence = Recurrence.WEEKLY.name,
            recurrenceDays = "1,3,",
        )

        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            entity.toDomain().recurrenceDays,
        )
    }

    @Test
    fun `WEEKLY com recurrenceDays vazio serializa como null`() {
        val entity = domainBlock(Recurrence.WEEKLY, recurrenceDays = emptySet()).toEntity()

        assertEquals(null, entity.recurrenceDays)
    }

    @Test
    fun `ActivityWindow round-trip preserva hora e minuto`() {
        val original = ActivityWindow(
            id = 1L,
            startTime = LocalTime.of(8, 5),
            endTime = LocalTime.of(22, 50),
            isActive = true,
        )

        val roundTripped = original.toEntity().toDomain()

        assertEquals(original, roundTripped)
    }
}
