package com.gtg.app.domain.usecase

import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.model.ExerciseLog
import com.gtg.app.domain.repository.ExerciseLogRepository
import com.gtg.app.domain.repository.ExerciseRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Testes de [GetExerciseBreakdownUseCase] — agregação por exercício,
 * fallback de nome para exercício deletado e ordenação por volume.
 */
class GetExerciseBreakdownUseCaseTest {

    private val logRepository: ExerciseLogRepository = mockk()
    private val exerciseRepository: ExerciseRepository = mockk()
    private val useCase = GetExerciseBreakdownUseCase(logRepository, exerciseRepository)

    private val day: LocalDate = LocalDate.of(2026, 6, 10)
    private val noon: LocalDateTime = day.atTime(12, 0)

    private fun log(exerciseId: Long, reps: Int) = ExerciseLog(
        exerciseId = exerciseId,
        timestamp = noon,
        repsCompleted = reps,
    )

    @Test
    fun `sem logs retorna lista vazia sem consultar exercicios`() = runTest {
        coEvery { logRepository.getLogsBetween(day, day) } returns emptyList()

        assertEquals(emptyList<Any>(), useCase(day, day))
    }

    @Test
    fun `agrega sets e reps por exercicio`() = runTest {
        coEvery { logRepository.getLogsBetween(day, day) } returns listOf(
            log(exerciseId = 1L, reps = 10),
            log(exerciseId = 1L, reps = 12),
            log(exerciseId = 1L, reps = 8),
        )
        coEvery { exerciseRepository.getById(1L) } returns Exercise(
            id = 1L,
            name = "Flexão",
            maxReps = 20,
            targetPercentage = 60,
        )

        val result = useCase(day, day)

        assertEquals(1, result.size)
        assertEquals("Flexão", result[0].name)
        assertEquals(3, result[0].sets)
        assertEquals(30, result[0].totalReps)
    }

    @Test
    fun `exercicio deletado usa nome de fallback com id`() = runTest {
        coEvery { logRepository.getLogsBetween(day, day) } returns listOf(
            log(exerciseId = 42L, reps = 5),
        )
        coEvery { exerciseRepository.getById(42L) } returns null

        val result = useCase(day, day)

        assertEquals("Exercício #42", result[0].name)
        assertEquals(1, result[0].sets)
        assertEquals(5, result[0].totalReps)
    }

    @Test
    fun `ordena por totalReps decrescente`() = runTest {
        coEvery { logRepository.getLogsBetween(day, day) } returns listOf(
            log(exerciseId = 1L, reps = 5),
            log(exerciseId = 2L, reps = 30),
            log(exerciseId = 3L, reps = 12),
        )
        coEvery { exerciseRepository.getById(1L) } returns
            Exercise(id = 1L, name = "A", maxReps = 10, targetPercentage = 50)
        coEvery { exerciseRepository.getById(2L) } returns
            Exercise(id = 2L, name = "B", maxReps = 10, targetPercentage = 50)
        coEvery { exerciseRepository.getById(3L) } returns
            Exercise(id = 3L, name = "C", maxReps = 10, targetPercentage = 50)

        val result = useCase(day, day)

        assertEquals(listOf("B", "C", "A"), result.map { it.name })
    }
}
