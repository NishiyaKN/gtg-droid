package com.gtg.app.presentation.settings

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.gtg.app.MainDispatcherRule
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.CalendarEventRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.DayOfWeek

/**
 * Testes do SettingsViewModel — foco no setter setShowDailyTarget e na
 * delegação para SessionPreferences. A propagação do pref para
 * SettingsUiState.showDailyTarget via observeChanges é coberta indiretamente
 * pelos defaults dos mocks; o setter em si é o ponto de escrita testável.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = mockk(relaxed = true)
    private val activityWindowRepository: ActivityWindowRepository = mockk(relaxed = true)
    private val calendarEventRepository: CalendarEventRepository = mockk(relaxed = true)
    private val sessionPrefs: SessionPreferences = mockk(relaxed = true)

    @Before
    fun setUp() {
        // RingtoneManager + Uri.parse são estáticos do framework Android —
        // o init do VM resolve título do som no IO dispatcher e bate nesses
        // APIs. Mockar evita RuntimeException em JVM.
        mockkStatic(RingtoneManager::class)
        mockkStatic(Uri::class)
        every { RingtoneManager.getDefaultUri(any()) } returns null
        every { RingtoneManager.getRingtone(any(), any()) } returns null
        every { Uri.parse(any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun buildViewModel(showDailyTarget: Boolean = false): SettingsViewModel {
        // ActivityWindow flow vazio + observeChanges com uma emissão inicial
        // são o mínimo para o init não bloquear.
        every { activityWindowRepository.observeActiveWindow() } returns flowOf(null)
        every { sessionPrefs.observeChanges() } returns flowOf(0L)
        every { sessionPrefs.baseIntervalMinutes } returns 45L
        every { sessionPrefs.dailySetTarget } returns 10
        every { sessionPrefs.showDailyTarget } returns showDailyTarget
        every { sessionPrefs.bypassDnd } returns true
        every { sessionPrefs.alarmSoundUri } returns null
        every { sessionPrefs.overshootRepeatEnabled } returns true
        every { sessionPrefs.overshootRepeatMinutes } returns 5
        every { sessionPrefs.calendarIntegrationEnabled } returns false
        every { sessionPrefs.calendarSelectedIds } returns emptySet()
        every { sessionPrefs.calendarShowTitles } returns true
        every { sessionPrefs.activeDaysOfWeek } returns DayOfWeek.entries.toSet()
        coEvery { calendarEventRepository.listAvailableCalendars() } returns emptyList()

        return SettingsViewModel(
            context = context,
            activityWindowRepository = activityWindowRepository,
            calendarEventRepository = calendarEventRepository,
            sessionPrefs = sessionPrefs,
        )
    }

    @Test
    fun `setShowDailyTarget true delega para SessionPreferences`() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.setShowDailyTarget(true)

        verify(exactly = 1) { sessionPrefs.setShowDailyTarget(true) }
    }

    @Test
    fun `setShowDailyTarget false delega para SessionPreferences`() = runTest {
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.setShowDailyTarget(false)

        verify(exactly = 1) { sessionPrefs.setShowDailyTarget(false) }
    }

    @Test
    fun `state inicial reflete showDailyTarget do SessionPreferences`() = runTest {
        val vm = buildViewModel(showDailyTarget = true)
        advanceUntilIdle()

        val state = vm.state.value

        assert(state.showDailyTarget) {
            "Esperava state.showDailyTarget=true após observeChanges propagar"
        }
    }

    @Test
    fun `setDailyTarget continua intocado pelo toggle de showDailyTarget`() = runTest {
        // Garante que o setter de visibilidade não escreve em
        // KEY_DAILY_SET_TARGET — preserva o valor configurado entre toggles.
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.setShowDailyTarget(false)
        vm.setShowDailyTarget(true)

        verify(exactly = 0) { sessionPrefs.setDailySetTarget(any()) }
    }
}
