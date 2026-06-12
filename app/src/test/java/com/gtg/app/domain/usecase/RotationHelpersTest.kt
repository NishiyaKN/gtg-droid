package com.gtg.app.domain.usecase

import android.util.Log
import com.gtg.app.data.local.IntervalMode
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.scheduler.AlarmScheduler
import com.gtg.app.presentation.alarm.AlarmReceiver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Testes de [rescheduleForNextDay] (fix 2026-06-11).
 *
 * Primeiro arquivo de testes do RotationHelpers — criado junto com a
 * delegação ao resolver de blocos. Foca no contrato de side-effects do
 * helper e nos caminhos de degradação (falha/timeout da resolução,
 * permissão de exact alarm revogada).
 *
 * O resolver é mockado: a corretude da resolução em si (mescla de
 * clusters, lookahead, STRICT bare) é coberta em
 * [DynamicSchedulerUseCaseTest].
 */
class RotationHelpersTest {

    private val alarmScheduler: AlarmScheduler = mockk(relaxed = true)
    private val sessionPrefs: SessionPreferences = mockk(relaxed = true)
    private val dynamicScheduler: DynamicSchedulerUseCase = mockk()

    private val window = ActivityWindow(
        id = 1L,
        startTime = LocalTime.of(9, 30),
        endTime = LocalTime.of(18, 0),
        isActive = true,
    )

    private val allDays: Set<DayOfWeek> = DayOfWeek.entries.toSet()

