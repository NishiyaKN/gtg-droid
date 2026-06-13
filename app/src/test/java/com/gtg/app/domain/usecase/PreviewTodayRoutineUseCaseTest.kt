package com.gtg.app.domain.usecase

import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.model.ScheduleResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Testes de [PreviewTodayRoutineUseCase] — a projeção em cascata da rotina
 * do dia. O [DynamicSchedulerUseCase] é mockado: a corretude do engine é
 * coberta em [DynamicSchedulerUseCaseTest]; aqui interessa o protocolo da
 * simulação (paradas, guard de data, rotação, cap de iterações).
 */
class PreviewTodayRoutineUseCaseTest {

    private val dynamicScheduler: DynamicSchedulerUseCase = mockk()
    private val useCase = PreviewTodayRoutineUseCase(dynamicScheduler)

    private val wednesday: LocalDate = LocalDate.of(2026, 5, 20)
    private val nineAm: LocalDateTime = wednesday.atTime(9, 0)

    private val flexao = Exercise(id = 1L, name = "Flexão", maxReps = 20, targetPercentage = 60)
    private val barra = Exercise(id = 2L, name = "Barra", maxReps = 10, targetPercentage = 60)

    private val deps = DynamicSchedulerUseCase.PrefetchedDependencies(
        window = ActivityWindow(
            id = 1L,
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(18, 0),
            isActive = true,
        ),
        manualBlocks = emptyList(),
        calendarBlocks = emptyList(),
    )

    private suspend fun invoke(maxIterations: Int = 12) = useCase(
        firstAlarmAt = nineAm,
        activeExercises = listOf(flexao, barra),
        firstExerciseIndex = 0,
        baseIntervalMinutes = 45L,
        isFirstAlarmScheduled = true,
        maxIterations = maxIterations,
    )

    @Test
    fun `sem exercicios ativos retorna lista vazia`() = runTest {
        val result = useCase(
            firstAlarmAt = nineAm,
            activeExercises = emptyList(),
            firstExerciseIndex = 0,
            baseIntervalMinutes = 45L,
            isFirstAlarmScheduled = true,
        )

        assertEquals(emptyList<PlannedSet>(), result)
    }

    @Test
    fun `preFetch null retorna apenas o primeiro item`() = runTest {
        coEvery { dynamicScheduler.preFetchForDate(wednesday) } returns null

        val result = invoke()

        assertEquals(1, result.size)
        assertEquals("Flexão", result[0].exerciseName)
        assertTrue(result[0].isScheduled)
    }

    @Test
    fun `para em ScheduledTomorrow e nao projeta alem`() = runTest {
        coEvery { dynamicScheduler.preFetchForDate(wednesday) } returns deps
        every {
            dynamicScheduler.evaluateWithDependencies(any(), any(), any(), any(), any(), any())
        } returns ScheduleResult.ScheduledTomorrow(wednesday.plusDays(1).atTime(8, 0))

        val result = invoke()

        assertEquals(1, result.size)
    }

    @Test
    fun `guard de data descarta Scheduled que cruzou o dia`() = runTest {
        // Mesmo um Scheduled (não-Tomorrow) cujo dateTime caia em outra data
        // deve encerrar a projeção — a preview é estritamente do dia de
        // referência.
        coEvery { dynamicScheduler.preFetchForDate(wednesday) } returns deps
        every {
            dynamicScheduler.evaluateWithDependencies(any(), any(), any(), any(), any(), any())
        } returns ScheduleResult.Scheduled(wednesday.plusDays(1).atTime(8, 0))

        val result = invoke()

        assertEquals(1, result.size)
    }

    @Test
    fun `cascata rotaciona exercicios e marca projecoes como nao agendadas`() = runTest {
        coEvery { dynamicScheduler.preFetchForDate(wednesday) } returns deps
        every {
            dynamicScheduler.evaluateWithDependencies(any(), any(), any(), any(), any(), any())
        } answers {
            val checkTime = firstArg<LocalDateTime>()
            val next = checkTime.plusMinutes(45)
            if (next.toLocalTime().isAfter(LocalTime.of(11, 0))) {
                ScheduleResult.ScheduledTomorrow(wednesday.plusDays(1).atTime(8, 0))
            } else {
                ScheduleResult.Scheduled(next)
            }
        }

        val result = invoke()

        // 09:00 (real) → 09:45 → 10:30 → para (11:15 > 11:00).
        assertEquals(3, result.size)
        assertEquals(listOf("Flexão", "Barra", "Flexão"), result.map { it.exerciseName })
        assertTrue(result[0].isScheduled)
        assertFalse(result[1].isScheduled)
        assertFalse(result[2].isScheduled)
        assertEquals(wednesday.atTime(10, 30), result[2].time)
    }

    @Test
    fun `maxIterations limita a projecao em configuracao patologica`() = runTest {
        coEvery { dynamicScheduler.preFetchForDate(wednesday) } returns deps
        every {
            dynamicScheduler.evaluateWithDependencies(any(), any(), any(), any(), any(), any())
        } answers {
            ScheduleResult.Scheduled(firstArg<LocalDateTime>().plusMinutes(1))
        }

        val result = invoke(maxIterations = 5)

        assertEquals(5, result.size)
    }
}
