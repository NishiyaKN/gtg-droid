package com.gtg.app.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gtg.app.R
import com.gtg.app.domain.model.ExerciseBreakdown
import com.gtg.app.domain.usecase.PlannedSet
import com.gtg.app.presentation.common.AdaptiveText
import com.gtg.app.presentation.common.AutoShrinkText
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.countdownDisplay
import com.gtg.app.presentation.theme.GtgSuccess
import com.gtg.app.presentation.theme.GtgSurface
import com.gtg.app.presentation.theme.GtgSurfaceVariant

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Computado uma vez por recomposição — usado tanto pelo Crossfade quanto
    // pela condicional do RoutinePreviewCard. Sem extrair, era invocado 2× no
    // mesmo escopo. Pure function sem side effects, mas extração explicita
    // que os dois reads referem-se à mesma classificação.
    val screenState = resolveScreenState(state)
    val snackbarHostState = remember { SnackbarHostState() }

    val snackbarMessage = stringResource(R.string.home_window_snackbar)
    LaunchedEffect(state.noWindowConfigured) {
        if (state.noWindowConfigured) {
            snackbarHostState.showSnackbar(snackbarMessage)
            viewModel.dismissNoWindowWarning()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // LazyColumn (era Column + verticalScroll). Column forçava medir
        // todos os filhos no primeiro frame — em sessão ativa com 12
        // PreviewRows + N BreakdownRows isso somava ~30 children medidos
        // antes de qualquer pixel aparecer. LazyColumn mede só itens
        // visíveis. Spacing entre items via Arrangement.spacedBy preserva
        // a hierarquia de 24dp entre seções.
        //
        // Items mantêm key estável — sem isso, recomposições "rebuild"
        // o item (perde scroll position, anima de novo as transições
        // internas). RoutinePreviewCard é condicional (some quando
        // NO_EXERCISE), e a key estável permite ao LazyColumn manejar
        // entrada/saída sem replays.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 16.dp,
                // 40dp = 16 da inset + 24 que era o Spacer final, para
                // preservar o respiro do BottomNav que a Column tinha.
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // ── Dashboard no topo ───────────────────────────────
            // Card condicional ao toggle "Mostrar meta diária" em Settings.
            // Quando OFF, o item nem entra na LazyColumn — sem reserva de
            // espaço, sem visual jump no scroll.
            if (state.showDailyTarget) {
                item(key = "daily_summary") {
                    DailySummaryCard(
                        setsCompleted = state.todaySetsCompleted,
                        totalReps = state.todayTotalReps,
                        dailyTarget = state.dailySetTarget,
                        breakdown = state.todayBreakdown,
                    )
                }
            }

            // ── Conteúdo central: muda conforme estado ──────────
            // Crossfade 120ms (era AnimatedContent fade 300ms). Encurtado para
            // não empilhar com o fade do NavHost (140ms) — dois fades de 300ms
            // somavam ~440ms perceptual ao entrar na Home logo após
            // start/stop session, dando sensação de "lento". 120ms mantém a
            // transição visualmente, mas dentro do envelope snappy.
            item(key = "screen_state_content") {
                Crossfade(
                    targetState = screenState,
                    animationSpec = tween(durationMillis = 120),
                    label = "home_content",
                    modifier = Modifier.fillMaxWidth(),
                ) { screenState ->
                    when (screenState) {
                        ScreenState.NO_EXERCISE -> NoExerciseContent()
                        ScreenState.IDLE -> IdleContent(
                            hasActivityWindow = state.hasActivityWindow,
                            onStart = viewModel::startSession,
                        )
                        ScreenState.COUNTDOWN -> CountdownContent(
                            exerciseName = state.pendingExerciseName,
                            targetReps = state.pendingTargetReps,
                            remainingSeconds = state.remainingSeconds,
                            baseIntervalMinutes = state.baseIntervalMinutes,
                            canCheck = state.canCheck,
                            isOverdue = state.isOverdue,
                            isAlarmRinging = state.isAlarmPending,
                            chainStartedAtMillis = state.chainStartedAtMillis,
                            chainElapsedSeconds = state.chainElapsedSeconds,
                            onManualCheck = viewModel::performManualCheck,
                            onDismissAlarm = viewModel::dismissAlarm,
                            onStop = viewModel::stopSession,
                        )
                    }
                }
            }

            // ── Preview da rotina (esconde durante cadeia ativa — foco
            // visual fica no contador crescente e na decisão Check/continuar
            // adiando). Fade-in/out 120ms casa com o Crossfade do conteúdo
            // central acima, evitando layout-shift abrupto. ──
            if (state.routinePreview.isNotEmpty() &&
                screenState != ScreenState.NO_EXERCISE
            ) {
                item(key = "routine_preview") {
                    AnimatedVisibility(
                        visible = state.chainStartedAtMillis == null,
                        enter = fadeIn(animationSpec = tween(120)),
                        exit = fadeOut(animationSpec = tween(120)),
                    ) {
                        RoutinePreviewCard(
                            preview = state.routinePreview,
                            isSessionActive = state.isSessionActive,
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Preview da rotina dinâmica
// ──────────────────────────────────────────────────────────────────

/**
 * Lista compacta dos próximos sets projetados para o dia.
 *
 * O primeiro item, quando há sessão ativa, é o alarme REAL agendado.
 * Os demais são projeções calculadas assumindo "Check no horário exato".
 * Por isso a UI rotula como "projeção" e enfatiza visualmente o item real.
 */
@Composable
private fun RoutinePreviewCard(
    preview: List<PlannedSet>,
    isSessionActive: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GtgSurface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (isSessionActive) R.string.home_routine_today
                        else R.string.home_routine_projection,
                    ),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    text = "${preview.size}",
                    color = GtgPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.home_routine_disclaimer),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))

            preview.forEachIndexed { index, set ->
                if (index > 0) Spacer(modifier = Modifier.height(8.dp))
                PreviewRow(set = set)
            }
        }
    }
}

