package com.gtg.app.presentation.settings

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.ActivityWindow
import com.gtg.app.domain.repository.ActivityWindowRepository
import com.gtg.app.domain.repository.CalendarEventRepository
import com.gtg.app.domain.repository.CalendarInfo
import com.gtg.app.domain.usecase.DynamicSchedulerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import javax.inject.Inject

data class SettingsUiState(
    val currentWindow: ActivityWindow? = null,
    val windowStartHour: Int = 9,
    val windowStartMinute: Int = 0,
    val windowEndHour: Int = 17,
    val windowEndMinute: Int = 0,
    /** Erro de validação da janela (ex: início >= fim). */
    val windowError: String? = null,
    /** True quando a janela em edição difere da persistida. */
    val windowDirty: Boolean = false,
    val baseIntervalMinutes: Long = SessionPreferences.DEFAULT_BASE_INTERVAL,
    val dailySetTarget: Int = SessionPreferences.DEFAULT_DAILY_SET_TARGET,
    val bypassDnd: Boolean = SessionPreferences.DEFAULT_BYPASS_DND,
    /** null → som padrão de alarme do sistema. */
    val alarmSoundUri: String? = null,
    /** Nome legível do som atual ("Alarme padrão", "Cesium", etc.). */
    val alarmSoundTitle: String = "Padrão do sistema",
    /** true se [alarmSoundUri] != null (usuário customizou). */
    val isCustomSound: Boolean = false,
    /** Re-alerta automático após o zero (overshoot). */
    val overshootRepeatEnabled: Boolean = SessionPreferences.DEFAULT_OVERSHOOT_ENABLED,
    /** Intervalo (min) entre re-alertas. Faixa MIN..MAX_OVERSHOOT_MINUTES. */
    val overshootRepeatMinutes: Int = SessionPreferences.DEFAULT_OVERSHOOT_MINUTES,
    /** Integração com o Calendar Provider (Google Calendar etc) ativa. */
    val calendarEnabled: Boolean = false,
    /** Calendários selecionados pelo usuário para auto-bloqueio. */
    val calendarSelectedIds: Set<Long> = emptySet(),
    /** Eventos do Calendar aparecem com título real (vs "Ocupado"). */
    val calendarShowTitles: Boolean = true,
    /** Permissão READ_CALENDAR concedida. */
    val calendarPermissionGranted: Boolean = false,
    /** Lista de calendários disponíveis no device (carregada sob demanda). */
    val availableCalendars: List<CalendarInfo> = emptyList(),
    /** Dias da semana em que o app pode disparar alarmes. */
    val activeDaysOfWeek: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activityWindowRepository: ActivityWindowRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val sessionPrefs: SessionPreferences,
) : ViewModel() {

    companion object {
        const val MIN_BASE_INTERVAL = DynamicSchedulerUseCase.MINIMUM_REST_MINUTES
        const val MAX_BASE_INTERVAL = 240L
        const val MIN_DAILY_TARGET = 1
        const val MAX_DAILY_TARGET = 50
    }

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // Observa a janela ativa do Room. Reseta os campos editáveis sempre que
        // a persistência mudar — exceto se o usuário estiver em edição (dirty).
        viewModelScope.launch {
            activityWindowRepository.observeActiveWindow().collectLatest { window ->
                _state.update { current ->
                    if (current.windowDirty) {
                        // Mantém edição em curso, só atualiza a referência persistida
                        current.copy(currentWindow = window)
                    } else {
                        current.copy(
                            currentWindow = window,
                            windowStartHour = window?.startTime?.hour ?: 9,
                            windowStartMinute = window?.startTime?.minute ?: 0,
                            windowEndHour = window?.endTime?.hour ?: 17,
                            windowEndMinute = window?.endTime?.minute ?: 0,
                            windowError = null,
                        )
                    }
                }
            }
        }

        // Observa SharedPreferences para intervalo base, meta diária, DND e som.
        viewModelScope.launch {
            sessionPrefs.observeChanges().collect {
                val uri = sessionPrefs.alarmSoundUri
                _state.update {
                    it.copy(
                        baseIntervalMinutes = sessionPrefs.baseIntervalMinutes,
                        dailySetTarget = sessionPrefs.dailySetTarget,
                        bypassDnd = sessionPrefs.bypassDnd,
                        alarmSoundUri = uri,
                        alarmSoundTitle = resolveSoundTitle(uri),
                        isCustomSound = uri != null,
                        overshootRepeatEnabled = sessionPrefs.overshootRepeatEnabled,
                        overshootRepeatMinutes = sessionPrefs.overshootRepeatMinutes,
                        calendarEnabled = sessionPrefs.calendarIntegrationEnabled,
                        calendarSelectedIds = sessionPrefs.calendarSelectedIds,
                        calendarShowTitles = sessionPrefs.calendarShowTitles,
                        activeDaysOfWeek = sessionPrefs.activeDaysOfWeek,
                    )
                }
            }
        }
    }

    /**
     * Resolve o nome legível do som via RingtoneManager.
     * Para URI nula, retorna o título do som de alarme padrão do sistema.
     * Fallback seguro se a URI for inválida ou inacessível.
     */
    private fun resolveSoundTitle(uriString: String?): String {
        val uri: Uri = uriString?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return "Padrão do sistema"
        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull() ?: "Padrão do sistema"
    }

    // ── Janela de Atividade ──────────────────────────────────────

    fun updateWindowStart(hour: Int, minute: Int) {
        _state.update {
            it.copy(
                windowStartHour = hour.coerceIn(0, 23),
                windowStartMinute = minute.coerceIn(0, 59),
                windowError = null,
                windowDirty = true,
            )
        }
    }

    fun updateWindowEnd(hour: Int, minute: Int) {
        _state.update {
            it.copy(
                windowEndHour = hour.coerceIn(0, 23),
                windowEndMinute = minute.coerceIn(0, 59),
                windowError = null,
                windowDirty = true,
            )
        }
    }

    fun saveWindow() {
        val s = _state.value
        val start = LocalTime.of(s.windowStartHour, s.windowStartMinute)
        val end = LocalTime.of(s.windowEndHour, s.windowEndMinute)

        if (!start.isBefore(end)) {
            _state.update { it.copy(windowError = "O início deve ser antes do fim.") }
            return
        }

        viewModelScope.launch {
            val existing = s.currentWindow
            val toSave = existing?.copy(startTime = start, endTime = end, isActive = true)
                ?: ActivityWindow(startTime = start, endTime = end, isActive = true)
            activityWindowRepository.save(toSave)
            _state.update { it.copy(windowError = null, windowDirty = false) }
        }
    }

    // ── Intervalo Base / Meta Diária ─────────────────────────────
    // Salvamento direto: o Slider já coage a faixa válida, sem necessidade
    // de "Salvar" explícito. As escritas em SharedPreferences disparam o
    // observeChanges() acima, que re-emite o state com o valor persistido.

    fun setBaseInterval(minutes: Long) {
        val clamped = minutes.coerceIn(MIN_BASE_INTERVAL, MAX_BASE_INTERVAL)
        sessionPrefs.setBaseInterval(clamped)
    }

    fun setDailyTarget(target: Int) {
        val clamped = target.coerceIn(MIN_DAILY_TARGET, MAX_DAILY_TARGET)
        sessionPrefs.setDailySetTarget(clamped)
    }

    fun setBypassDnd(enabled: Boolean) {
        sessionPrefs.setBypassDnd(enabled)
    }

    // ── Som do Alarme ────────────────────────────────────────────

    /**
     * Define o som do alarme. URI nula reseta para o som padrão do sistema.
     * O `observeChanges()` re-emite o estado com o título atualizado.
     */
    fun setAlarmSound(uri: Uri?) {
        sessionPrefs.setAlarmSoundUri(uri?.toString())
    }

    /** Reset para o som padrão de alarme do sistema. */
    fun resetAlarmSound() {
        sessionPrefs.setAlarmSoundUri(null)
    }

    // ── Re-alerta (Overshoot) ────────────────────────────────────

    fun setOvershootEnabled(enabled: Boolean) {
        sessionPrefs.setOvershootRepeatEnabled(enabled)
    }

    fun setOvershootInterval(minutes: Int) {
        sessionPrefs.setOvershootRepeatMinutes(minutes)
    }

    // ── Integração com Calendar ──────────────────────────────────

    /**
     * UI chama após cada lifecycle ON_RESUME e após o usuário retornar do
     * fluxo de permissão. Atualiza `calendarPermissionGranted` e, se OK,
     * carrega [availableCalendars]. Sem permissão, esvazia a lista.
     */
    fun refreshCalendarPermissionAndCalendars() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CALENDAR,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) {
            _state.update {
                it.copy(calendarPermissionGranted = false, availableCalendars = emptyList())
            }
            return
        }

        _state.update { it.copy(calendarPermissionGranted = true) }
        viewModelScope.launch {
            val list = calendarEventRepository.listAvailableCalendars()
            _state.update { it.copy(availableCalendars = list) }
        }
    }

    fun setCalendarEnabled(enabled: Boolean) {
        sessionPrefs.setCalendarIntegrationEnabled(enabled)
    }

    fun toggleCalendarSelected(calendarId: Long) {
        val current = sessionPrefs.calendarSelectedIds
        val updated = if (calendarId in current) current - calendarId else current + calendarId
        sessionPrefs.setCalendarSelectedIds(updated)
    }

    fun setCalendarShowTitles(show: Boolean) {
        sessionPrefs.setCalendarShowTitles(show)
    }

    /**
     * Toggle de um dia da semana na lista de dias ativos. Mantém pelo menos
     * um dia ativo — desativar todos significaria o scheduler nunca disparar,
     * confundindo o usuário.
     */
    fun toggleActiveDay(day: DayOfWeek) {
        val current = sessionPrefs.activeDaysOfWeek
        val updated = if (day in current) current - day else current + day
        if (updated.isEmpty()) return
        sessionPrefs.setActiveDaysOfWeek(updated)
    }
}