    @Before
    fun setUp() {
        // Log.w não é mockado pelo harness JVM (sem returnDefaultValues no
        // gradle) — qualquer caminho que loga estoura sem este stub. Mesmo
        // pattern do mockkStatic(NotificationManagerCompat) no AlarmViewModelTest.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0

        every { alarmScheduler.canScheduleExactAlarms() } returns true
        every { sessionPrefs.intervalMode } returns IntervalMode.DYNAMIC
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private suspend fun invoke() = rescheduleForNextDay(
        alarmScheduler = alarmScheduler,
        sessionPrefs = sessionPrefs,
        window = window,
        activeDays = allDays,
        pendingExerciseId = 1L,
        pendingExerciseName = "Flexão",
        pendingTargetReps = 10,
        dynamicScheduler = dynamicScheduler,
    )

    @Test
    fun `agenda horario resolvido e preserva contrato de side-effects`() = runTest {
        // Resolver emula bloco cobrindo o início da janela do dia alvo →
        // 09:45 em vez do início bare 09:30.
        val resolverStartDate = slot<LocalDate>()
        coEvery {
            dynamicScheduler.resolveFirstAlarmStartingAt(
                startDate = capture(resolverStartDate),
                activeDaysOfWeek = any(),
                intervalMode = any(),
                prefetchedWindow = any(),
            )
        } answers { resolverStartDate.captured.atTime(9, 45) }
        val scheduled = slot<LocalDateTime>()

        invoke()

        verify(exactly = 1) {
            alarmScheduler.schedule(
                triggerAt = capture(scheduled),
                exerciseId = 1L,
                exerciseName = "Flexão",
                targetReps = 10,
            )
        }
        assertEquals(resolverStartDate.captured.atTime(9, 45), scheduled.captured)
        // Ordem schedule → persist (AlarmSchedulerImpl engole SecurityException;
        // persistir antes deixaria prefs apontando para alarme inexistente).
        verifyOrder {
            alarmScheduler.schedule(any(), any(), any(), any())
            sessionPrefs.setNextAlarm(any(), any(), any(), any())
        }
        verify(exactly = 1) { alarmScheduler.cancel() }
        verify(exactly = 1) { alarmScheduler.cancelOvershoot() }
        // Nova cadeia amanhã — anchor zerado.
        verify(exactly = 1) { sessionPrefs.setFirstAlarmInChain(0L) }
        // Âncora de cadência é exclusiva de Checks reais do usuário.
        verify(exactly = 0) { sessionPrefs.setLastCheck(any()) }
    }

    // Falha de fetch não tem teste aqui: o fail-open de falha vive DENTRO de
    // resolveFirstAlarmStartingAt (contrato no-throw, coberto em
    // DynamicSchedulerUseCaseTest); este helper só trata o estouro do budget.

    @Test
    fun `cai para inicio bare quando a resolucao estoura o budget`() = runTest {
        // runTest usa clock virtual: o delay gigante dispara o
        // withTimeoutOrNull interno do helper sem custo real de tempo.
        coEvery {
            dynamicScheduler.resolveFirstAlarmStartingAt(any(), any(), any(), any())
        } coAnswers {
            delay(60_000)
            LocalDate.now().plusDays(1).atTime(9, 45)
        }
        val scheduled = slot<LocalDateTime>()

        invoke()

        verify(exactly = 1) {
            alarmScheduler.schedule(triggerAt = capture(scheduled), any(), any(), any())
        }
        assertEquals(LocalTime.of(9, 30), scheduled.captured.toLocalTime())
    }

    @Test
    fun `aborta limpo quando exact alarm esta revogado`() = runTest {
        every { alarmScheduler.canScheduleExactAlarms() } returns false

        invoke()

        // Encerramento limpo da cadeia, sem reagendar e sem tocar a resolução.
        verify(exactly = 1) { alarmScheduler.cancelOvershoot() }
        verify(exactly = 1) { sessionPrefs.setFirstAlarmInChain(0L) }
        verify(exactly = 0) { alarmScheduler.schedule(any(), any(), any(), any()) }
        verify(exactly = 0) { sessionPrefs.setNextAlarm(any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            dynamicScheduler.resolveFirstAlarmStartingAt(any(), any(), any(), any())
        }
    }

    @Test
    fun `repassa o intervalMode do prefs ao resolver`() = runTest {
        // STRICT chega ao resolver, que devolve bare sem consultar blocos —
        // garantia coberta no DynamicSchedulerUseCaseTest; aqui só o repasse.
        every { sessionPrefs.intervalMode } returns IntervalMode.STRICT
        coEvery {
            dynamicScheduler.resolveFirstAlarmStartingAt(any(), any(), any(), any())
        } answers { firstArg<LocalDate>().atTime(window.startTime) }

        invoke()

        coVerify(exactly = 1) {
            dynamicScheduler.resolveFirstAlarmStartingAt(
                startDate = any(),
                activeDaysOfWeek = any(),
                intervalMode = IntervalMode.STRICT,
                prefetchedWindow = window,
            )
        }
    }

    // ── suppressPrimaryInsideBlock (guard fire-time, U3) ────────────

    @Test
    fun `suppress primary rearma e preserva a matriz de side-effects`() = runTest {
        val rearmAt = LocalDate.now().atTime(9, 45)

        suppressPrimaryInsideBlock(
            alarmScheduler = alarmScheduler,
            sessionPrefs = sessionPrefs,
            rearmAt = rearmAt,
            pendingExerciseId = 1L,
            pendingExerciseName = "Flexão",
            pendingTargetReps = 10,
        )

        verify(exactly = 1) {
            alarmScheduler.schedule(
                triggerAt = rearmAt,
                exerciseId = 1L,
                exerciseName = "Flexão",
                targetReps = 10,
            )
        }
        // Sequência completa pinada: cancela ambos os alarmes ANTES de
        // rearmar, e schedule ANTES de persistir (AlarmSchedulerImpl engole
        // SecurityException — persistir primeiro deixaria prefs apontando
        // para alarme inexistente).
        verifyOrder {
            alarmScheduler.cancel()
            alarmScheduler.cancelOvershoot()
            alarmScheduler.schedule(any(), any(), any(), any())
            sessionPrefs.setNextAlarm(any(), any(), any(), any())
        }
        // Matriz: postponement same-day NÃO toca âncora de cadência nem T0
        // da cadeia — diferença deliberada vs rescheduleForNextDay.
        verify(exactly = 0) { sessionPrefs.setLastCheck(any()) }
        verify(exactly = 0) { sessionPrefs.setFirstAlarmInChain(any()) }
    }

    @Test
    fun `suppress primary aborta limpo quando exact alarm foi revogado na janela TOCTOU`() = runTest {
        // Revogação entre a decisão (que checou) e a aplicação: sem rearme
        // possível, NADA é persistido — senão prefs apontariam para alarme
        // fantasma (schedule engole SecurityException).
        every { alarmScheduler.canScheduleExactAlarms() } returns false

        suppressPrimaryInsideBlock(
            alarmScheduler = alarmScheduler,
            sessionPrefs = sessionPrefs,
            rearmAt = LocalDate.now().atTime(9, 45),
            pendingExerciseId = 1L,
            pendingExerciseName = "Flexão",
            pendingTargetReps = 10,
        )

        verify(exactly = 1) { alarmScheduler.cancelOvershoot() }
        verify(exactly = 0) { alarmScheduler.schedule(any(), any(), any(), any()) }
        verify(exactly = 0) { sessionPrefs.setNextAlarm(any(), any(), any(), any()) }
        verify(exactly = 0) { sessionPrefs.setLastCheck(any()) }
        verify(exactly = 0) { sessionPrefs.setFirstAlarmInChain(any()) }
    }

    @Test
    fun `sub-budgets do receiver compoem dentro do budget externo`() {
        // Pior caso do dispatch: leitura da window + guard de blocos (2s) +
        // resolução do rollover (3s) + cauda de agendamento, tudo sob os 9s
        // do goAsync. A folga (~4s) absorve a window read e a cauda — se um
        // dos sub-budgets crescer, este teste força a conversa.
        assertTrue(
            "BLOCK_GUARD (${AlarmReceiver.BLOCK_GUARD_BUDGET_MILLIS}ms) + " +
                "FIRST_ALARM_RESOLUTION (${FIRST_ALARM_RESOLUTION_BUDGET_MILLIS}ms) devem " +
                "caber com folga nos ${AlarmReceiver.SUSPEND_BUDGET_MILLIS}ms externos",
            AlarmReceiver.BLOCK_GUARD_BUDGET_MILLIS + FIRST_ALARM_RESOLUTION_BUDGET_MILLIS <=
                AlarmReceiver.SUSPEND_BUDGET_MILLIS - 4_000L,
        )
    }
}
