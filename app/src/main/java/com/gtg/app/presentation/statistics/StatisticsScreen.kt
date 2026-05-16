package com.gtg.app.presentation.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gtg.app.R
import com.gtg.app.domain.model.ExerciseBreakdown
import com.gtg.app.presentation.common.AdaptiveText
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.GtgSurface
import com.gtg.app.presentation.theme.GtgSurfaceVariant

@Composable
fun StatisticsScreen(viewModel: StatisticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.statistics_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Cards de resumo (3 períodos) ────────────────────
        // `height(IntrinsicSize.Max)` no Row + `fillMaxHeight()` em cada card
        // garante que os 3 cards tenham a MESMA altura, igual ao maior conteúdo.
        // Sem isso, "0" (1 char) e "204" (3 chars) geram alturas distintas
        // mesmo com `weight(1f)` igualando a largura.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val repsLabel = stringResource(R.string.home_reps)
            SummaryCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = stringResource(R.string.statistics_today),
                primaryValue = formatReps(state.todayReps),
                primaryLabel = repsLabel,
                secondaryValue = stringResource(R.string.home_sets_count_format, state.todaySets),
            )
            SummaryCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = stringResource(R.string.statistics_week),
                primaryValue = formatReps(state.weekReps),
                primaryLabel = repsLabel,
                secondaryValue = stringResource(R.string.home_sets_count_format, state.weekSets),
            )
            SummaryCard(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                title = stringResource(R.string.statistics_month),
                primaryValue = formatReps(state.monthReps),
                primaryLabel = repsLabel,
                secondaryValue = stringResource(R.string.home_sets_count_format, state.monthSets),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Gráfico de barras: últimos 7 dias ────────────────
        Text(
            text = stringResource(R.string.statistics_last_7_days),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = GtgSurface),
            shape = RoundedCornerShape(16.dp),
        ) {
            // heightIn ao invés de height fixo: em telas pequenas (altura util
                // ~400dp), 200dp era 50% da tela inteira só pro gráfico. Faixa
                // 160..240 ajusta visualmente em phones pequenos e tablets.
            WeeklyBarChart(
                data = state.last7Days,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 240.dp)
                    .padding(16.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Detalhamento por exercício (período selecionável) ─
        Text(
            text = stringResource(R.string.statistics_by_exercise),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Spacer(modifier = Modifier.height(12.dp))

        PeriodSelector(
            selected = state.selectedPeriod,
            onSelect = viewModel::selectPeriod,
        )

        Spacer(modifier = Modifier.height(12.dp))

        BreakdownCard(
            items = state.breakdown,
            period = state.selectedPeriod,
        )

        Spacer(modifier = Modifier.height(80.dp)) // espaço para BottomNav
    }
}

// ── Period Selector ──────────────────────────────────────────────

@Composable
private fun PeriodSelector(
    selected: StatsPeriod,
    onSelect: (StatsPeriod) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GtgSurface)
            .padding(4.dp),
    ) {
        StatsPeriod.entries.forEach { period ->
            PeriodChip(
                label = periodLabel(period),
                selected = selected == period,
                onClick = { onSelect(period) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PeriodChip(
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

@Composable
private fun periodLabel(period: StatsPeriod): String = stringResource(
    when (period) {
        StatsPeriod.TODAY -> R.string.statistics_period_today
        StatsPeriod.WEEK -> R.string.statistics_period_week
        StatsPeriod.MONTH -> R.string.statistics_period_month
    },
)

// ── Breakdown Card ───────────────────────────────────────────────

@Composable
private fun BreakdownCard(
    items: List<ExerciseBreakdown>,
    period: StatsPeriod,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GtgSurface),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when (period) {
                            StatsPeriod.TODAY -> R.string.statistics_empty_today
                            StatsPeriod.WEEK -> R.string.statistics_empty_week
                            StatsPeriod.MONTH -> R.string.statistics_empty_month
                        },
                    ),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(modifier = Modifier.padding(16.dp)) {
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = GtgSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    ExerciseRow(item)
                }
            }
        }
    }
}

// ── Componentes ──────────────────────────────────────────────────

