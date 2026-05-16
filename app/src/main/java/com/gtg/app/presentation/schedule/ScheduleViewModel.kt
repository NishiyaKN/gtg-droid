package com.gtg.app.presentation.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.Recurrence
import com.gtg.app.domain.repository.CalendarEventRepository
import com.gtg.app.domain.repository.InactivityBlockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import javax.inject.Inject

/** Aba visível na [com.gtg.app.presentation.schedule.ScheduleScreen]. */
enum class ScheduleTab {
    CALENDAR,
    LIST,
}

/** Origem de um bloqueio renderizado na agenda. */
enum class BlockSource { MANUAL, CALENDAR }

/**
 * Wrapper que carrega o `InactivityBlock` virtualizado + a origem.
 * Para [BlockSource.CALENDAR], [calendarEventId] é o ID original do evento
 * no Calendar Provider — usado pelo override manual.
 */
data class DisplayBlock(
    val block: InactivityBlock,
    val source: BlockSource,
    val calendarEventId: Long? = null,
)

data class ScheduleUiState(
    val blocks: List<InactivityBlock> = emptyList(),
    /** Eventos importados do Calendar Provider, agrupados por dia. */
    val calendarBlocksByDate: Map<LocalDate, List<DisplayBlock>> = emptyMap(),
    /** Diálogo "Personalizar bloqueio" — exposto quando user toca em evento do Calendar. */
    val personalizeTarget: DisplayBlock? = null,
    val showDialog: Boolean = false,
    val dialogTitle: String = "",
    val dialogStartHour: Int = 12,
    val dialogStartMinute: Int = 0,
    val dialogEndHour: Int = 13,
    val dialogEndMinute: Int = 0,
    val dialogRecurrence: Recurrence = Recurrence.NONE,
    val dialogWeekDays: Set<DayOfWeek> = emptySet(),
    val dialogDate: LocalDate = LocalDate.now(),
    val dialogDayOfMonth: Int = 1,
    /** Se true, o bloco será 00:00 → 23:59 e os campos de hora ficam ocultos. */
    val dialogAllDay: Boolean = false,
    /** Se não-null, editando; se null, criando. */
    val editingBlock: InactivityBlock? = null,

    // ── Calendário ───────────────────────────────────────────────
    val selectedTab: ScheduleTab = ScheduleTab.CALENDAR,
    /** Mês exibido na vista calendário. */
    val calendarMonth: YearMonth = YearMonth.now(),
)

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repository: InactivityBlockRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val sessionPrefs: SessionPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collectLatest { list ->
                _state.update { it.copy(blocks = list) }
            }
        }
        // Refaz a query do Calendar a cada mudança nas prefs (toggle on/off,
        // seleção de calendários, override de evento). Roda em paralelo com
        // o observer de blocos manuais.
        //
        // .conflate(): observeChanges emite uma vez por chave mudada no
        // SharedPreferences. Quando o usuário toggla várias configs em rajada,
        // sem conflate disparávamos loadCalendarBlocks (acessa CalendarContract
        // — relativamente caro) uma vez por chave; com conflate, processamos
        // só a última emissão de cada burst.
        viewModelScope.launch {
            sessionPrefs.observeChanges().conflate().collect {
                loadCalendarBlocks(_state.value.calendarMonth)
            }
        }
    }

    private suspend fun loadCalendarBlocks(month: YearMonth) {
        val today = LocalDate.now()
        // Cobre a vista calendário (mês visível) + a lista (14 dias à frente).
        val rangeStart = minOf(month.atDay(1), today)
        val rangeEnd = maxOf(month.atEndOfMonth(), today.plusDays(13))

        val raw = calendarEventRepository.getBlocksInRange(rangeStart, rangeEnd)
        val mapped: Map<LocalDate, List<DisplayBlock>> = raw.mapValues { (_, blocks) ->
            blocks.map { virtual ->
                DisplayBlock(
                    block = virtual,
                    source = BlockSource.CALENDAR,
                    // virtual.id é -eventId (ver CalendarEventRepositoryImpl).
                    calendarEventId = -virtual.id,
                )
            }
        }
        _state.update { it.copy(calendarBlocksByDate = mapped) }
    }

    /** Re-fetch eventos do calendar — chame ao retornar à tela (ON_RESUME). */
    fun refreshCalendar() {
        viewModelScope.launch { loadCalendarBlocks(_state.value.calendarMonth) }
    }

    // ── Dialog ───────────────────────────────────────────────────

    fun openCreateDialog() {
        openCreateDialogForDate(LocalDate.now())
    }

    /**
     * Abre o dialog de criação pré-preenchido com a data informada.
     * Usado quando o usuário toca em um dia do calendário:
     * - dialogDate = data clicada (relevante para Recurrence.NONE)
     * - dialogDayOfMonth = dia do mês da data (pré-seleção para MONTHLY)
     * - dialogWeekDays = dia da semana da data (pré-seleção para WEEKLY)
     * - dialogAllDay = false (usuário ativa via Switch se quiser)
     */
    fun openCreateDialogForDate(date: LocalDate) {
        _state.update {
            it.copy(
                showDialog = true,
                editingBlock = null,
                dialogTitle = "",
                dialogStartHour = 12,
                dialogStartMinute = 0,
                dialogEndHour = 13,
                dialogEndMinute = 0,
                dialogRecurrence = Recurrence.NONE,
                dialogWeekDays = setOf(date.dayOfWeek),
                dialogDate = date,
                dialogDayOfMonth = date.dayOfMonth,
                dialogAllDay = false,
            )
        }
    }

    fun openEditDialog(block: InactivityBlock) {
        _state.update {
            it.copy(
                showDialog = true,
                editingBlock = block,
                dialogTitle = block.title,
                dialogStartHour = block.startTime.hour,
                dialogStartMinute = block.startTime.minute,
                dialogEndHour = block.endTime.hour,
                dialogEndMinute = block.endTime.minute,
                dialogRecurrence = block.recurrence,
                dialogWeekDays = block.recurrenceDays,
                dialogDate = block.specificDate ?: LocalDate.now(),
                dialogDayOfMonth = block.dayOfMonth ?: 1,
                dialogAllDay = block.isFullDay(),
            )
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(showDialog = false) }
    }

    fun updateTitle(v: String) = _state.update { it.copy(dialogTitle = v) }
    fun updateStartTime(h: Int, m: Int) = _state.update { it.copy(dialogStartHour = h, dialogStartMinute = m) }
    fun updateEndTime(h: Int, m: Int) = _state.update { it.copy(dialogEndHour = h, dialogEndMinute = m) }
    fun updateRecurrence(r: Recurrence) = _state.update { it.copy(dialogRecurrence = r) }
    fun updateDate(d: LocalDate) = _state.update { it.copy(dialogDate = d) }
    fun updateDayOfMonth(d: Int) = _state.update { it.copy(dialogDayOfMonth = d.coerceIn(1, 31)) }

    fun toggleWeekDay(day: DayOfWeek) {
        _state.update { s ->
            val current = s.dialogWeekDays
            s.copy(dialogWeekDays = if (day in current) current - day else current + day)
        }
    }

    fun setAllDay(allDay: Boolean) {
        _state.update { it.copy(dialogAllDay = allDay) }
    }

    // ── Persistência ─────────────────────────────────────────────

    fun saveBlock() {
        val s = _state.value

        // Quando "Dia inteiro" está ativo, sobrescreve os campos de hora com 00:00→23:59.
        val startTime = if (s.dialogAllDay) {
            InactivityBlock.FULL_DAY_START
        } else {
            LocalTime.of(s.dialogStartHour, s.dialogStartMinute)
        }
        val endTime = if (s.dialogAllDay) {
            InactivityBlock.FULL_DAY_END
        } else {
            LocalTime.of(s.dialogEndHour, s.dialogEndMinute)
        }

        val block = InactivityBlock(
            id = s.editingBlock?.id ?: 0,
            title = s.dialogTitle.trim().ifBlank { if (s.dialogAllDay) "Indisponível" else "Bloco" },
            startTime = startTime,
            endTime = endTime,
            specificDate = if (s.dialogRecurrence == Recurrence.NONE) s.dialogDate else null,
            recurrence = s.dialogRecurrence,
            recurrenceDays = if (s.dialogRecurrence == Recurrence.WEEKLY) s.dialogWeekDays else emptySet(),
            dayOfMonth = if (s.dialogRecurrence == Recurrence.MONTHLY) s.dialogDayOfMonth else null,
        )

        viewModelScope.launch {
            if (s.editingBlock != null) repository.update(block) else repository.insert(block)
            _state.update { it.copy(showDialog = false) }
        }
    }

    fun deleteBlock(block: InactivityBlock) {
        viewModelScope.launch { repository.delete(block) }
    }

    // ── Calendário ───────────────────────────────────────────────

    fun selectTab(tab: ScheduleTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun goToPreviousMonth() {
        val newMonth = _state.value.calendarMonth.minusMonths(1)
        _state.update { it.copy(calendarMonth = newMonth) }
        viewModelScope.launch { loadCalendarBlocks(newMonth) }
    }

    fun goToNextMonth() {
        val newMonth = _state.value.calendarMonth.plusMonths(1)
        _state.update { it.copy(calendarMonth = newMonth) }
        viewModelScope.launch { loadCalendarBlocks(newMonth) }
    }

    fun goToToday() {
        val newMonth = YearMonth.now()
        _state.update { it.copy(calendarMonth = newMonth) }
        viewModelScope.launch { loadCalendarBlocks(newMonth) }
    }

    // ── Personalizar evento do Calendar ──────────────────────────

    /** Abre o diálogo de confirmação para clonar um evento do Calendar como bloco manual. */
    fun requestPersonalize(target: DisplayBlock) {
        _state.update { it.copy(personalizeTarget = target) }
    }

    fun dismissPersonalize() {
        _state.update { it.copy(personalizeTarget = null) }
    }

    /**
     * Clona o evento do Calendar em [InactivityBlock] manual com os mesmos
     * horários e adiciona o `eventId` à blacklist. O próximo refresh deixa de
     * importar este evento, evitando duplicação.
     */
    fun confirmPersonalize() {
        val target = _state.value.personalizeTarget ?: return
        val source = target.block
        val eventId = target.calendarEventId ?: return

        viewModelScope.launch {
            val cloned = InactivityBlock(
                id = 0,
                title = source.title.ifBlank { "Personalizado" },
                startTime = source.startTime,
                endTime = source.endTime,
                specificDate = source.specificDate,
                recurrence = Recurrence.NONE,
            )
            repository.insert(cloned)
            sessionPrefs.addCalendarOverriddenEventId(eventId)
            _state.update { it.copy(personalizeTarget = null) }
        }
    }
}
