package com.gtg.app.presentation.alarm

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import com.gtg.app.MainDispatcherRule
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.model.ExerciseLog
import com.gtg.app.domain.model.ScheduleResult
import com.gtg.app.domain.repository.ExerciseLogRepository
import com.gtg.app.domain.repository.ExerciseRepository
import com.gtg.app.domain.scheduler.AlarmScheduler
import com.gtg.app.domain.usecase.DynamicSchedulerUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

/**
 * Testes do AlarmViewModel — cobrem R1 (cancelOvershoot em Check/Skip),
 * R2 (performSnooze) e R3 (Skip sem log).
 *
 * Cenário base: alarme tocou para exerciseId=1L "Flexão", 10 reps.
 * Lista ativa: [Flexão (id=1), Barra (id=2)]. Rotação avança 1→2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val exerciseRepository: ExerciseRepository = mockk()
    private val exerciseLogRepository: ExerciseLogRepository = mockk(relaxed = true)
    private val dynamicScheduler: DynamicSchedulerUseCase = mockk()
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val sessionPrefs: SessionPreferences = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val notificationManager: NotificationManagerCompat = mockk(relaxed = true)

    private val flexao = Exercise(id = 1L, name = "Flexão", maxReps = 20, targetPercentage = 50)
    private val barra = Exercise(id = 2L, name = "Barra", maxReps = 10, targetPercentage = 50)

    @Before
    fun setUp() {
        // AlarmSoundPlayer.stop() é object — mockkObject permite verify
        mockkObject(AlarmSoundPlayer)
        justRun { AlarmSoundPlayer.stop() }

        // NotificationManagerCompat.from(context) é static
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns notificationManager

        coEvery { exerciseRepository.getActiveExercises() } returns listOf(flexao, barra)
        every { sessionPrefs.baseIntervalMinutes } returns 45L
        every { sessionPrefs.activeDaysOfWeek } returns java.time.DayOfWeek.entries.toSet()
        every { sessionPrefs.overshootRepeatMinutes } returns 5
        coEvery {
            dynamicScheduler.calculateNextAlarm(any(), any(), any())
        } returns ScheduleResult.Scheduled(LocalDateTime.now().plusMinutes(45))
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildViewModel(
        exerciseId: Long = 1L,
        exerciseName: String = "Flexão",
        targetReps: Int = 10,
    ): AlarmViewModel {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                AlarmReceiver.EXTRA_EXERCISE_ID to exerciseId,
                AlarmReceiver.EXTRA_EXERCISE_NAME to exerciseName,
                AlarmReceiver.EXTRA_TARGET_REPS to targetReps,
            ),
        )
        return AlarmViewModel(
            savedStateHandle = savedStateHandle,
            exerciseRepository = exerciseRepository,
            exerciseLogRepository = exerciseLogRepository,
            dynamicScheduler = dynamicScheduler,
            alarmScheduler = alarmScheduler,
            sessionPrefs = sessionPrefs,
            appContext = context,
        )
    }

    // ── Test 1 — R1 — performCheck cancela overshoot ─────────────────

    @Test
    fun `performCheck cancela overshoot e rotaciona exercicio`() = runTest {
        val vm = buildViewModel()

        vm.performCheck()
        advanceUntilIdle()

        verify(exactly = 1) { alarmScheduler.cancelOvershoot() }
        coVerify(exactly = 1) { exerciseLogRepository.insert(any<ExerciseLog>()) }
        verify {
            alarmScheduler.schedule(
                triggerAt = any(),
                exerciseId = 2L,
                exerciseName = "Barra",
                targetReps = barra.targetReps,
            )
        }
    }

    // ── Test 2 — R3 — performSkip cancela overshoot, sem log ─────────

    @Test
    fun `performSkip cancela overshoot, rotaciona e nao registra log`() = runTest {
        val vm = buildViewModel()

        vm.performSkip()
        advanceUntilIdle()

        verify(exactly = 1) { alarmScheduler.cancelOvershoot() }
        coVerify(exactly = 0) { exerciseLogRepository.insert(any<ExerciseLog>()) }
        verify {
            alarmScheduler.schedule(
                triggerAt = any(),
                exerciseId = 2L,
                exerciseName = "Barra",
                targetReps = barra.targetReps,
            )
        }
    }

    // Tests 3-5 (performSnooze) virão em U3.
}