@Composable
private fun PreviewRow(set: PlannedSet) {
    val timeText = "%02d:%02d".format(set.time.hour, set.time.minute)
    val timeColor = if (set.isScheduled) GtgPrimary else Color.White.copy(alpha = 0.85f)
    val accent = if (set.isScheduled) GtgPrimary else Color.White.copy(alpha = 0.6f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Indicador visual: dot cheio para alarme real, vazado para projeção
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (set.isScheduled) GtgPrimary else GtgSurfaceVariant,
                    shape = CircleShape,
                ),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = timeText,
            color = timeColor,
            fontSize = 15.sp,
            fontWeight = if (set.isScheduled) FontWeight.Bold else FontWeight.SemiBold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )

        Spacer(modifier = Modifier.width(12.dp))

        AdaptiveText(
            text = set.exerciseName,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = "${set.targetReps}",
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = stringResource(R.string.home_reps),
            color = accent.copy(alpha = 0.7f),
            fontSize = 11.sp,
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Estado lógico da tela
// ──────────────────────────────────────────────────────────────────

private enum class ScreenState {
    NO_EXERCISE,
    IDLE,
    COUNTDOWN,
}

private fun resolveScreenState(state: HomeUiState): ScreenState = when {
    state.currentExercise == null -> ScreenState.NO_EXERCISE
    !state.isSessionActive -> ScreenState.IDLE
    else -> ScreenState.COUNTDOWN
}

// ──────────────────────────────────────────────────────────────────
// Componentes
// ──────────────────────────────────────────────────────────────────

/**
 * Card de resumo diário no topo: sets concluídos + reps totais + barra de progresso.
 */
@Composable
private fun DailySummaryCard(
    setsCompleted: Int,
    totalReps: Int,
    dailyTarget: Int,
    breakdown: List<ExerciseBreakdown>,
) {
    val progress = if (dailyTarget > 0) {
        (setsCompleted.toFloat() / dailyTarget).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GtgSurface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_summary_today),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                AdaptiveText(
                    text = stringResource(
                        R.string.home_sets_completed_format,
                        setsCompleted,
                        dailyTarget,
                    ),
                    color = if (setsCompleted >= dailyTarget) GtgSuccess else GtgPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = if (setsCompleted >= dailyTarget) GtgSuccess else GtgPrimary,
                trackColor = GtgSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(label = stringResource(R.string.home_sets), value = "$setsCompleted")
                StatItem(label = stringResource(R.string.home_reps_total), value = "$totalReps")
                StatItem(label = stringResource(R.string.home_volume), value = "$totalReps")
            }

            // Detalhamento por exercício — só renderiza se houver dados.
            // Mantém a Card limpa quando o dia ainda não começou.
            if (breakdown.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = GtgSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.home_breakdown_title),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                breakdown.forEachIndexed { index, item ->
                    if (index > 0) Spacer(modifier = Modifier.height(6.dp))
                    BreakdownRow(item)
                }
            }
        }
    }
}

