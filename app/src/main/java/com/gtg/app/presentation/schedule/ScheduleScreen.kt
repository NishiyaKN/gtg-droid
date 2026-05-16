package com.gtg.app.presentation.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoNotDisturb
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.Recurrence
import com.gtg.app.presentation.common.WheelTimePicker
import com.gtg.app.presentation.theme.GtgError
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.GtgSurface
import com.gtg.app.presentation.theme.GtgSurfaceBright
import com.gtg.app.presentation.theme.GtgSurfaceVariant
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun ScheduleScreen(viewModel: ScheduleViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Re-busca eventos do Calendar ao retornar à tela. Cobre o caso em que o
    // usuário cria/edita um evento no app nativo do Calendar e volta ao GtG.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshCalendar()
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::openCreateDialog,
                containerColor = GtgPrimary,
                contentColor = Color.White,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar bloco")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Agenda de Inatividade",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Períodos em que você não será notificado.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
            )

            Spacer(modifier = Modifier.height(16.dp))

            ScheduleTabSelector(
                selected = state.selectedTab,
                onSelect = viewModel::selectTab,
            )

            Spacer(modifier = Modifier.height(16.dp))

            when (state.selectedTab) {
                ScheduleTab.CALENDAR -> CalendarMonthView(
                    month = state.calendarMonth,
                    blocks = state.blocks,
                    calendarBlocksByDate = state.calendarBlocksByDate,
                    onPrevMonth = viewModel::goToPreviousMonth,
                    onNextMonth = viewModel::goToNextMonth,
                    onToday = viewModel::goToToday,
                    onDayClick = viewModel::openCreateDialogForDate,
                )
                ScheduleTab.LIST -> {
                    val combined = remember(state.blocks, state.calendarBlocksByDate) {
                        buildList {
                            state.blocks.forEach {
                                add(DisplayBlock(it, BlockSource.MANUAL))
                            }
                            state.calendarBlocksByDate
                                .toSortedMap()
                                .values
                                .forEach { addAll(it) }
                        }
                    }
                    if (combined.isEmpty()) {
                        EmptySchedule()
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 80.dp),
                        ) {
                            items(combined, key = { item ->
                                // ID positivo do Room para manuais, eventId
                                // (também positivo) com offset para evitar colisão.
                                when (item.source) {
                                    BlockSource.MANUAL -> "m-${item.block.id}"
                                    BlockSource.CALENDAR ->
                                        "c-${item.calendarEventId}-${item.block.specificDate}"
                                }
                            }) { item ->
                                BlockCard(
                                    block = item.block,
                                    source = item.source,
                                    onEdit = {
                                        when (item.source) {
                                            BlockSource.MANUAL ->
                                                viewModel.openEditDialog(item.block)
                                            BlockSource.CALENDAR ->
                                                viewModel.requestPersonalize(item)
                                        }
                                    },
                                    onDelete = {
                                        when (item.source) {
                                            BlockSource.MANUAL ->
                                                viewModel.deleteBlock(item.block)
                                            BlockSource.CALENDAR ->
                                                viewModel.requestPersonalize(item)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showDialog) {
        BlockDialog(state = state, viewModel = viewModel)
    }

    state.personalizeTarget?.let { target ->
        PersonalizeCalendarDialog(
            target = target,
            onConfirm = viewModel::confirmPersonalize,
            onDismiss = viewModel::dismissPersonalize,
        )
    }
}

// ── Tab Selector ─────────────────────────────────────────────────

@Composable
private fun ScheduleTabSelector(
    selected: ScheduleTab,
    onSelect: (ScheduleTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GtgSurface)
            .padding(4.dp),
    ) {
        TabChip(
            label = "Calendário",
            selected = selected == ScheduleTab.CALENDAR,
            onClick = { onSelect(ScheduleTab.CALENDAR) },
            modifier = Modifier.weight(1f),
        )
        TabChip(
            label = "Lista",
            selected = selected == ScheduleTab.LIST,
            onClick = { onSelect(ScheduleTab.LIST) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) GtgPrimary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ── Calendário ───────────────────────────────────────────────────

@Composable
private fun CalendarMonthView(
    month: YearMonth,
    blocks: List<InactivityBlock>,
    calendarBlocksByDate: Map<LocalDate, List<DisplayBlock>>,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    val locale = Locale("pt", "BR")
    val today = LocalDate.now()
    val titleFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Header: mês + navegação ──────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevMonth, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = "Mês anterior",
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToday),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = month.format(titleFormatter).replaceFirstChar { it.uppercase(locale) },
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = onNextMonth, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Próximo mês",
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Header dos dias da semana (Seg → Dom) ────────────
        Row(modifier = Modifier.fillMaxWidth()) {
            val weekdays = DayOfWeek.entries // MONDAY .. SUNDAY
            weekdays.forEach { day ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = day.getDisplayName(TextStyle.NARROW, locale).uppercase(locale),
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Grid de 6x7 células (cobre qualquer mês) ─────────
        // Inicia na segunda-feira da semana que contém o dia 1 do mês.
        val firstOfMonth = month.atDay(1)
        val mondayOffset = (firstOfMonth.dayOfWeek.value - DayOfWeek.MONDAY.value)
            .let { if (it < 0) it + 7 else it }
        val gridStart = firstOfMonth.minusDays(mondayOffset.toLong())

        repeat(6) { rowIndex ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { colIndex ->
                    val date = gridStart.plusDays((rowIndex * 7 + colIndex).toLong())
                    val hasCalendar = calendarBlocksByDate[date]?.isNotEmpty() == true
                    DayCell(
                        date = date,
                        inCurrentMonth = date.month == month.month && date.year == month.year,
                        isToday = date == today,
                        hasFullDayBlock = hasFullDayMarker(blocks, date),
                        hasPartialBlock = hasPartialMarker(blocks, date) || hasCalendar,
                        modifier = Modifier.weight(1f),
                        onClick = { onDayClick(date) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Legenda ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendItem(color = GtgError.copy(alpha = 0.7f), label = "Dia inteiro indisponível")
            LegendItem(color = GtgPrimary, label = "Bloco parcial", dotOnly = true)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Toque em um dia para criar um bloco — dia inteiro, intervalo " +
                "específico, ou recorrência baseada nessa data.",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
    }
}

/** Existe um bloco NONE full-day para esta data? */
private fun hasFullDayMarker(blocks: List<InactivityBlock>, date: LocalDate): Boolean =
    blocks.any { b ->
        b.recurrence.name == "NONE" &&
            b.specificDate == date &&
            b.isFullDay()
    }

/** Existe algum bloco ativo no dia que NÃO seja full-day NONE? */
private fun hasPartialMarker(blocks: List<InactivityBlock>, date: LocalDate): Boolean =
    blocks.any { b ->
        b.isActiveOn(date) && !(b.recurrence.name == "NONE" && b.isFullDay())
    }

@Composable
private fun DayCell(
    date: LocalDate,
    inCurrentMonth: Boolean,
    isToday: Boolean,
    hasFullDayBlock: Boolean,
    hasPartialBlock: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val containerColor = when {
        hasFullDayBlock -> GtgError.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    val borderColor = if (isToday) GtgPrimary else Color.Transparent
    val textColor = when {
        !inCurrentMonth -> Color.White.copy(alpha = 0.18f)
        hasFullDayBlock -> Color.White
        else -> Color.White.copy(alpha = 0.85f)
    }

    Box(
        modifier = modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${date.dayOfMonth}",
                color = textColor,
                fontSize = 14.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
            )
            // Dot indicador de bloco parcial quando NÃO há full-day
            if (hasPartialBlock && !hasFullDayBlock) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(GtgPrimary),
                )
            }
        }
        // Indicador visual de "hoje" via borda inferior (sem usar Modifier.border
        // para evitar import extra — desenhamos via Spacer no fundo)
        if (isToday && !hasFullDayBlock) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
                    .size(width = 12.dp, height = 2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(borderColor),
            )
        }
    }
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
    dotOnly: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(if (dotOnly) 6.dp else 12.dp)
                .clip(if (dotOnly) CircleShape else RoundedCornerShape(3.dp))
                .background(color),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
        )
    }
}

// ── Card de bloco ────────────────────────────────────────────────

@Composable
private fun BlockCard(
    block: InactivityBlock,
    source: BlockSource,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCalendar = source == BlockSource.CALENDAR
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = GtgSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isCalendar) {
                    Icons.Default.CalendarMonth
                } else {
                    Icons.Default.DoNotDisturb
                },
                contentDescription = null,
                tint = if (isCalendar) GtgPrimary else GtgError.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp),
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = block.title.ifBlank { if (isCalendar) "Ocupado" else "Bloco" },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCalendar) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CALENDAR",
                            color = GtgPrimary.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(GtgPrimary.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatTimeRange(block),
                    color = GtgPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isCalendar) {
                        block.specificDate?.let { d ->
                            "%02d/%02d/%d".format(d.dayOfMonth, d.monthValue, d.year)
                        } ?: "Importado do Calendar"
                    } else {
                        formatRecurrence(block)
                    },
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isCalendar) Icons.Default.Edit else Icons.Default.Delete,
                    contentDescription = if (isCalendar) "Personalizar" else "Deletar",
                    tint = if (isCalendar) {
                        GtgPrimary.copy(alpha = 0.6f)
                    } else {
                        GtgError.copy(alpha = 0.5f)
                    },
                )
            }
        }
    }
}

