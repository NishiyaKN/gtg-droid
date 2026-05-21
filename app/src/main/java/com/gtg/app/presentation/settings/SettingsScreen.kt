package com.gtg.app.presentation.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtg.app.R
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.repository.CalendarInfo
import com.gtg.app.presentation.alarm.AlarmSoundPlayer
import com.gtg.app.presentation.common.AdaptiveText
import com.gtg.app.presentation.common.WheelTimePicker
import com.gtg.app.presentation.theme.GtgError
import com.gtg.app.presentation.theme.GtgPrimary
import com.gtg.app.presentation.theme.GtgSuccess
import com.gtg.app.presentation.theme.GtgSurface
import com.gtg.app.presentation.theme.GtgSurfaceVariant
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

    // Re-checa permissão READ_CALENDAR ao voltar do system Settings ou após
    // o launcher de permissão. Sem isso, o estado "permissão concedida"
    // ficaria preso até troca de tela.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshCalendarPermissionAndCalendars()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_subtitle),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(20.dp))

        ActivityWindowSection(
            startHour = state.windowStartHour,
            startMinute = state.windowStartMinute,
            endHour = state.windowEndHour,
            endMinute = state.windowEndMinute,
            isConfigured = state.currentWindow != null,
            isDirty = state.windowDirty,
            error = state.windowError,
            onStartChange = viewModel::updateWindowStart,
            onEndChange = viewModel::updateWindowEnd,
            onSave = viewModel::saveWindow,
        )

        Spacer(modifier = Modifier.height(16.dp))

        ActiveDaysSection(
            active = state.activeDaysOfWeek,
            onToggle = viewModel::toggleActiveDay,
        )

        Spacer(modifier = Modifier.height(16.dp))

        BaseIntervalSection(
            minutes = state.baseIntervalMinutes,
            onChange = viewModel::setBaseInterval,
        )

        Spacer(modifier = Modifier.height(16.dp))

        DailyTargetSection(
            enabled = state.showDailyTarget,
            onToggle = viewModel::setShowDailyTarget,
            target = state.dailySetTarget,
            onChange = viewModel::setDailyTarget,
        )

        Spacer(modifier = Modifier.height(16.dp))

        BypassDndSection(
            enabled = state.bypassDnd,
            onChange = viewModel::setBypassDnd,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OvershootRepeatSection(
            enabled = state.overshootRepeatEnabled,
            intervalMinutes = state.overshootRepeatMinutes,
            onToggle = viewModel::setOvershootEnabled,
            onIntervalChange = viewModel::setOvershootInterval,
        )

        Spacer(modifier = Modifier.height(16.dp))

        CalendarIntegrationSection(
            enabled = state.calendarEnabled,
            permissionGranted = state.calendarPermissionGranted,
            availableCalendars = state.availableCalendars,
            selectedIds = state.calendarSelectedIds,
            showTitles = state.calendarShowTitles,
            onToggleEnabled = viewModel::setCalendarEnabled,
            onToggleCalendar = viewModel::toggleCalendarSelected,
            onToggleShowTitles = viewModel::setCalendarShowTitles,
            onRefreshPermission = viewModel::refreshCalendarPermissionAndCalendars,
        )

        Spacer(modifier = Modifier.height(16.dp))

        LanguageSection(
            currentTag = viewModel.currentLanguageTag,
            onSelect = viewModel::setLanguage,
        )

        Spacer(modifier = Modifier.height(16.dp))

        SoundSection(
            title = state.alarmSoundTitle,
            isCustom = state.isCustomSound,
            currentUri = state.alarmSoundUri,
            bypassDnd = state.bypassDnd,
            onPick = viewModel::setAlarmSound,
            onReset = viewModel::resetAlarmSound,
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ── Janela de Atividade ──────────────────────────────────────────

@Composable
private fun ActivityWindowSection(
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    isConfigured: Boolean,
    isDirty: Boolean,
    error: String?,
    onStartChange: (Int, Int) -> Unit,
    onEndChange: (Int, Int) -> Unit,
    onSave: () -> Unit,
) {
    SectionCard(
        icon = Icons.Default.Schedule,
        title = stringResource(R.string.settings_activity_window_title),
        description = stringResource(R.string.settings_activity_window_description),
    ) {
        if (!isConfigured) {
            Text(
                text = stringResource(R.string.settings_activity_window_unset),
                color = GtgError.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            WheelTimePicker(
                label = stringResource(R.string.settings_time_start),
                hour = startHour,
                minute = startMinute,
                onChange = onStartChange,
            )
            Text(
                text = "→",
                color = Color.White.copy(alpha = 0.5f),
                fontWeight = FontWeight.Bold,
            )
            WheelTimePicker(
                label = stringResource(R.string.settings_time_end),
                hour = endHour,
                minute = endMinute,
                onChange = onEndChange,
            )
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = GtgError,
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSave,
            enabled = isDirty || !isConfigured,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = GtgPrimary,
                contentColor = Color.White,
                disabledContainerColor = GtgSurfaceVariant,
                disabledContentColor = Color.White.copy(alpha = 0.4f),
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            val label = stringResource(
                when {
                    !isConfigured -> R.string.settings_window_save
                    isDirty -> R.string.settings_window_save_changes
                    else -> R.string.settings_window_saved
                },
            )
            if (!isDirty && isConfigured) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = GtgSuccess,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Dias da semana ativos ────────────────────────────────────────

@Composable
private fun ActiveDaysSection(
    active: Set<java.time.DayOfWeek>,
    onToggle: (java.time.DayOfWeek) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    SectionCard(
        icon = Icons.Default.Schedule,
        title = stringResource(R.string.settings_active_days_title),
        description = stringResource(R.string.settings_active_days_description),
    ) {
        // 7 chips de 36dp em 320dp - padding (40dp screen + 32dp card) ≈ 248dp
        // úteis. 7×36 = 252dp já estoura, daí o chip "S" pode clipar.
        // Reduzimos para 30dp em narrow para garantir folga.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 320.dp
            val chipSize = if (narrow) 30.dp else 36.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                java.time.DayOfWeek.entries.forEach { day ->
                    val selected = day in active
                    Box(
                        modifier = Modifier
                            .size(chipSize)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (selected) GtgPrimary else GtgSurfaceVariant)
                            .clickable { onToggle(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = day.getDisplayName(
                                java.time.format.TextStyle.NARROW,
                                locale,
                            ),
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = if (narrow) 11.sp else 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

// ── Intervalo Base ───────────────────────────────────────────────

@Composable
private fun BaseIntervalSection(
    minutes: Long,
    onChange: (Long) -> Unit,
) {
    // Valor local seguido durante o drag — persiste só ao soltar.
    // `remember(minutes)` reseta se o valor persistido mudar externamente.
    var draft by remember(minutes) { mutableFloatStateOf(minutes.toFloat()) }

    SectionCard(
        icon = Icons.Default.Alarm,
        title = stringResource(R.string.settings_base_interval_title),
        description = stringResource(
            R.string.settings_base_interval_description,
            SettingsViewModel.MIN_BASE_INTERVAL.toInt(),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_current_label),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
            Text(
                text = stringResource(R.string.settings_min_value, draft.roundToInt()),
                color = GtgPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onChange(draft.roundToInt().toLong()) },
            valueRange = SettingsViewModel.MIN_BASE_INTERVAL.toFloat()..
                SettingsViewModel.MAX_BASE_INTERVAL.toFloat(),
            // Step de 5 min: (240 - 20) / 5 - 1 = 43
            steps = ((SettingsViewModel.MAX_BASE_INTERVAL -
                SettingsViewModel.MIN_BASE_INTERVAL) / 5 - 1).toInt(),
            colors = SliderDefaults.colors(
                thumbColor = GtgPrimary,
                activeTrackColor = GtgPrimary,
                inactiveTrackColor = GtgSurfaceVariant,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(
                    R.string.settings_min_value,
                    SettingsViewModel.MIN_BASE_INTERVAL.toInt(),
                ),
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 11.sp,
            )
            Text(
                text = stringResource(
                    R.string.settings_min_value,
                    SettingsViewModel.MAX_BASE_INTERVAL.toInt(),
                ),
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 11.sp,
            )
        }
    }
}

// ── Meta Diária ──────────────────────────────────────────────────

@Composable
private fun DailyTargetSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    target: Int,
    onChange: (Int) -> Unit,
) {
    SectionCard(
        icon = Icons.Default.Repeat,
        title = stringResource(R.string.settings_daily_target_title),
        description = stringResource(R.string.settings_daily_target_description),
    ) {
        // Quando OFF, esconde valor + slider via AnimatedVisibility (sem visual
        // jump no scroll denso de Settings).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_daily_target_show_label),
                color = Color.White,
                fontSize = 14.sp,
            )
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = GtgPrimary,
                    checkedTrackColor = GtgPrimary.copy(alpha = 0.4f),
                ),
            )
        }

        AnimatedVisibility(visible = enabled) {
            // `draft` mora DENTRO do AnimatedVisibility: ao reabrir após toggle
            // OFF→ON, `remember(target)` re-inicializa com o `target` atual
            // (sem stale value de drag interrompido).
            var draft by remember(target) { mutableFloatStateOf(target.toFloat()) }

            Column {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_current_label),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                    )
                    Text(
                        text = stringResource(R.string.settings_daily_target_value, draft.roundToInt()),
                        color = GtgPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Slider(
                    value = draft,
                    onValueChange = { draft = it },
                    onValueChangeFinished = { onChange(draft.roundToInt()) },
                    valueRange = SettingsViewModel.MIN_DAILY_TARGET.toFloat()..
                        SettingsViewModel.MAX_DAILY_TARGET.toFloat(),
                    steps = SettingsViewModel.MAX_DAILY_TARGET -
                        SettingsViewModel.MIN_DAILY_TARGET - 1,
                    colors = SliderDefaults.colors(
                        thumbColor = GtgPrimary,
                        activeTrackColor = GtgPrimary,
                        inactiveTrackColor = GtgSurfaceVariant,
                    ),
                )
            }
        }
    }
}

// ── Som do Alarme ────────────────────────────────────────────────

@Composable
private fun SoundSection(
    title: String,
    isCustom: Boolean,
    currentUri: String?,
    bypassDnd: Boolean,
    onPick: (Uri?) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current

    // Toggle do preview. Ringtones nativos do tipo TYPE_ALARM tocam em loop —
    // sem este controle o som ficava infinito até trocar de tela.
    var isPreviewing by remember { mutableStateOf(false) }

    // Sincroniza o toggle com o estado real da Ringtone:
    // - sons curtos param sozinhos → zera quando isPlaying vira false;
    // - timeout de 10s como fallback se o som nunca iniciou ou loopa indefinidamente.
    // Espera observar isPlaying=true ao menos uma vez antes de aceitar isPlaying=false
    // como "terminou", pois há um pequeno delay até a Ringtone começar.
    LaunchedEffect(isPreviewing) {
        if (!isPreviewing) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        var observedPlaying = false
        while (true) {
            delay(200)
            val playing = AlarmSoundPlayer.isPlaying
            if (playing) observedPlaying = true
            val finished = observedPlaying && !playing
            val timedOut = System.currentTimeMillis() - startedAt > 10_000
            if (finished || timedOut) {
                AlarmSoundPlayer.stop()
                isPreviewing = false
                return@LaunchedEffect
            }
        }
    }

    // Para o som ao sair da tela — evita que preview fique tocando se o
    // usuário sair sem parar manualmente.
    DisposableEffect(Unit) {
        onDispose { AlarmSoundPlayer.stop() }
    }

    // Launcher para o picker nativo de ringtones do sistema.
    // Devolve a Uri selecionada (ou null se o usuário escolheu "Nenhum"/cancelou).
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java,
                )
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            onPick(selectedUri)
        }
    }

    SectionCard(
        icon = Icons.Default.MusicNote,
        title = stringResource(R.string.settings_sound_title),
        description = stringResource(R.string.settings_sound_description),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AdaptiveText(
                    text = title,
                    color = GtgPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (isCustom) R.string.settings_sound_custom
                        else R.string.settings_sound_default,
                    ),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }

            // Preview — toggle Play/Stop. Toca o som com as mesmas configs do alarme real.
            IconButton(
                onClick = {
                    if (isPreviewing) {
                        AlarmSoundPlayer.stop()
                        isPreviewing = false
                    } else {
                        val uri = currentUri?.let(Uri::parse)
                        AlarmSoundPlayer.play(
                            context = context,
                            soundUri = uri,
                            bypassDnd = bypassDnd,
                        )
                        isPreviewing = true
                    }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (isPreviewing) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = stringResource(
                        if (isPreviewing) R.string.settings_sound_stop_test
                        else R.string.settings_sound_test,
                    ),
                    tint = GtgPrimary,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { pickerLauncher.launch(buildRingtonePickerIntent(context, currentUri)) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GtgPrimary,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_sound_choose),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (isCustom) {
                Button(
                    onClick = onReset,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GtgSurfaceVariant,
                        contentColor = Color.White.copy(alpha = 0.8f),
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.settings_sound_use_default),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

/**
 * Constrói o Intent para o picker nativo de ringtones.
 *
 * - TYPE_ALARM | TYPE_RINGTONE | TYPE_NOTIFICATION: cobre os 3 tipos para que
 *   o usuário escolha qualquer som disponível no celular.
 * - SHOW_DEFAULT = true: opção "Som padrão" no topo.
 * - SHOW_SILENT = false: alarme silencioso não faz sentido nesse app.
 * - EXISTING_URI: pré-seleciona o som atual na lista.
 */
private fun buildRingtonePickerIntent(context: android.content.Context, currentUri: String?): Intent =
    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(
            RingtoneManager.EXTRA_RINGTONE_TYPE,
            RingtoneManager.TYPE_ALARM or
                RingtoneManager.TYPE_RINGTONE or
                RingtoneManager.TYPE_NOTIFICATION,
        )
        putExtra(
            RingtoneManager.EXTRA_RINGTONE_TITLE,
            context.getString(R.string.settings_sound_picker_title),
        )
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        putExtra(
            RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        )
        currentUri?.let {
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
        }
    }

// ── Integração com Google Calendar ───────────────────────────────

@Composable
private fun CalendarIntegrationSection(
    enabled: Boolean,
    permissionGranted: Boolean,
    availableCalendars: List<CalendarInfo>,
    selectedIds: Set<Long>,
    showTitles: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleCalendar: (Long) -> Unit,
    onToggleShowTitles: (Boolean) -> Unit,
    onRefreshPermission: () -> Unit,
) {
    val context = LocalContext.current

    // Launcher para solicitar READ_CALENDAR. Ao receber resposta, dispara
    // refresh para o ViewModel atualizar `permissionGranted` e carregar a
    // lista de calendários (que depende da permissão).
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        onRefreshPermission()
    }

    SectionCard(
        icon = Icons.Default.CalendarMonth,
        title = stringResource(R.string.settings_calendar_title),
        description = stringResource(R.string.settings_calendar_description),
    ) {
        // ── Toggle principal ─────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (enabled) R.string.settings_enabled else R.string.settings_disabled,
                    ),
                    color = if (enabled) GtgPrimary else Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (enabled) R.string.settings_calendar_on_description
                        else R.string.settings_calendar_off_description,
                    ),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { newValue ->
                    if (newValue && !permissionGranted) {
                        // Tenta solicitar a permissão antes de ativar de fato —
                        // se o usuário negar, o toggle volta a OFF via observer.
                        permissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
                    }
                    onToggleEnabled(newValue)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = GtgPrimary,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                    uncheckedTrackColor = GtgSurfaceVariant,
                    uncheckedBorderColor = GtgSurfaceVariant,
                ),
            )
        }

        if (!enabled) return@SectionCard

        // ── Estado de permissão ──────────────────────────────────
        if (!permissionGranted) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = GtgError.copy(alpha = 0.12f),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_calendar_no_permission),
                        color = GtgError,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_calendar_no_permission_desc),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings
                                        .ACTION_APPLICATION_DETAILS_SETTINGS,
                                ).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                },
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GtgPrimary,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_calendar_open_settings),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            return@SectionCard
        }

        // ── Lista de calendários ─────────────────────────────────
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_calendars_header),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (availableCalendars.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_calendar_none_synced),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
            )
        } else {
            availableCalendars.forEachIndexed { index, info ->
                if (index > 0) Spacer(modifier = Modifier.height(4.dp))
                CalendarPickerRow(
                    info = info,
                    selected = info.id in selectedIds,
                    onToggle = { onToggleCalendar(info.id) },
                )
            }
        }

        // ── Toggle de privacidade ────────────────────────────────
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_hide_calendar_titles),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (showTitles) R.string.settings_hide_calendar_titles_on
                        else R.string.settings_hide_calendar_titles_off,
                    ),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = !showTitles,
                onCheckedChange = { onToggleShowTitles(!it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = GtgPrimary,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                    uncheckedTrackColor = GtgSurfaceVariant,
                    uncheckedBorderColor = GtgSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun CalendarPickerRow(
    info: CalendarInfo,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = GtgPrimary,
                uncheckedColor = Color.White.copy(alpha = 0.5f),
            ),
        )
        // Dot da cor do calendar — referência visual ao app nativo.
        // OR com 0xFF000000 garante alpha opaco mesmo se o calendar persistir
        // a cor sem o canal alpha (ocorre com alguns providers).
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(info.colorArgb or 0xFF000000.toInt())),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            AdaptiveText(
                text = info.displayName,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (info.accountName.isNotBlank() && info.accountName != info.displayName) {
                AdaptiveText(
                    text = info.accountName,
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

// ── Idioma ───────────────────────────────────────────────────────

@Composable
private fun LanguageSection(
    currentTag: String,
    onSelect: (String) -> Unit,
) {
    SectionCard(
        icon = Icons.Default.Language,
        title = stringResource(R.string.settings_language_title),
        description = stringResource(R.string.settings_language_description),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LanguageChip(
                label = stringResource(R.string.language_english),
                selected = currentTag.startsWith("en"),
                onClick = { onSelect("en") },
                modifier = Modifier.weight(1f),
            )
            LanguageChip(
                label = stringResource(R.string.language_portuguese),
                selected = currentTag.startsWith("pt"),
                onClick = { onSelect("pt-BR") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LanguageChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) GtgPrimary else GtgSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ── Re-alerta automático (Overshoot) ─────────────────────────────

@Composable
private fun OvershootRepeatSection(
    enabled: Boolean,
    intervalMinutes: Int,
    onToggle: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit,
) {
    var draft by remember(intervalMinutes) { mutableFloatStateOf(intervalMinutes.toFloat()) }

    SectionCard(
        icon = Icons.Default.NotificationsActive,
        title = stringResource(R.string.settings_overshoot_title),
        description = stringResource(R.string.settings_overshoot_description),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (enabled) R.string.settings_enabled else R.string.settings_disabled,
                    ),
                    color = if (enabled) GtgPrimary else Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (enabled) {
                        stringResource(
                            R.string.settings_overshoot_on_description,
                            draft.roundToInt(),
                        )
                    } else {
                        stringResource(R.string.settings_overshoot_off_description)
                    },
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = GtgPrimary,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                    uncheckedTrackColor = GtgSurfaceVariant,
                    uncheckedBorderColor = GtgSurfaceVariant,
                ),
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_overshoot_interval_label),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
                Text(
                    text = stringResource(R.string.settings_min_value, draft.roundToInt()),
                    color = GtgPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = { onIntervalChange(draft.roundToInt()) },
                valueRange = SessionPreferences.MIN_OVERSHOOT_MINUTES.toFloat()..
                    SessionPreferences.MAX_OVERSHOOT_MINUTES.toFloat(),
                // Steps de 1 minuto: (15 - 1) - 1 = 13 (descontando os endpoints)
                steps = SessionPreferences.MAX_OVERSHOOT_MINUTES -
                    SessionPreferences.MIN_OVERSHOOT_MINUTES - 1,
                colors = SliderDefaults.colors(
                    thumbColor = GtgPrimary,
                    activeTrackColor = GtgPrimary,
                    inactiveTrackColor = GtgSurfaceVariant,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        R.string.settings_min_value,
                        SessionPreferences.MIN_OVERSHOOT_MINUTES,
                    ),
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 11.sp,
                )
                Text(
                    text = stringResource(
                        R.string.settings_min_value,
                        SessionPreferences.MAX_OVERSHOOT_MINUTES,
                    ),
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

// ── Ignorar Não Perturbe ─────────────────────────────────────────

@Composable
private fun BypassDndSection(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SectionCard(
        icon = Icons.Default.DoNotDisturbOn,
        title = stringResource(R.string.settings_dnd_title),
        description = stringResource(R.string.settings_dnd_description),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (enabled) R.string.settings_enabled else R.string.settings_disabled,
                    ),
                    color = if (enabled) GtgPrimary else Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (enabled) R.string.settings_dnd_on_description
                        else R.string.settings_dnd_off_description,
                    ),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = GtgPrimary,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                    uncheckedTrackColor = GtgSurfaceVariant,
                    uncheckedBorderColor = GtgSurfaceVariant,
                ),
            )
        }
    }
}

// ── Componentes auxiliares ───────────────────────────────────────

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GtgSurface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = GtgPrimary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    AdaptiveText(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    AdaptiveText(
                        text = description,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        maxLines = 2,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            content()
        }
    }
}