/**
 * Linha compacta por exercício no dashboard da Home.
 * Formato: "Nome · 3 sets" à esquerda, "15 reps" em destaque à direita.
 */
@Composable
private fun BreakdownRow(item: ExerciseBreakdown) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdaptiveText(
            text = item.name,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.home_sets_count_format, item.sets),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "${item.totalReps}",
            color = GtgPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = stringResource(R.string.home_reps),
            color = GtgPrimary.copy(alpha = 0.6f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
    }
}

// ── Estado: Nenhum exercício configurado ─────────────────────────

@Composable
private fun NoExerciseContent() {
    // fillMaxWidth: sem isso o Column pega só a largura intrínseca dos filhos
    // e horizontalAlignment.CenterHorizontally só centraliza dentro dessa
    // largura mínima — o resultado é o bloco inteiro encostado à esquerda
    // do container pai (LazyColumn item).
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.FitnessCenter,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_no_exercise_title),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_no_exercise_subtitle),
            color = Color.White.copy(alpha = 0.3f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Estado: Sessão parada (Idle) ─────────────────────────────────

@Composable
private fun IdleContent(
    hasActivityWindow: Boolean,
    onStart: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = Icons.Outlined.Timer,
            contentDescription = null,
            tint = GtgPrimary.copy(alpha = if (hasActivityWindow) 0.6f else 0.25f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(
                if (hasActivityWindow) R.string.home_ready_to_train
                else R.string.home_configure_first,
            ),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )

        if (!hasActivityWindow) {
            Spacer(modifier = Modifier.height(16.dp))
            // Alerta inline persistente — substitui o snackbar volátil
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = GtgPrimary.copy(alpha = 0.12f),
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = GtgPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.home_window_not_configured_title),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.home_window_not_configured_desc),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStart,
            enabled = hasActivityWindow,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GtgPrimary,
                contentColor = Color.White,
                disabledContainerColor = GtgSurfaceVariant,
                disabledContentColor = Color.White.copy(alpha = 0.4f),
            ),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.home_start_session),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ── Estado: Contagem regressiva ──────────────────────────────────