@Composable
private fun PersonalizeCalendarDialog(
    target: DisplayBlock,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GtgSurface,
        title = {
            Text(
                "Personalizar bloqueio?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                Text(
                    text = target.block.title.ifBlank { "Ocupado" },
                    color = GtgPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimeRange(target.block) + " · " +
                        (target.block.specificDate?.toString() ?: ""),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Vamos clonar este evento como um bloqueio editável. " +
                        "O evento do Google Calendar deixará de ser sincronizado " +
                        "automaticamente, e mudanças futuras lá não vão afetar este.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Personalizar", color = GtgPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = Color.White.copy(alpha = 0.5f))
            }
        },
    )
}

private fun formatTimeRange(block: InactivityBlock): String {
    val start = "%02d:%02d".format(block.startTime.hour, block.startTime.minute)
    val end = "%02d:%02d".format(block.endTime.hour, block.endTime.minute)
    return "$start — $end"
}

private fun formatRecurrence(block: InactivityBlock): String = when (block.recurrence) {
    Recurrence.NONE -> block.specificDate?.toString() ?: "Uma vez"
    Recurrence.DAILY -> "Todos os dias"
    Recurrence.WEEKLY -> {
        val days = block.recurrenceDays
            .sortedBy { it.value }
            .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale("pt", "BR")) }
        "Semanal: $days"
    }
    Recurrence.MONTHLY -> "Mensal: dia ${block.dayOfMonth}"
}