@Composable
private fun SummaryCard(
    modifier: Modifier = Modifier,
    title: String,
    primaryValue: String,
    primaryLabel: String,
    secondaryValue: String,
) {
    // Layout vertical: TITLE / valor / "reps" / sets count. Stacked impede
    // o label "reps" de disputar largura com o número, eliminando o clipping
    // do "s" em telas estreitas. O fontSize do valor ainda escala com a
    // contagem de caracteres — mas agora apenas para preservar hierarquia
    // visual e não como remediação de overflow.
    //
    // formatReps abrevia milhares ("1.2k", "12k"), então `primaryValue.length`
    // não passa de 5 chars na prática (ex: "999k", "9.9k"). O escalonamento
    // abaixo cobre essa faixa em qualquer largura razoável de tela.
    val primaryFontSize = when (primaryValue.length) {
        1, 2 -> 36.sp
        3 -> 32.sp
        4 -> 26.sp
        else -> 22.sp
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = GtgSurface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                maxLines = 1,
            )
            Text(
                text = primaryValue,
                color = GtgPrimary,
                fontSize = primaryFontSize,
                fontWeight = FontWeight.Bold,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = primaryLabel,
                color = GtgPrimary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                softWrap = false,
                maxLines = 1,
            )
            Text(
                text = secondaryValue,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

/**
 * "534" / "1.2k" / "12k" — abrevia milhares para evitar overflow em
 * larguras apertadas (3 cards por linha).
 */
private fun formatReps(reps: Int): String = when {
    reps < 1000 -> reps.toString()
    reps % 1000 == 0 -> "${reps / 1000}k"
    reps < 10_000 -> "%.1fk".format(java.util.Locale.US, reps / 1000.0)
    else -> "${reps / 1000}k"
}

@Composable
private fun ExerciseRow(item: ExerciseBreakdown) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AdaptiveText(
                text = item.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${item.sets} sets",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 12.sp,
            )
        }
        Text(
            text = "${item.totalReps}",
            color = GtgPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "reps",
            color = GtgPrimary.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
    }
}

// ── Gráfico de barras via Canvas ─────────────────────────────────

/**
 * 7 barras verticais desenhadas inteiramente via Canvas nativo do Compose.
 * Sem dependências externas.
 *
 * Layout:
 * - Área inferior (20dp) reservada para labels (Seg, Ter, ...)
 * - Área superior: barras proporcionais ao max
 * - Valor numérico acima de cada barra
 */
@Composable
private fun WeeklyBarChart(
    data: List<DailyBar>,
    modifier: Modifier = Modifier,
) {
    val barColor = GtgPrimary
    val emptyColor = GtgSurfaceVariant
    val textColor = android.graphics.Color.argb(180, 255, 255, 255)
    val labelColor = android.graphics.Color.argb(120, 255, 255, 255)
    val maxReps = data.maxOfOrNull { it.totalReps }?.coerceAtLeast(1) ?: 1

    Canvas(modifier = modifier) {
        val barCount = data.size
        if (barCount == 0) return@Canvas

        val labelAreaHeight = 24.dp.toPx()
        val valueAreaHeight = 18.dp.toPx()
        val chartHeight = size.height - labelAreaHeight - valueAreaHeight
        val slotWidth = size.width / barCount
        val barWidth = slotWidth * 0.55f
        val cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())

        val valuePaint = android.graphics.Paint().apply {
            color = textColor
            textSize = 11.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val labelPaint = android.graphics.Paint().apply {
            color = labelColor
            textSize = 11.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        data.forEachIndexed { index, bar ->
            val centerX = slotWidth * index + slotWidth / 2
            val barLeft = centerX - barWidth / 2

            val ratio = bar.totalReps.toFloat() / maxReps
            val barHeight = (chartHeight * ratio).coerceAtLeast(if (bar.totalReps > 0) 4.dp.toPx() else 0f)

            val barTop = valueAreaHeight + chartHeight - barHeight
            val color = if (bar.totalReps > 0) barColor else emptyColor

            // Barra
            drawRoundRect(
                color = color,
                topLeft = Offset(barLeft, barTop),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
            )

            // Se sem dados, desenha placeholder fino
            if (bar.totalReps == 0) {
                drawRoundRect(
                    color = emptyColor,
                    topLeft = Offset(barLeft, valueAreaHeight + chartHeight - 3.dp.toPx()),
                    size = Size(barWidth, 3.dp.toPx()),
                    cornerRadius = cornerRadius,
                )
            }

            // Valor numérico acima da barra
            if (bar.totalReps > 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "${bar.totalReps}",
                    centerX,
                    barTop - 4.dp.toPx(),
                    valuePaint,
                )
            }

            // Label do dia abaixo
            drawContext.canvas.nativeCanvas.drawText(
                bar.label,
                centerX,
                size.height,
                labelPaint,
            )
        }
    }
}
