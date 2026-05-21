package com.gtg.app.presentation.alarm

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import com.gtg.app.MainDispatcherRule
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.Exercise
import com.gtg.app.domain.model.ExerciseLog
import com.gtg.app.domain.model.ScheduleResult
import com.gtg.app.domain.repository.ActivityWindowRepository
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
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Testes do AlarmViewModel — cobrem R1 (cancelOvershoot em Check/Skip),
 * R2 (performSnooze + bounds check) e R3 (Skip sem log).
 *
 * Cenário base: alarme tocou para exerciseId=1L "Flexão", 10 reps.
 * Lista ativa: [Flexão (id=1), Barra (id=2)]. Rotação avança 1→2.
 * activeDaysOfWeek = todos os dias por default; ActivityWindow null por default.
 * Testes que precisam de bounds violation reconfiguram em-line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val exerciseRepository: ExerciseRepository = mockk()
    private val exerciseLogRepository: ExerciseLogRepository = mockk(relaxed = true)
    private val activityWindowRepository: ActivityWindowRepository = mockk()
    private val dynamicScheduler: DynamicSchedulerUseCase = mockk()
    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val sessionPrefs: SessionPreferences = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val notificationManager: NotificationManagerCompat = mockk(relaxed = true)

    private val flexao = Exercise(id = 1L, name = "Flexão", maxReps = 20, targetPercentage = 50)
    private val barra = Exercise(id = 2L, name = "Barra", maxReps = 10, targetPercentage = 50)

    @Before
    fun setUp() {
        mockkObject(AlarmSoundPlayer)
        justRun { AlarmSoundPlayer.stop() }

        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns notificationManager

        coEvery { exerciseRepository.getActiveExercises() } returns listOf(flexao, barra)
        coEvery { activityWindowRepository.getActiveWindow() } returns null
        every { sessionPrefs.baseIntervalMinutes } returns 45L
        every { sessionPrefs.activeDaysOfWeek } returns DayOfWeek.entries.toSet()
        every { sessionPrefs.overshootRepeatMinutes } returns 5
        coEvery {
            dynamicScheduler.calculateNextAlarm(any(), any(), any(), any(), any())
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
            activityWindowRepository = activityWindowRepository,
            dynamicScheduler = dynamicScheduler,
            alarmScheduler = alarmScheduler,
            sessionPrefs = sessionPrefs,
            appContext = context,
        )
    }

    // ── R1 — Check/Skip cancelam overshoot ───────────────────────────

    @Test
    fun `performCheck cancela overshoot e rotaciona exercicio`() = runTest {
        val vm = buildViewModel()

        vm.performCheck()
        advanceUntilIdle()

        verify(exactly = 1) { alarmScheduler.cancelOvershoot() }
        coVerify(exactly = 1) { exerciseLogRepository.insert(any<ExerciseLog>()) }
        verify(exactly = 1) {
            alarmScheduler.schedule(
                triggerAt = any(),
                exerciseId = 2L,
                exerciseName = "Barra",
                targetReps = barra.targetReps,
            )
        }
    }

    @Test
    fun `performCheck com exerciseId invalido nao loga mas rotaciona`() = runTest {
        // pickNextExerciseInRotation com currentId não encontrado na lista retorna
        // o primeiro elemento (recomeça o ciclo). Aqui: -1L não está em [flexao, barra]
        // → próximo = flexao (id=1L). Crucial: insert NÃO é chamado pelo guard de
        // exerciseId > 0, mas o reagendamento avança normalmente.
        val vm = buildViewModel(exerciseId = -1L)

        vm.performCheck()
        advanceUntilIdle()

        coVerify(exactly = 0) { exerciseLogRepository.insert(any<ExerciseLog>()) }
        verify(exactly = 1) { alarmScheduler.schedule(any(), eq(1L), eq("Flexão"), any()) }
    }

    @Test
    fun `performSkip cancela overshoot, rotaciona e nao registra log`() = runTest {
        val vm = buildViewModel()

        vm.performSkip()
        advanceUntilIdle()

        verify(exactly = 1) { alarmScheduler.cancelOvershoot() }
        coVerify(exactly = 0) { exerciseLogRepository.insert(any<ExerciseLog>()) }
        verify(exactly = 1) {
            alarmScheduler.schedule(
                triggerAt = any(),
                exerciseId = 2L,
                exerciseName = "Barra",
                targetReps = barra.targetReps,
            )
        }
    }

    @Test
    fun `Check com lista vazia limpa sessao e nao agenda`() = runTest {
        coEvery { exerciseRepository.getActiveExercises() } returns emptyList()
        val vm = buildViewModel()

        vm.performCheck()
        advanceUntilIdle()

        verify(exactly = 1) { sessionPrefs.clearSession() }
        verify(exactly = 0) {
            alarmScheduler.schedule(any(), any(), any(), any())
        }
    }

    @Test
    fun `Check com NoWindowConfigured limpa sessao`() = runTest {
        coEvery {
            dynamicScheduler.calculateNextAlarm(any(), any(), any(), any(), any())
        } returns ScheduleResult.NoWindowConfigured
        val vm = buildViewModel()

        vm.performCheck()
        advanceUntilIdle()

        verify(exactly = 1) { sessionPrefs.clearSession() }
        verify(exactly = 0) {
            alarmScheduler.schedule(any(), any(), any(), any())
        }
    }

    @Test
    fun `Check com ScheduledTomorrow agenda no dia seguinte`() = runTest {
        val tomorrow = LocalDateTime.now().plusDays(1).withHour(8).withMinute(0)
        coEvery {
            dynamicScheduler.calculateNextAlarm(any(), any(), any(), any(), any())
        } returns ScheduleResult.ScheduledTomorrow(tomorrow)
        val vm = buildViewModel()

        vm.performCheck()
        advanceUntilIdle()

        verify(exactly = 1) {
            alarmScheduler.schedule(eq(tomorrow), eq(2L), eq("Barra"), any())
        }
        verify(exactly = 0) { sessionPrefs.clearSession() }
    }

    // ── R2 — performSnooze ───────────────────────────────────────────

    @Test
    fun `performSnooze cancela overshoot, mantem exercicio e nao registra log`() = runTest {
        val vm = buildViewModel()

        vm.performSnooze()
        advanceUntilIdle()

        verify(exactly = 1) { alarmScheduler.cancelOvershoot() }
        verify(exactly = 1) { alarmScheduler.cancel() }
        verify(exactly = 1) {
            alarmScheduler.schedule(
                triggerAt = any(),
                exerciseId = 1L,
                exerciseName = "Flexão",
                targetReps = 10,
            )
        }
        coVerify(exactly = 0) { exerciseLogRepository.insert(any<ExerciseLog>()) }
        coVerify(exactly = 0) {
            dynamicScheduler.calculateNextAlarm(any(), any(), any(), any(), any())
        }
        verify(exactly = 1) {
            sessionPrefs.setNextAlarm(
                epochMillis = any(),
                exerciseId = 1L,
                exerciseName = "Flexão",
                targetReps = 10,
            )
        }
    }

    @Test
    fun `performSnooze agenda exatamente now+overshootRepeatMinutes quando dentro dos bounds`() = runTest {
        every { sessionPrefs.overshootRepeatMinutes } returns 7
        val captured = slot<LocalDateTime>()
        val before = LocalDateTime.now()
        val vm = buildViewModel()

        vm.performSnooze()
        advanceUntilIdle()

        verify(exactly = 1) {
            alarmScheduler.schedule(
                triggerAt = capture(captured),
                exerciseId = 1L,
                exerciseName = "Flexão",
                targetReps = 10,
            )
        }
        val after = LocalDateTime.now()
        val expectedMin = before.plusMinutes(7)
        val expectedMax = after.plusMinutes(7)
        assertTrue(
            "snooze trigger ($captured) deveria estar entre $expectedMin e $expectedMax",
            !captured.captured.isBefore(expectedMin) && !captured.captured.isAfter(expectedMax),
        )
    }

    @Test
    fun `performSnooze NAO grava lastCheckMillis`() = runTest {
        // KD do code review: gravar lastCheck no snooze corrompe a âncora consumida
        // por HomeViewModel.rescheduleFromAnchor. Snooze NÃO atualiza lastCheck.
        val vm = buildViewModel()

        vm.performSnooze()
        advanceUntilIdle()

        verify(exactly = 0) { sessionPrefs.setLastCheck(any()) }
    }

    @Test
    fun `performSnooze define actionCompleted=true ao final`() = runTest {
        val vm = buildViewModel()

        vm.performSnooze()
        advanceUntilIdle()

        assertTrue(vm.actionCompleted.value)
    }

    @Test
    fun `performSnooze faz rollover quando candidato cai em dia inativo`() = runTest {
        // Cenário: hoje é segunda, activeDays = [TUE..FRI]. Snooze (5min) cairia
        // ainda na segunda → fora de bounds. Espera-se rollover para terça (próximo
        // dia ativo) no início da janela.
        val activeDays = setOf(
            DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        // Garantir que LocalDateTime.now() não cair em dia ativo:
        val today = LocalDateTime.now().dayOfWeek
        if (today in activeDays) {
            // Se rodando o teste em dia ativo, pula — não é determinístico.
            return@runTest
        }
        every { sessionPrefs.activeDaysOfWeek } returns activeDays
        coEvery { activityWindowRepository.getActiveWindow() } returns ActivityWindow(
            id = 1L,
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(18, 0),
        )
        val captured = slot<LocalDateTime>()
        val vm = buildViewModel()

        vm.performSnooze()
        advanceUntilIdle()

        verify(exactly = 1) {
            alarmScheduler.schedule(triggerAt = capture(captured), any(), any(), any())
        }
        assertTrue(captured.captured.dayOfWeek in activeDays)
        assertEquals(LocalTime.of(8, 0), captured.captured.toLocalTime())
    }

    @Test
    fun `performSnooze faz rollover quando candidato passa do fim da janela`() = runTest {
        // Cenário: now + 5min está após window.endTime → rollover para próximo dia.
        val now = LocalDateTime.now()
        val endTime = now.plusMinutes(2).toLocalTime() // janela acaba antes do snooze
        coEvery { activityWindowRepository.getActiveWindow() } returns ActivityWindow(
            id = 1L,
            startTime = LocalTime.of(8, 0),
            endTime = endTime,
        )
        val captured = slot<LocalDateTime>()
        val vm = buildViewModel()

        vm.performSnooze()
        advanceUntilIdle()

        verify(exactly = 1) {
            alarmScheduler.schedule(triggerAt = capture(captured), any(), any(), any())
        }
        // Esperado: rollover para próximo dia, 08:00.
        assertTrue(
            "snooze (${captured.captured}) deveria ter ido para depois de $now+5min via rollover",
            captured.captured.toLocalDate().isAfter(now.toLocalDate()) &&
                captured.captured.toLocalTime() == LocalTime.of(8, 0),
        )
    }
}