@Composable
private fun EmptySchedule() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CalendarMonth,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Nenhum bloco de inatividade", color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Toque no + para adicionar.", color = Color.White.copy(alpha = 0.3f), fontSize = 14.sp)
    }
}

// ── Dialog de criação/edição ─────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockDialog(state: ScheduleUiState, viewModel: ScheduleViewModel) {
    val isEditing = state.editingBlock != null
    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = GtgPrimary,
        unfocusedBorderColor = GtgSurfaceVariant,
        focusedLabelColor = GtgPrimary,
        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
        cursorColor = GtgPrimary,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
    )

    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        containerColor = GtgSurface,
        title = {
            Text(
                if (isEditing) "Editar Bloco" else "Novo Bloco",
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                // Título
                OutlinedTextField(
                    value = state.dialogTitle,
                    onValueChange = viewModel::updateTitle,
                    label = { Text("Título (ex: Reunião)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tfColors,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── Toggle "Dia inteiro" ─────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dia inteiro",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "00:00 → 23:59",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = state.dialogAllDay,
                        onCheckedChange = viewModel::setAllDay,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = GtgPrimary,
                            uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                            uncheckedTrackColor = GtgSurfaceVariant,
                            uncheckedBorderColor = GtgSurfaceVariant,
                        ),
                    )
                }

                // ── Horários (só visíveis se não for dia inteiro) ──
                if (!state.dialogAllDay) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        WheelTimePicker("Início", state.dialogStartHour, state.dialogStartMinute) { h, m ->
                            viewModel.updateStartTime(h, m)
                        }
                        Text(
                            text = "→",
                            color = Color.White.copy(alpha = 0.5f),
                            fontWeight = FontWeight.Bold,
                        )
                        WheelTimePicker("Fim", state.dialogEndHour, state.dialogEndMinute) { h, m ->
                            viewModel.updateEndTime(h, m)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Recorrência ──────────────────────────────────
                Text("Recorrência", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Recurrence.entries.forEach { rec ->
                        FilterChip(
                            selected = state.dialogRecurrence == rec,
                            onClick = { viewModel.updateRecurrence(rec) },
                            label = { Text(recurrenceLabel(rec), fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = GtgPrimary.copy(alpha = 0.2f),
                                selectedLabelColor = GtgPrimary,
                                containerColor = GtgSurfaceVariant,
                                labelColor = Color.White.copy(alpha = 0.6f),
                            ),
                        )
                    }
                }

                // Helper text contextualizado: descreve em linguagem natural o que
                // a seleção atual de recorrência fará, baseado em dialogDate.
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = recurrenceHelper(state),
                    color = GtgPrimary.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                )

                // Campos condicionais por tipo de recorrência
                when (state.dialogRecurrence) {
                    Recurrence.WEEKLY -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Dias da semana", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DayOfWeek.entries.forEach { day ->
                                val selected = day in state.dialogWeekDays
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) GtgPrimary else GtgSurfaceVariant)
                                        .clickable { viewModel.toggleWeekDay(day) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = day.getDisplayName(TextStyle.NARROW, Locale("pt", "BR")),
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                    Recurrence.MONTHLY -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = state.dialogDayOfMonth.toString(),
                            onValueChange = { v ->
                                v.filter { it.isDigit() }.toIntOrNull()?.let { viewModel.updateDayOfMonth(it) }
                            },
                            label = { Text("Dia do mês (1-31)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(150.dp),
                            colors = tfColors,
                        )
                    }
                    else -> { /* NONE e DAILY não precisam de campos extras */ }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::saveBlock) {
                Text("Salvar", color = GtgPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissDialog) {
                Text("Cancelar", color = Color.White.copy(alpha = 0.5f))
            }
        },
    )
}


