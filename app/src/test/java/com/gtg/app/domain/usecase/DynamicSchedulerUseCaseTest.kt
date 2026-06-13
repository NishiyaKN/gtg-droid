package com.gtg.app.domain.usecase

import android.util.Log
import com.gtg.app.data.local.IntervalMode
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.Recurrence
import com.gtg.app.domain.model.ScheduleResult
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.CalendarEventRepository
import com.gtg.app.domain.repository.InactivityBlockRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // INACTIVITY_PROXIMITY_MINUTES=15 → "mid-block" (Caso B) → adia para
        // fim do bloco + buffer = 11:30 + 5min = 11:35 exato.
        val blocks = listOf(lunchBlock(LocalTime.of(10, 30), LocalTime.of(11, 30)))

        val result = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.DYNAMIC,
            deps = deps(blocks),
        )

        assertEquals(ScheduleResult.Scheduled(wednesday.atTime(11, 35)), result)
    }

    // ── Caso A: candidato perto do INÍCIO do bloco antecipa ─────────

    @Test
    fun `DYNAMIC perto do inicio do bloco antecipa para inicio menos buffer`() {
        val checkTime = wednesday.atTime(10, 0)
        // candidate = 10:45 cai em 10:40-11:30 com minutesPastStart=5 (<15)
        // → Caso A: antecipa para blockStart - 5min = 10:35.
        // earliestAllowed = now + 20 = 10:20 ≤ 10:35 e 10:35 ≥ windowStart
        // (08:00) → antecipação é válida.
        val blocks = listOf(lunchBlock(LocalTime.of(10, 40), LocalTime.of(11, 30)))

        val result = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.DYNAMIC,
            deps = deps(blocks),
        )

        assertEquals(ScheduleResult.Scheduled(wednesday.atTime(10, 35)), result)
    }

    @Test
    fun `DYNAMIC Caso A sem espaco para antecipar adia para fim do bloco`() {
        val checkTime = wednesday.atTime(10, 0)
        // now = 10:30 → earliestAllowed = 10:50; candidate 10:45 é clampado
        // para 10:50 (regra 3), que cai em 10:40-11:30 com minutesPastStart=10
        // (<15) → Caso A; antecipado 10:35 VIOLARIA o descanso mínimo
        // (10:35 < 10:50) → fallback: fim do bloco + buffer = 11:35.
        val now = wednesday.atTime(10, 30)
        val blocks = listOf(lunchBlock(LocalTime.of(10, 40), LocalTime.of(11, 30)))

        val result = useCase.evaluateWithDependencies(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = now,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.DYNAMIC,
            deps = deps(blocks),
        )

        assertEquals(ScheduleResult.Scheduled(wednesday.atTime(11, 35)), result)
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

    // ── U1: resolver de primeiro alarme do dia (plano 2026-06-11) ───
    //
    // Cenário base do bug reportado: janela começando 09:30, evento de
    // calendário 09:10–09:40 → o alarme de início de janela deve adiar
    // para 09:45 (fim do bloco + 5min buffer), nunca tocar às 09:30.

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** Janela do bug reportado: 09:30–18:00. */
    private val windowNineThirty = ActivityWindow(
        id = 1L,
        startTime = LocalTime.of(9, 30),
        endTime = LocalTime.of(18, 0),
        isActive = true,
    )

    private fun resolve(
        blocks: List<InactivityBlock>,
        window: ActivityWindow = windowNineThirty,
        candidate: LocalDateTime = wednesday.atTime(window.startTime),
    ) = useCase.resolveFirstAlarmForDay(
        date = wednesday,
        window = window,
        blocks = blocks,
        candidate = candidate,
    )

    private fun resolved(time: LocalTime) =
        DynamicSchedulerUseCase.FirstAlarmResolution.Resolved(wednesday.atTime(time))

    @Test
    fun `resolver com bloco cobrindo inicio da janela adia para fim do bloco mais buffer`() {
        // Geometria exata do bug: janela 09:30, evento 09:10–09:40 → 09:45.
        val blocks = listOf(lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40)))

        assertEquals(resolved(LocalTime.of(9, 45)), resolve(blocks))
    }

    @Test
    fun `resolver sem blocos retorna inicio da janela`() {
        assertEquals(resolved(LocalTime.of(9, 30)), resolve(emptyList()))
    }

    @Test
    fun `resolver com bloco comecando exatamente no inicio da janela adia sem antecipar`() {
        // Antecipação cairia antes da janela — resolver é postpone-only.
        val blocks = listOf(lunchBlock(LocalTime.of(9, 30), LocalTime.of(10, 0)))

        assertEquals(resolved(LocalTime.of(10, 5)), resolve(blocks))
    }

    @Test
    fun `resolver com bloco terminando exatamente no inicio da janela nao colide`() {
        // Semântica half-open [start, end): candidato == blockEnd não colide.
        val blocks = listOf(lunchBlock(LocalTime.of(9, 0), LocalTime.of(9, 30)))

        assertEquals(resolved(LocalTime.of(9, 30)), resolve(blocks))
    }

    @Test
    fun `resolver mescla blocos consecutivos sem gap em passada unica`() {
        // 09:00–10:00 + 10:00–10:30 viram um cluster → 10:35, sem ping-pong.
        val blocks = listOf(
            lunchBlock(LocalTime.of(9, 0), LocalTime.of(10, 0)),
            lunchBlock(LocalTime.of(10, 0), LocalTime.of(10, 30)),
        )

        assertEquals(resolved(LocalTime.of(10, 35)), resolve(blocks))
    }

    @Test
    fun `resolver mescla blocos com gap exatamente igual ao buffer`() {
        // Fronteira onde implementação "menor que" vs "menor ou igual" diverge:
        // gap de exatos 5min DEVE mesclar, senão o candidato adiado (10:05)
        // cai exatamente no início do próximo bloco.
        val blocks = listOf(
            lunchBlock(LocalTime.of(9, 0), LocalTime.of(10, 0)),
            lunchBlock(LocalTime.of(10, 5), LocalTime.of(10, 30)),
        )

        assertEquals(resolved(LocalTime.of(10, 35)), resolve(blocks))
    }

    @Test
    fun `resolver mescla blocos com gap menor que o buffer`() {
        val blocks = listOf(
            lunchBlock(LocalTime.of(9, 0), LocalTime.of(10, 0)),
            lunchBlock(LocalTime.of(10, 3), LocalTime.of(10, 30)),
        )

        assertEquals(resolved(LocalTime.of(10, 35)), resolve(blocks))
    }

    @Test
    fun `resolver nao mescla blocos com gap maior que o buffer`() {
        // Gap de 6min > buffer: clusters separados; candidato adiado para
        // 10:05 cai no gap livre e fica lá.
        val blocks = listOf(
            lunchBlock(LocalTime.of(9, 0), LocalTime.of(10, 0)),
            lunchBlock(LocalTime.of(10, 6), LocalTime.of(10, 30)),
        )

        assertEquals(resolved(LocalTime.of(10, 5)), resolve(blocks))
    }

    @Test
    fun `resolver mescla blocos de fontes diferentes manuais e calendar`() {
        // Sobreposição parcial entre fonte manual e virtual do Calendar —
        // o resolver recebe a lista concatenada e mescla por intervalo.
        val blocks = listOf(
            lunchBlock(LocalTime.of(9, 0), LocalTime.of(9, 35)),
            lunchBlock(LocalTime.of(9, 33), LocalTime.of(9, 50)).copy(id = -42L),
        )

        assertEquals(resolved(LocalTime.of(9, 55)), resolve(blocks))
    }

    @Test
    fun `resolver com bloco cobrindo janela inteira sinaliza overflow`() {
        val shortWindow = windowNineThirty.copy(endTime = LocalTime.of(10, 0))
        val blocks = listOf(lunchBlock(LocalTime.of(9, 0), LocalTime.of(11, 0)))

        val result = resolve(blocks, window = shortWindow)

        assertEquals(DynamicSchedulerUseCase.FirstAlarmResolution.OverflowsWindowEnd, result)
    }

    @Test
    fun `resolver com candidato 5min apos inicio do bloco ainda adia sem antecipar`() {
        // Caller fire-time: candidato dentro do bloco perto do início. A engine
        // Rule 4 anteciparia (Caso A); o resolver NUNCA antecipa.
        val blocks = listOf(lunchBlock(LocalTime.of(10, 0), LocalTime.of(10, 30)))

        val result = resolve(blocks, candidate = wednesday.atTime(10, 5))

        assertEquals(resolved(LocalTime.of(10, 35)), result)
    }

    // STRICT não tem teste no resolver puro: o primitivo é DYNAMIC-only por
    // contrato; o curto-circuito STRICT vive (e é testado) nos pontos de
    // entrada reais — wrapper, decisão fire-time e calculateNextAlarm.

    // ── U1: wrapper suspend com lookahead de dias ───────────────────

    private fun stubbedUseCase(
        blocksByDate: Map<LocalDate, List<InactivityBlock>> = emptyMap(),
        recurrentBlocks: List<InactivityBlock> = emptyList(),
        activeWindow: ActivityWindow? = windowNineThirty,
    ): Triple<DynamicSchedulerUseCase, InactivityBlockRepository, CalendarEventRepository> {
        val windowRepo = mockk<ActivityWindowRepository> {
            coEvery { getActiveWindow() } returns activeWindow
        }
        val manualRepo = mockk<InactivityBlockRepository> {
            coEvery { getBlocksActiveOn(any()) } answers {
                recurrentBlocks + (blocksByDate[firstArg<LocalDate>()] ?: emptyList())
            }
        }
        val calendarRepo = mockk<CalendarEventRepository> {
            coEvery { getBlocksOn(any()) } returns emptyList()
            coEvery { getBlocksInRange(any(), any()) } returns emptyMap()
        }
        return Triple(
            DynamicSchedulerUseCase(windowRepo, manualRepo, calendarRepo),
            manualRepo,
            calendarRepo,
        )
    }

    private val fullDayDailyBlock = InactivityBlock(
        id = 99L,
        startTime = InactivityBlock.FULL_DAY_START,
        endTime = InactivityBlock.FULL_DAY_END,
        recurrence = Recurrence.DAILY,
    )

    @Test
    fun `wrapper resolve bloco no inicio da janela do dia pedido`() = runTest {
        val (scheduler, _, _) = stubbedUseCase(
            blocksByDate = mapOf(
                wednesday to listOf(lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40))),
            ),
        )

        val result = scheduler.resolveFirstAlarmStartingAt(
            startDate = wednesday,
            activeDaysOfWeek = weekdays,
        )

        assertEquals(wednesday.atTime(9, 45), result)
    }

    @Test
    fun `wrapper rola para o proximo dia ativo quando o dia inteiro esta bloqueado`() = runTest {
        // Quarta tomada por bloco que cobre a janela toda → quinta 09:30,
        // e os blocos DA QUINTA são consultados (fixture: quinta livre).
        val (scheduler, _, _) = stubbedUseCase(
            blocksByDate = mapOf(
                wednesday to listOf(lunchBlock(LocalTime.of(9, 0), LocalTime.of(18, 30))),
            ),
        )

        val result = scheduler.resolveFirstAlarmStartingAt(
            startDate = wednesday,
            activeDaysOfWeek = weekdays,
        )

        assertEquals(wednesday.plusDays(1).atTime(9, 30), result)
    }

    @Test
    fun `wrapper aplica blocos do dia rolado tambem`() = runTest {
        // Quarta bloqueada inteira; quinta tem evento cobrindo o início →
        // resultado é quinta 09:45, não quinta 09:30.
        val thursday = wednesday.plusDays(1)
        val (scheduler, _, calendarRepo) = stubbedUseCase(
            blocksByDate = mapOf(
                wednesday to listOf(lunchBlock(LocalTime.of(9, 0), LocalTime.of(18, 30))),
                thursday to listOf(
                    lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40)).copy(specificDate = thursday),
                ),
            ),
        )

        val result = scheduler.resolveFirstAlarmStartingAt(
            startDate = wednesday,
            activeDaysOfWeek = weekdays,
        )

        assertEquals(thursday.atTime(9, 45), result)
        // Bounds do batch: range cobre exatamente as 7 datas candidatas
        // (qua 20 → qui 28, pulando o fim de semana), em UMA query.
        coVerify(exactly = 1) {
            calendarRepo.getBlocksInRange(wednesday, LocalDate.of(2026, 5, 28))
        }
    }

    @Test
    fun `wrapper exaure lookahead com bloco diario permanente e cai para inicio bare`() = runTest {
        // Bloco DAILY 00:00–23:59 em todos os dias: 7 tentativas falham e o
        // wrapper degrada para o início bare do último dia ativo examinado
        // (nunca "sem alarme"). Log.w não é mockado pelo harness → stub.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0

        val (scheduler, _, _) = stubbedUseCase(recurrentBlocks = listOf(fullDayDailyBlock))

        val result = scheduler.resolveFirstAlarmStartingAt(
            startDate = wednesday,
            activeDaysOfWeek = weekdays,
        )

        // Examinados: qua 20, qui 21, sex 22, seg 25, ter 26, qua 27, qui 28.
        assertEquals(LocalDate.of(2026, 5, 28).atTime(9, 30), result)
    }

    @Test
    fun `wrapper STRICT retorna inicio bare sem consultar blocos`() = runTest {
        val (scheduler, manualRepo, calendarRepo) = stubbedUseCase(
            recurrentBlocks = listOf(fullDayDailyBlock),
        )

        val result = scheduler.resolveFirstAlarmStartingAt(
            startDate = wednesday,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.STRICT,
        )

        assertEquals(wednesday.atTime(9, 30), result)
        coVerify(exactly = 0) { manualRepo.getBlocksActiveOn(any()) }
        coVerify(exactly = 0) { calendarRepo.getBlocksOn(any()) }
        coVerify(exactly = 0) { calendarRepo.getBlocksInRange(any(), any()) }
    }

    @Test
    fun `wrapper degrada para inicio bare quando a busca de blocos falha`() = runTest {
        // Fail-open interno (R5): falha de fetch nunca propaga aos callers —
        // todos os caminhos de rollover sempre armam ALGO.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0

        val (scheduler, manualRepo, _) = stubbedUseCase()
        coEvery { manualRepo.getBlocksActiveOn(any()) } throws RuntimeException("Room indisponível")

        val result = scheduler.resolveFirstAlarmStartingAt(
            startDate = wednesday,
            activeDaysOfWeek = weekdays,
        )

        assertEquals(wednesday.atTime(9, 30), result)
    }

    @Test
    fun `wrapper normaliza dia inativo para o proximo dia ativo`() = runTest {
        // Sábado não é dia ativo → resolve para segunda 09:30.
        val saturday = LocalDate.of(2026, 5, 23)
        val (scheduler, _, _) = stubbedUseCase()

        val result = scheduler.resolveFirstAlarmStartingAt(
            startDate = saturday,
            activeDaysOfWeek = weekdays,
        )

        assertEquals(LocalDate.of(2026, 5, 25).atTime(9, 30), result)
    }

    @Test
    fun `wrapper sem janela configurada retorna null`() = runTest {
        val (scheduler, _, _) = stubbedUseCase(activeWindow = null)

        val result = scheduler.resolveFirstAlarmStartingAt(
            startDate = wednesday,
            activeDaysOfWeek = weekdays,
        )

        assertNull(result)
    }

    // ── U3: decisão fire-time Ring/Suppress (guard do AlarmReceiver) ─

    private fun decide(
        now: LocalDateTime,
        blocks: List<InactivityBlock>,
        window: ActivityWindow = windowNineThirty,
        intervalMode: IntervalMode = IntervalMode.DYNAMIC,
        canScheduleExactAlarms: Boolean = true,
    ) = useCase.decideFireTimeDispatch(
        now = now,
        window = window,
        blocks = blocks,
        intervalMode = intervalMode,
        canScheduleExactAlarms = canScheduleExactAlarms,
    )

    @Test
    fun `fireTime DYNAMIC dentro de bloco suprime e rearma no fim do cluster`() {
        // Geometria do bug em staleness de calendário: evento criado depois
        // do arme cobre o disparo das 09:30 → suprime e rearma 09:45.
        val blocks = listOf(lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40)))

        val decision = decide(now = wednesday.atTime(9, 30), blocks = blocks)

        assertEquals(
            DynamicSchedulerUseCase.FireTimeDecision.SuppressAndReschedule(wednesday.atTime(9, 45)),
            decision,
        )
    }

    @Test
    fun `fireTime fora de qualquer bloco toca`() {
        val blocks = listOf(lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40)))

        val decision = decide(now = wednesday.atTime(11, 0), blocks = blocks)

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.Ring, decision)
    }

    @Test
    fun `fireTime dentro de gap livre entre clusters toca`() {
        // O probe não pode "escorregar" para o próximo bloco: 09:30 está no
        // gap livre (20min > buffer, clusters separados) → Ring.
        val blocks = listOf(
            lunchBlock(LocalTime.of(9, 0), LocalTime.of(9, 20)),
            lunchBlock(LocalTime.of(9, 40), LocalTime.of(10, 0)),
        )

        val decision = decide(now = wednesday.atTime(9, 30), blocks = blocks)

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.Ring, decision)
    }

    @Test
    fun `fireTime em gap sub-buffer entre blocos toca`() {
        // A engine (Regra 4, blocos crus) pode armar legitimamente num gap
        // de 3min entre blocos; a supressão decide por bloco CRU, não por
        // cluster mesclado — suprimir aqui moveria em silêncio um alarme que
        // a própria engine aprovou.
        val blocks = listOf(
            lunchBlock(LocalTime.of(13, 0), LocalTime.of(13, 58)),
            lunchBlock(LocalTime.of(14, 1), LocalTime.of(15, 0)),
        )

        val decision = decide(now = wednesday.atTime(13, 59), blocks = blocks)

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.Ring, decision)
    }

    @Test
    fun `fireTime back-to-back mesclado rearma apos o cluster inteiro`() {
        // Espelho do teste de mescla gap==buffer no domínio fire-time.
        val blocks = listOf(
            lunchBlock(LocalTime.of(9, 0), LocalTime.of(10, 0)),
            lunchBlock(LocalTime.of(10, 5), LocalTime.of(10, 30)),
        )

        val decision = decide(now = wednesday.atTime(9, 30), blocks = blocks)

        assertEquals(
            DynamicSchedulerUseCase.FireTimeDecision.SuppressAndReschedule(wednesday.atTime(10, 35)),
            decision,
        )
    }

    @Test
    fun `fireTime dentro do segundo bloco cru do cluster rearma no fim do cluster inteiro`() {
        // Pina o split cru-vs-mesclado pelo lado do segundo bloco: a contenção
        // bate no bloco 10:05–10:30, mas o rearme vem do cluster inteiro.
        val blocks = listOf(
            lunchBlock(LocalTime.of(9, 0), LocalTime.of(10, 0)),
            lunchBlock(LocalTime.of(10, 5), LocalTime.of(10, 30)),
        )

        val decision = decide(now = wednesday.atTime(10, 15), blocks = blocks)

        assertEquals(
            DynamicSchedulerUseCase.FireTimeDecision.SuppressAndReschedule(wednesday.atTime(10, 35)),
            decision,
        )
    }

    @Test
    fun `fireTime STRICT dentro de bloco toca`() {
        // Contrato AE7 — STRICT toca dentro de bloco por design.
        val blocks = listOf(lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40)))

        val decision = decide(
            now = wednesday.atTime(9, 30),
            blocks = blocks,
            intervalMode = IntervalMode.STRICT,
        )

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.Ring, decision)
    }

    @Test
    fun `fireTime sem exact alarm converte para Ring antes de suprimir`() {
        // Suprimir sem conseguir rearmar = alarme perdido em silêncio.
        // Fail-open: incapacidade de rearme vira Ring, nunca abort-pós-suppress.
        val blocks = listOf(lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40)))

        val decision = decide(
            now = wednesday.atTime(9, 30),
            blocks = blocks,
            canScheduleExactAlarms = false,
        )

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.Ring, decision)
    }

    @Test
    fun `fireTime cluster passando do fim da janela rola para o proximo dia`() {
        // Reunião 17:00–19:00 com janela acabando 18:00: rearm 19:05 cairia
        // fora da janela → nunca armar verbatim; rolar para o próximo dia.
        val blocks = listOf(lunchBlock(LocalTime.of(17, 0), LocalTime.of(19, 0)))

        val decision = decide(now = wednesday.atTime(17, 30), blocks = blocks)

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.SuppressAndRollToNextDay, decision)
    }

    @Test
    fun `fireTime cluster overnight clampado nao rearma cruzando meia-noite`() {
        // Evento overnight particionado clampa em 23:59; rearm seria 00:04 do
        // dia seguinte (full-screen às 00:04!) → rolar para o próximo dia.
        val lateWindow = windowNineThirty.copy(
            startTime = LocalTime.of(6, 0),
            endTime = LocalTime.of(23, 45),
        )
        val blocks = listOf(lunchBlock(LocalTime.of(23, 0), LocalTime.of(23, 59)))

        val decision = decide(
            now = wednesday.atTime(23, 20),
            blocks = blocks,
            window = lateWindow,
        )

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.SuppressAndRollToNextDay, decision)
    }

    @Test
    fun `fireTime suspend STRICT retorna Ring sem buscar blocos`() = runTest {
        // Early-return ANTES do fetch: usuário STRICT não paga query
        // cross-process descartada a cada disparo.
        val (scheduler, manualRepo, calendarRepo) = stubbedUseCase(
            recurrentBlocks = listOf(fullDayDailyBlock),
        )

        val decision = scheduler.decideFireTimeDispatch(
            now = wednesday.atTime(9, 30),
            window = windowNineThirty,
            intervalMode = IntervalMode.STRICT,
            canScheduleExactAlarms = true,
        )

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.Ring, decision)
        coVerify(exactly = 0) { manualRepo.getBlocksActiveOn(any()) }
        coVerify(exactly = 0) { calendarRepo.getBlocksOn(any()) }
    }

    @Test
    fun `fireTime suspend sem exact alarm retorna Ring sem buscar blocos`() = runTest {
        val (scheduler, manualRepo, calendarRepo) = stubbedUseCase(
            recurrentBlocks = listOf(fullDayDailyBlock),
        )

        val decision = scheduler.decideFireTimeDispatch(
            now = wednesday.atTime(9, 30),
            window = windowNineThirty,
            intervalMode = IntervalMode.DYNAMIC,
            canScheduleExactAlarms = false,
        )

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.Ring, decision)
        coVerify(exactly = 0) { manualRepo.getBlocksActiveOn(any()) }
        coVerify(exactly = 0) { calendarRepo.getBlocksOn(any()) }
    }

    @Test
    fun `fireTime suspend com falha de fetch retorna Ring fail-open`() = runTest {
        // Guard falhando nunca pode custar o alarme: fetch lança → Ring.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0

        val (scheduler, manualRepo, _) = stubbedUseCase()
        coEvery { manualRepo.getBlocksActiveOn(any()) } throws RuntimeException("Room indisponível")

        val decision = scheduler.decideFireTimeDispatch(
            now = wednesday.atTime(9, 30),
            window = windowNineThirty,
            intervalMode = IntervalMode.DYNAMIC,
            canScheduleExactAlarms = true,
        )

        assertEquals(DynamicSchedulerUseCase.FireTimeDecision.Ring, decision)
    }

    @Test
    fun `fireTime suspend busca blocos do dia e decide`() = runTest {
        // Variante suspend usada pelo receiver: busca manual + calendar do
        // dia de `now` e delega à decisão pura.
        val (scheduler, _, _) = stubbedUseCase(
            blocksByDate = mapOf(
                wednesday to listOf(lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40))),
            ),
        )

        val decision = scheduler.decideFireTimeDispatch(
            now = wednesday.atTime(9, 30),
            window = windowNineThirty,
            intervalMode = IntervalMode.DYNAMIC,
            canScheduleExactAlarms = true,
        )

        assertEquals(
            DynamicSchedulerUseCase.FireTimeDecision.SuppressAndReschedule(wednesday.atTime(9, 45)),
            decision,
        )
    }

    // ── U2: calculateNextAlarm resolve rollovers contra blocos ──────

    @Test
    fun `calculateNextAlarm resolve rollover bare contra blocos do dia seguinte`() = runTest {
        // Check 17:50 + 45min = 18:35 > fim da janela (18:00) → rollover para
        // quinta. Quinta tem evento 09:10–09:40 cobrindo o início (09:30) →
        // o alarme armado deve ser 09:45, não o início bare.
        val thursday = wednesday.plusDays(1)
        val (scheduler, _, _) = stubbedUseCase(
            blocksByDate = mapOf(
                thursday to listOf(
                    lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40)).copy(specificDate = thursday),
                ),
            ),
        )
        val checkTime = wednesday.atTime(17, 50)

        val result = scheduler.calculateNextAlarm(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.DYNAMIC,
        )

        assertEquals(ScheduleResult.ScheduledTomorrow(thursday.atTime(9, 45)), result)
    }

    @Test
    fun `calculateNextAlarm nao reescreve fall-through cross-midnight mid-window`() = runTest {
        // 4º produtor de ScheduledTomorrow: check 22:00 + 240min = 02:00 do dia
        // seguinte, DENTRO da janela (01:00–23:00) e já validado pela Regra 4
        // com os blocos da data correta. O gate (horário != início da janela)
        // impede a re-resolução — reescrever do início anteciparia o alarme
        // (01:35) violando o intervalo pedido pelo usuário.
        val thursday = wednesday.plusDays(1)
        val nightWindow = ActivityWindow(
            id = 1L,
            startTime = LocalTime.of(1, 0),
            endTime = LocalTime.of(23, 0),
            isActive = true,
        )
        val (scheduler, _, _) = stubbedUseCase(
            blocksByDate = mapOf(
                thursday to listOf(
                    lunchBlock(LocalTime.of(1, 0), LocalTime.of(1, 30)).copy(specificDate = thursday),
                ),
            ),
            activeWindow = nightWindow,
        )
        val checkTime = wednesday.atTime(22, 0)

        val result = scheduler.calculateNextAlarm(
            checkTime = checkTime,
            baseIntervalMinutes = 240L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.DYNAMIC,
        )

        assertEquals(ScheduleResult.ScheduledTomorrow(thursday.atTime(2, 0)), result)
    }

    @Test
    fun `calculateNextAlarm com resolveRolloverAgainstBlocks false mantem inicio bare`() = runTest {
        // Caminho da preview de sessão parada: o resultado é descartado, então
        // o caller opta por não pagar o lookahead — comportamento pré-fix.
        val thursday = wednesday.plusDays(1)
        val (scheduler, _, _) = stubbedUseCase(
            blocksByDate = mapOf(
                thursday to listOf(
                    lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40)).copy(specificDate = thursday),
                ),
            ),
        )
        val checkTime = wednesday.atTime(17, 50)

        val result = scheduler.calculateNextAlarm(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.DYNAMIC,
            resolveRolloverAgainstBlocks = false,
        )

        assertEquals(ScheduleResult.ScheduledTomorrow(thursday.atTime(9, 30)), result)
    }

    @Test
    fun `calculateNextAlarm STRICT rollover mantem inicio bare mesmo com bloco`() = runTest {
        // Contrato AE7: STRICT pode tocar dentro de bloco por design — a
        // resolução de rollover não se aplica.
        val thursday = wednesday.plusDays(1)
        val (scheduler, _, _) = stubbedUseCase(
            blocksByDate = mapOf(
                thursday to listOf(
                    lunchBlock(LocalTime.of(9, 10), LocalTime.of(9, 40)).copy(specificDate = thursday),
                ),
            ),
        )
        val checkTime = wednesday.atTime(17, 50)

        val result = scheduler.calculateNextAlarm(
            checkTime = checkTime,
            baseIntervalMinutes = 45L,
            now = checkTime,
            activeDaysOfWeek = weekdays,
            intervalMode = IntervalMode.STRICT,
        )

        assertEquals(ScheduleResult.ScheduledTomorrow(thursday.atTime(9, 30)), result)
    }
}
