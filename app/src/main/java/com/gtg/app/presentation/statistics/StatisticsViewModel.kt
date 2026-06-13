package com.gtg.app.presentation.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtg.app.domain.model.ExerciseBreakdown
import com.gtg.app.domain.repository.ExerciseLogRepository
import com.gtg.app.domain.usecase.GetExerciseBreakdownUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject

// ── Models ───────────────────────────────────────────────────────

data class DailyBar(
    val date: LocalDate,
    val label: String,   // ex: "Seg", "Ter"
    val totalReps: Int,
)

/**
 * Período selecionado para o detalhamento por exercício.
 * [TODAY] / [WEEK] (segunda da semana corrente → hoje) / [MONTH] (dia 1 do mês → hoje).
 */
enum class StatsPeriod {
    TODAY,
    WEEK,
    MONTH,
}

data class StatisticsUiState(
    val todayReps: Int = 0,
    val todaySets: Int = 0,
    val weekReps: Int = 0,
    val weekSets: Int = 0,
    val monthReps: Int = 0,
    val monthSets: Int = 0,
    val last7Days: List<DailyBar> = emptyList(),
    val selectedPeriod: StatsPeriod = StatsPeriod.TODAY,
    /** Breakdown do período em [selectedPeriod]. */
    val breakdown: List<ExerciseBreakdown> = emptyList(),
)

// ── ViewModel ────────────────────────────────────────────────────

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val logRepository: ExerciseLogRepository,
    private val getExerciseBreakdown: GetExerciseBreakdownUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(StatisticsUiState())
    val state: StateFlow<StatisticsUiState> = _state.asStateFlow()

    init {
        // Re-calcula stats quando novos logs são inseridos (Flow reativo do
        // Room). observeCount: o payload era descartado — só o tick importa.
        viewModelScope.launch {
            logRepository.observeCount().collectLatest {
                loadStats()
            }
        }
    }

    /** Troca o período do detalhamento por exercício. */
    fun selectPeriod(period: StatsPeriod) {
        if (_state.value.selectedPeriod == period) return
        _state.update { it.copy(selectedPeriod = period) }
        viewModelScope.launch { reloadBreakdown() }
    }

    private suspend fun loadStats() {
        val today = LocalDate.now()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monthStart = today.withDayOfMonth(1)
        val locale = Locale.getDefault()
        val (breakdownStart, breakdownEnd) = boundsFor(_state.value.selectedPeriod, today)

        // 14 queries Room independentes: 6 totais (3 periodos x 2 metricas) +
        // 7 do grafico semanal + 1 breakdown. Sequencial pagava soma de
        // latencias. coroutineScope/async dispatcha tudo em paralelo —
        // SQLite serializa reads internamente mas o wall-time observado pelo
        // viewmodel cai materialmente (~2-4x em devices reais).
        coroutineScope {
            val todayRepsDef = async { logRepository.totalRepsBetween(today, today) }
            val todaySetsDef = async { logRepository.totalSetsBetween(today, today) }
            val weekRepsDef = async { logRepository.totalRepsBetween(weekStart, today) }
            val weekSetsDef = async { logRepository.totalSetsBetween(weekStart, today) }
            val monthRepsDef = async { logRepository.totalRepsBetween(monthStart, today) }
            val monthSetsDef = async { logRepository.totalSetsBetween(monthStart, today) }
            val breakdownDef = async { getExerciseBreakdown(breakdownStart, breakdownEnd) }

            val last7DaysDefs = (6 downTo 0).map { daysAgo ->
                val date = today.minusDays(daysAgo.toLong())
                date to async { logRepository.totalRepsBetween(date, date) }
            }

            val last7Days = last7DaysDefs.map { (date, def) ->
                DailyBar(
                    date = date,
                    label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    totalReps = def.await(),
                )
            }

            _state.update {
                it.copy(
                    todayReps = todayRepsDef.await(),
                    todaySets = todaySetsDef.await(),
                    weekReps = weekRepsDef.await(),
                    weekSets = weekSetsDef.await(),
                    monthReps = monthRepsDef.await(),
                    monthSets = monthSetsDef.await(),
                    last7Days = last7Days,
                    breakdown = breakdownDef.await(),
                )
            }
        }
    }

    /** Recalcula apenas o breakdown quando o período muda (mais barato que loadStats completo). */
    private suspend fun reloadBreakdown() {
        val today = LocalDate.now()
        val (start, end) = boundsFor(_state.value.selectedPeriod, today)
        val breakdown = getExerciseBreakdown(start, end)
        _state.update { it.copy(breakdown = breakdown) }
    }

    private fun boundsFor(period: StatsPeriod, today: LocalDate): Pair<LocalDate, LocalDate> =
        when (period) {
            StatsPeriod.TODAY -> today to today
            StatsPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
            StatsPeriod.MONTH -> today.withDayOfMonth(1) to today
        }
}
