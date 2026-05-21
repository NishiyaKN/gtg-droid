package com.gtg.app.domain.usecase

import com.gtg.app.data.local.IntervalMode
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.Recurrence
import com.gtg.app.domain.model.ScheduleResult
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Testes do branching de [IntervalMode] em [DynamicSchedulerUseCase].
 *
 * Foco em `evaluateWithDependencies` — a engine pura que recebe tudo por
 * parâmetro, evitando a necessidade de mockar Room/repos.
 *
 * Cenário base:
 * - ActivityWindow 08:00–18:00, todos os dias úteis (seg-sex)
 * - baseInterval = 45 min
 * - Hoje = uma quarta-feira arbitrária para evitar problemas de borda
 */
class DynamicSchedulerUseCaseTest {

    private val useCase = DynamicSchedulerUseCase(
        activityWindowRepository = mockk(),
        inactivityBlockRepository = mockk(),
        calendarEventRepository = mockk(),
    )

    private val window = ActivityWindow(
        id = 1L,
        startTime = LocalTime.of(8, 0),
        endTime = LocalTime.of(18, 0),
        isActive = true,
    )

    private val weekdays: Set<DayOfWeek> = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    /** Quarta-feira fixa em 2026-05-20 → coincide com a data do brainstorm. */
    private val wednesday: LocalDate = LocalDate.of(2026, 5, 20)

    private fun deps(blocks: List<InactivityBlock> = emptyList()) =
        DynamicSchedulerUseCase.PrefetchedDependencies(
            window = window,
            manualBlocks = blocks,
            calendarBlocks = emptyList(),
        )

    private fun lunchBlock(start: LocalTime, end: LocalTime) =
        InactivityBlock(
            id = 1L,
            startTime = start,
            endTime = end,
            recurrence = Recurrence.NONE,
            specificDate = wednesday,
        )

    // ── AE7: STRICT toca dentro de InactivityBlock ──────────────────

    @Test
    fun `STRICT com bloco colidindo retorna candidato exato dentro do bloco`() {
        val checkTime = wednesday.atTime(10, 0)
        val blocks = listOf(lunchBlock(LocalTime.of(10, 30), LocalTime.of(11, 30)))

        val result = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.STRICT,
            deps = deps(blocks),
        )

        val expected = wednesday.atTime(10, 45)
        assertEquals(ScheduleResult.Scheduled(expected), result)
    }

    @Test
    fun `DYNAMIC com bloco colidindo desvia para fora do bloco`() {
        val checkTime = wednesday.atTime(10, 0)
        // candidate = 10:45 cai dentro de 10:30-11:30 (15min após o início).
        // INACTIVITY_PROXIMITY_MINUTES=15 → "mid-block" → adia para 11:35.
        val blocks = listOf(lunchBlock(LocalTime.of(10, 30), LocalTime.of(11, 30)))

        val result = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.DYNAMIC,
            deps = deps(blocks),
        )

        val ten45 = wednesday.atTime(10, 45)
        assertTrue(
            "DYNAMIC deveria ajustar para fora de 10:45 (estava em $result)",
            result != ScheduleResult.Scheduled(ten45),
        )
    }

    // ── AE8: STRICT respeita fim de janela ──────────────────────────

    @Test
    fun `STRICT com candidato apos windowEnd vai para proximo dia ativo`() {
        // checkTime 17:30 + 45 = 18:15 → após windowEnd (18:00).
        // Rule 5 mantida em STRICT → próximo dia útil às 08:00.
        val checkTime = wednesday.atTime(17, 30)

        val result = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.STRICT,
            deps = deps(),
        )

        val expectedTomorrow = wednesday.plusDays(1).atTime(8, 0)
        assertEquals(ScheduleResult.ScheduledTomorrow(expectedTomorrow), result)
    }

    @Test
    fun `STRICT pula sabado domingo para segunda-feira`() {
        val friday = LocalDate.of(2026, 5, 22)
        val checkTime = friday.atTime(17, 30)

        val result = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.STRICT,
            deps = deps(),
        )

        // 18:15 sexta → fora da janela → sat (off) → sun (off) → mon 08:00.
        val expectedMonday = LocalDate.of(2026, 5, 25).atTime(8, 0)
        assertEquals(ScheduleResult.ScheduledTomorrow(expectedMonday), result)
    }

    // ── STRICT preserva cadência exata sem clamp ────────────────────

    @Test
    fun `STRICT com Check muito atrasado nao clampa para now+20min`() {
        // lastCheck=10:00 + 45 = 10:45.
        // now=12:00 → DYNAMIC clamparia para 12:20 (rule 3). STRICT mantém 10:45
        // (no passado, AlarmManager dispara imediatamente).
        val checkTime = wednesday.atTime(10, 0)
        val now = wednesday.atTime(12, 0)

        val result = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = now,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.STRICT,
            deps = deps(),
        )

        val expected = wednesday.atTime(10, 45)
        assertEquals(ScheduleResult.Scheduled(expected), result)
    }

    @Test
    fun `DYNAMIC com Check atrasado clampa para now plus rest minimo`() {
        val checkTime = wednesday.atTime(10, 0)
        val now = wednesday.atTime(12, 0)

        val result = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = now,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.DYNAMIC,
            deps = deps(),
        )

        val expected = wednesday.atTime(12, 20)
        assertEquals(ScheduleResult.Scheduled(expected), result)
    }

    // ── Default DYNAMIC preserva comportamento legado ───────────────

    @Test
    fun `default intervalMode e DYNAMIC e nao quebra chamadas existentes`() {
        val checkTime = wednesday.atTime(10, 0)

        val resultDefault = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            deps = deps(),
            // intervalMode omitido — usa default
        )

        val resultDynamic = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.DYNAMIC,
            deps = deps(),
        )

        assertEquals(resultDynamic, resultDefault)
    }
}