private fun recurrenceLabel(r: Recurrence): String = when (r) {
    Recurrence.NONE -> "Uma vez"
    Recurrence.DAILY -> "Diário"
    Recurrence.WEEKLY -> "Semanal"
    Recurrence.MONTHLY -> "Mensal"
}

/**
 * Texto explicativo dinâmico baseado em [ScheduleUiState.dialogDate] e na
 * recorrência selecionada. Ex: "Marca toda quarta-feira" quando WEEKLY +
 * data clicada é uma quarta.
 */
private fun recurrenceHelper(state: ScheduleUiState): String {
    val locale = Locale("pt", "BR")
    val date = state.dialogDate
    return when (state.dialogRecurrence) {
        Recurrence.NONE -> {
            val dayLabel = date.dayOfWeek
                .getDisplayName(TextStyle.FULL, locale)
                .replaceFirstChar { it.lowercase(locale) }
            "Marca apenas $dayLabel, ${date.dayOfMonth}/${date.monthValue}/${date.year}."
        }
        Recurrence.DAILY -> "Marca todos os dias."
        Recurrence.WEEKLY -> {
            val days = state.dialogWeekDays
                .sortedBy { it.value }
                .joinToString(", ") {
                    it.getDisplayName(TextStyle.FULL, locale)
                        .replaceFirstChar { c -> c.lowercase(locale) }
                }
            if (days.isBlank()) "Selecione ao menos um dia da semana." else "Marca toda $days."
        }
        Recurrence.MONTHLY -> "Marca todo dia ${state.dialogDayOfMonth} do mês."
    }
}