@Composable
private fun CountdownContent(
    exerciseName: String,
    targetReps: Int,
    remainingSeconds: Long,
    baseIntervalMinutes: Long,
    canCheck: Boolean,
    isOverdue: Boolean,
    isAlarmRinging: Boolean,
    chainStartedAtMillis: Long?,
    chainElapsedSeconds: Long,
    onManualCheck: () -> Unit,
    onDismissAlarm: () -> Unit,
    onStop: () -> Unit,
) {
    val totalSeconds = baseIntervalMinutes * 60
    val progress = if (totalSeconds > 0) {
        1f - (remainingSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
    } else {
        0f
    }

    // Estados visuais distintos:
    // - inChain && isAlarmRinging → card vermelho + pulse + label "ADIADO" Bold
    //   (cadeia ativa COM alarme tocando AGORA — retém urgência).
    // - inChain && !isAlarmRinging → card normal + sem pulse + label "ADIADO"
    //   Medium (usuário já silenciou/snoozou — chain blue calm).
    // - !inChain && isOverdue → estado overdue legacy (red pulse + "HORA DO GTG").
    // - !inChain && !isOverdue → countdown normal.
    val inChain = chainStartedAtMillis != null
    val urgentChain = inChain && isAlarmRinging
    val pulseActive = urgentChain || (!inChain && isOverdue)

    val infiniteTransition = rememberInfiniteTransition(label = "overdue_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulseActive) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "overdue_pulse_scale",
    )

    val accentColor = if (pulseActive || inChain) GtgPrimary else Color.White

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Status acima do nome do exercício
        val statusRes = when {
            inChain -> R.string.home_chain_label_paused
            isOverdue -> R.string.home_gtg_time
            remainingSeconds <= 60 -> R.string.home_almost_there
            else -> R.string.home_next_exercise
        }
        val emphasizeStatus = inChain || isOverdue
        Text(
            text = stringResource(statusRes),
            color = if (emphasizeStatus) GtgPrimary else Color.White.copy(alpha = 0.5f),
            fontSize = if (emphasizeStatus) 16.sp else 14.sp,
            fontWeight = when {
                urgentChain -> FontWeight.Bold
                inChain -> FontWeight.Medium // calmChain — diferencia de overdue Bold
                isOverdue -> FontWeight.Bold
                else -> FontWeight.Normal
            },
            letterSpacing = if (emphasizeStatus) 2.sp else 0.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = exerciseName,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_target_reps_format, targetReps),
            color = GtgPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Card central com contador (regressivo ou crescente)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .scale(pulseScale),
            colors = CardDefaults.cardColors(
                containerColor = if (pulseActive) {
                    GtgPrimary.copy(alpha = 0.12f)
                } else {
                    GtgSurface
                },
            ),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Em cadeia → contador crescente "+MM:SS" desde o primeiro
                // alarme da cadeia. Fora de cadeia → countdown regressivo
                // "MM:SS" (ou "-MM:SS" em overdue legacy).
                AutoShrinkText(
                    text = if (inChain) {
                        formatCounter(chainElapsedSeconds)
                    } else {
                        formatCountdown(remainingSeconds)
                    },
                    style = MaterialTheme.typography.countdownDisplay,
                    minFontSize = 32.sp,
                    color = accentColor,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Secondary "atrasado" só no estado overdue LEGACY (não em
                // cadeia — o label "ADIADO" acima já comunica o estado).
                if (isOverdue && !inChain) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_overdue),
                        color = GtgPrimary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Progresso só no countdown regressivo (positivo, sem chain).
                if (!isOverdue && !inChain) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = GtgPrimary,
                        trackColor = GtgSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Botão Check — habilitado sempre durante cadeia dentro da janela,
        // ou (sem cadeia) na janela de 5min antes / em overdue.
        Button(
            onClick = onManualCheck,
            enabled = canCheck,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .let { if (pulseActive) it.scale(pulseScale) else it },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GtgPrimary,
                contentColor = Color.White,
                disabledContainerColor = GtgSurfaceVariant,
                disabledContentColor = Color.White.copy(alpha = 0.4f),
            ),
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (isOverdue || inChain) R.string.home_do_check else R.string.home_do_check_now,
                ),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Dica visual quando o check ainda está bloqueado
        if (!canCheck) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_check_unlocks),
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
            )
        }

        // Silenciar — só aparece enquanto o som do alarme está tocando.
        // NÃO faz Check nem Skip: o timer continua rodando, exercício pendente
        // segue igual. O usuário só está dispensando o som/notificação.
        if (isAlarmRinging) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onDismissAlarm,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = GtgPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_silence_no_check),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Botão Parar (único secundário restante)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            OutlinedButton(
                onClick = onStop,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White.copy(alpha = 0.4f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = stringResource(R.string.home_stop), fontSize = 14.sp)
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────

/**
 * Formata segundos decorridos da cadeia em "+MM:SS" ou "+HH:MM:SS" se > 1 hora.
 *
 * Sempre positivo — `coerceAtLeast(0L)` defende contra clock skew / NTP
 * adjustment se algum caller passar negativo. Delega a [formatCountdown]
 * para o decompose hh/mm/ss e só prefixa o sinal.
 */
private fun formatCounter(elapsedSeconds: Long): String =
    "+" + formatCountdown(elapsedSeconds.coerceAtLeast(0L))

/**
 * Formata segundos em "MM:SS" ou "HH:MM:SS" se > 1 hora.
 * Para valores negativos (overdue) prefixa com "-" e usa o valor absoluto.
 */
private fun formatCountdown(totalSeconds: Long): String {
    val absSeconds = kotlin.math.abs(totalSeconds)
    val sign = if (totalSeconds < 0) "-" else ""

    val hours = absSeconds / 3600
    val minutes = (absSeconds % 3600) / 60
    val seconds = absSeconds % 60

    return if (hours > 0) {
        "$sign%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "$sign%02d:%02d".format(minutes, seconds)
    }
}
