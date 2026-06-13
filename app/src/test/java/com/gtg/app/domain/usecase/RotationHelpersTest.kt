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

        val rearmed = suppressPrimaryInsideBlock(
            alarmScheduler = alarmScheduler,
            sessionPrefs = sessionPrefs,
            rearmAt = rearmAt,
            pendingExerciseId = 1L,
            pendingExerciseName = "Flexão",
            pendingTargetReps = 10,
        )

        assertTrue("caminho normal deve devolver true (rearme aplicado)", rearmed)
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
    fun `suppress primary devolve false sem side-effects quando exact alarm foi revogado`() = runTest {
        // Revogação entre a decisão (que checou) e a aplicação: sem rearme
        // possível, NADA é executado e o retorno false manda o caller deixar
        // o disparo TOCAR — suprimir sem rearme seria alarme perdido (política
        // da camada de decisão); persistir apontaria para alarme fantasma.
        every { alarmScheduler.canScheduleExactAlarms() } returns false

        val rearmed = suppressPrimaryInsideBlock(
            alarmScheduler = alarmScheduler,
            sessionPrefs = sessionPrefs,
            rearmAt = LocalDate.now().atTime(9, 45),
            pendingExerciseId = 1L,
            pendingExerciseName = "Flexão",
            pendingTargetReps = 10,
        )

        assertTrue("abort TOCTOU deve devolver false (caller cai para Ring)", !rearmed)
        verify(exactly = 0) { alarmScheduler.cancelOvershoot() }
        verify(exactly = 0) { alarmScheduler.schedule(any(), any(), any(), any()) }
        verify(exactly = 0) { sessionPrefs.setNextAlarm(any(), any(), any(), any()) }
        verify(exactly = 0) { sessionPrefs.setLastCheck(any()) }
        verify(exactly = 0) { sessionPrefs.setFirstAlarmInChain(any()) }
    }

    @Test
    fun `sub-budgets do receiver compoem dentro do budget externo`() {
        // Pior caso do dispatch: leitura da window + guard de blocos (2s) +
        // resolução do rollover (3s) + cauda de agendamento, tudo sob os 9s
        // do goAsync. DISPATCH_TAIL_SLACK nomeia o que os sub-budgets NÃO
        // cobrem (window read, notify, cauda) — se um sub-budget crescer ou
        // a folga for reduzida, este teste força a conversa.
        assertTrue(
            "BLOCK_GUARD (${AlarmReceiver.BLOCK_GUARD_BUDGET_MILLIS}ms) + " +
                "FIRST_ALARM_RESOLUTION (${FIRST_ALARM_RESOLUTION_BUDGET_MILLIS}ms) devem " +
                "deixar DISPATCH_TAIL_SLACK (${AlarmReceiver.DISPATCH_TAIL_SLACK_MILLIS}ms) " +
                "dentro dos ${AlarmReceiver.SUSPEND_BUDGET_MILLIS}ms externos",
            AlarmReceiver.BLOCK_GUARD_BUDGET_MILLIS + FIRST_ALARM_RESOLUTION_BUDGET_MILLIS <=
                AlarmReceiver.SUSPEND_BUDGET_MILLIS - AlarmReceiver.DISPATCH_TAIL_SLACK_MILLIS,
        )
    }

    // ── Funções puras: findNextActiveDate / isInsideActiveWindow ────
    //
    // Compartilhadas por TODOS os caminhos que produzem datas futuras
    // (scheduler, HomeViewModel, BootReceiver) — regressão aqui rearmaria
    // alarme em dia inativo ou liberaria Check fora da janela.

    /** Quarta-feira fixa, mesma âncora do DynamicSchedulerUseCaseTest. */
    private val wednesday: LocalDate = LocalDate.of(2026, 5, 20)

    @Test
    fun `findNextActiveDate pula dias inativos ate o proximo ativo`() {
        // Quarta → ativo só sexta e sábado → espera sexta (22/05).
        val result = findNextActiveDate(
            after = wednesday,
            activeDaysOfWeek = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
        )

        assertEquals(LocalDate.of(2026, 5, 22), result)
    }

    @Test
    fun `findNextActiveDate e estritamente depois mesmo quando hoje e ativo`() {
        // Quarta com quarta ativa → próxima QUARTA (27/05), nunca a própria data.
        val result = findNextActiveDate(
            after = wednesday,
            activeDaysOfWeek = setOf(DayOfWeek.WEDNESDAY),
        )

        assertEquals(LocalDate.of(2026, 5, 27), result)
    }

    @Test
    fun `findNextActiveDate com todos os dias inativos cai para after mais um`() {
        // Config patológica documentada: fallback defensivo para não travar
        // o scheduler.
        val result = findNextActiveDate(
            after = wednesday,
            activeDaysOfWeek = emptySet(),
        )

        assertEquals(wednesday.plusDays(1), result)
    }

    @Test
    fun `isInsideActiveWindow false em dia inativo mesmo dentro do horario`() {
        val noonWednesday = wednesday.atTime(12, 0)

        assertTrue(
            "dia inativo deve vetar mesmo com horário dentro da janela",
            !isInsideActiveWindow(
                now = noonWednesday,
                window = window, // 09:30-18:00
                activeDays = setOf(DayOfWeek.MONDAY),
            ),
        )
    }

    @Test
    fun `isInsideActiveWindow true sem janela configurada em dia ativo`() {
        val threeAm = wednesday.atTime(3, 0)

        assertTrue(
            "window null = sempre ativo (em dia ativo)",
            isInsideActiveWindow(now = threeAm, window = null, activeDays = allDays),
        )
    }

    @Test
    fun `isInsideActiveWindow trata bordas da janela como inclusivas`() {
        // Pina o comportamento atual: startTime e endTime são INCLUSIVOS
        // (!isBefore && !isAfter). O engine de agendamento trata endTime como
        // exclusivo — assimetria conhecida de 1 minuto; mudar este contrato
        // exige revisitar canCheck/overshoot gating.
        val atStart = wednesday.atTime(9, 30)
        val atEnd = wednesday.atTime(18, 0)
        val pastEnd = wednesday.atTime(18, 1)

        assertTrue(isInsideActiveWindow(atStart, window, allDays))
        assertTrue(isInsideActiveWindow(atEnd, window, allDays))
        assertTrue(!isInsideActiveWindow(pastEnd, window, allDays))
    }
}
