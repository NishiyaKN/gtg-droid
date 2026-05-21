package com.gtg.app.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persiste o estado volátil da sessão GtG via SharedPreferences.
 *
 * Este estado não pertence ao Room porque é efêmero (próximo alarme, sessão ativa)
 * e precisa ser acessível sincronamente pelo [AlarmScheduler] e [BootReceiver].
 *
 * Expõe um [Flow] reativo via [SharedPreferences.OnSharedPreferenceChangeListener]
 * para que o [HomeViewModel] observe mudanças feitas pelo [AlarmViewModel] em outra Activity.
 */
@Singleton
class SessionPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "gtg_session"
        private const val KEY_IS_SESSION_ACTIVE = "is_session_active"
        private const val KEY_NEXT_ALARM_MILLIS = "next_alarm_millis"
        private const val KEY_PENDING_EXERCISE_ID = "pending_exercise_id"
        private const val KEY_PENDING_EXERCISE_NAME = "pending_exercise_name"
        private const val KEY_PENDING_TARGET_REPS = "pending_target_reps"
        private const val KEY_BASE_INTERVAL_MINUTES = "base_interval_minutes"
        private const val KEY_DAILY_SET_TARGET = "daily_set_target"
        private const val KEY_SHOW_DAILY_TARGET = "show_daily_target"
        private const val KEY_IS_ALARM_PENDING = "is_alarm_pending"
        private const val KEY_BYPASS_DND = "bypass_dnd"
        private const val KEY_ALARM_SOUND_URI = "alarm_sound_uri"
        private const val KEY_LAST_CHECK_MILLIS = "last_check_millis"
        private const val KEY_OVERSHOOT_ENABLED = "overshoot_repeat_enabled"
        private const val KEY_OVERSHOOT_MINUTES = "overshoot_repeat_minutes"
        private const val KEY_CALENDAR_ENABLED = "calendar_integration_enabled"
        private const val KEY_CALENDAR_SELECTED_IDS = "calendar_selected_ids"
        private const val KEY_CALENDAR_SHOW_TITLES = "calendar_show_titles"
        private const val KEY_CALENDAR_OVERRIDDEN_IDS = "calendar_overridden_event_ids"
        private const val KEY_ACTIVE_DAYS_OF_WEEK = "active_days_of_week"
        private const val KEY_LANGUAGE_TAG = "language_tag"

        const val DEFAULT_BASE_INTERVAL = 45L
        const val DEFAULT_DAILY_SET_TARGET = 10
        const val DEFAULT_SHOW_DAILY_TARGET = false
        const val DEFAULT_BYPASS_DND = true
        const val DEFAULT_OVERSHOOT_ENABLED = true
        const val DEFAULT_OVERSHOOT_MINUTES = 5
        const val MIN_OVERSHOOT_MINUTES = 1
        const val MAX_OVERSHOOT_MINUTES = 15
        const val DEFAULT_CALENDAR_ENABLED = false
        const val DEFAULT_CALENDAR_SHOW_TITLES = true
    }

    // ── Leitura ──────────────────────────────────────────────────

    val isSessionActive: Boolean
        get() = prefs.getBoolean(KEY_IS_SESSION_ACTIVE, false)

    /** Epoch millis do próximo alarme agendado. 0 se nenhum. */
    val nextAlarmMillis: Long
        get() = prefs.getLong(KEY_NEXT_ALARM_MILLIS, 0L)

    val pendingExerciseId: Long
        get() = prefs.getLong(KEY_PENDING_EXERCISE_ID, -1L)

    val pendingExerciseName: String
        get() = prefs.getString(KEY_PENDING_EXERCISE_NAME, "") ?: ""

    val pendingTargetReps: Int
        get() = prefs.getInt(KEY_PENDING_TARGET_REPS, 0)

    val baseIntervalMinutes: Long
        get() = prefs.getLong(KEY_BASE_INTERVAL_MINUTES, DEFAULT_BASE_INTERVAL)

    val dailySetTarget: Int
        get() = prefs.getInt(KEY_DAILY_SET_TARGET, DEFAULT_DAILY_SET_TARGET)

    /**
     * Controla se o card "Daily Summary" aparece na Home e se o campo de
     * `dailySetTarget` é exposto em Settings. Default `false` — meta diária
     * é opcional. O valor de [dailySetTarget] permanece persistido
     * independente deste toggle (toggle ON religa com último valor).
     */
    val showDailyTarget: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DAILY_TARGET, DEFAULT_SHOW_DAILY_TARGET)

    /** true quando o alarme disparou e aguarda o Check do usuário. */
    val isAlarmPending: Boolean
        get() = prefs.getBoolean(KEY_IS_ALARM_PENDING, false)

    /**
     * Se o alarme deve usar o canal de notificação que ignora o Do Not Disturb.
     * Default = true (preserva o comportamento original do app — alarme estilo
     * "wake me up" que toca mesmo em modo silencioso).
     */
    val bypassDnd: Boolean
        get() = prefs.getBoolean(KEY_BYPASS_DND, DEFAULT_BYPASS_DND)

    /**
     * URI do som a ser tocado quando o alarme dispara.
     * `null` → usa o som padrão de alarme do sistema (RingtoneManager.TYPE_ALARM).
     * Definido pelo usuário via RingtoneManager.ACTION_RINGTONE_PICKER nas Configs.
     */
    val alarmSoundUri: String?
        get() = prefs.getString(KEY_ALARM_SOUND_URI, null)

    /**
     * Epoch millis do último Check (ou do start de sessão, que conta como check 0).
     * Usado para recalcular o próximo alarme quando o usuário altera o
     * baseInterval com sessão ativa: `próximo = lastCheck + novoIntervalo`,
     * preservando cadência. `0L` = nunca houve check (defensivo).
     */
    val lastCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK_MILLIS, 0L)

    /** Re-alerta automático após o overshoot do alarme (passou do zero sem Check). */
    val overshootRepeatEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERSHOOT_ENABLED, DEFAULT_OVERSHOOT_ENABLED)

    /** Intervalo (minutos) entre re-alertas em overshoot. Faixa MIN..MAX_OVERSHOOT_MINUTES. */
    val overshootRepeatMinutes: Int
        get() = prefs.getInt(KEY_OVERSHOOT_MINUTES, DEFAULT_OVERSHOOT_MINUTES)

    /** Importar eventos do Calendar Provider como bloqueios automáticos. */
    val calendarIntegrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALENDAR_ENABLED, DEFAULT_CALENDAR_ENABLED)

    /** IDs dos calendários (CalendarContract.Calendars._ID) que o usuário quer importar. */
    val calendarSelectedIds: Set<Long>
        get() = prefs.getString(KEY_CALENDAR_SELECTED_IDS, null).toLongSet()

    /** Quando `false`, eventos importados aparecem como "Ocupado" — não expõe título. */
    val calendarShowTitles: Boolean
        get() = prefs.getBoolean(KEY_CALENDAR_SHOW_TITLES, DEFAULT_CALENDAR_SHOW_TITLES)

    /**
     * IDs de eventos do Calendar que o usuário "personalizou" — clonou em
     * `InactivityBlock` manual editável. Esses IDs param de ser importados pelo
     * `CalendarEventRepository` para não duplicar.
     */
    val calendarOverriddenEventIds: Set<Long>
        get() = prefs.getString(KEY_CALENDAR_OVERRIDDEN_IDS, null).toLongSet()

    /**
     * Dias da semana em que o scheduler pode disparar alarmes. Default = todos.
     * Dias removidos rolam o alarme para o próximo dia ativo.
     */
    val activeDaysOfWeek: Set<DayOfWeek>
        get() {
            val raw = prefs.getString(KEY_ACTIVE_DAYS_OF_WEEK, null)
            return if (raw == null) DayOfWeek.entries.toSet() else raw.toDayOfWeekSet()
        }

    /**
     * Idioma escolhido pelo usuário no formato BCP-47 ("en", "pt-BR").
     * `null` = primeira execução, ainda não escolheu — UI deve mostrar
     * tela de seleção antes de prosseguir.
     */
    val languageTag: String?
        get() = prefs.getString(KEY_LANGUAGE_TAG, null)

    // ── Escrita ──────────────────────────────────────────────────

    fun setSessionActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_IS_SESSION_ACTIVE, active).apply()
    }

    fun setNextAlarm(
        epochMillis: Long,
        exerciseId: Long,
        exerciseName: String,
        targetReps: Int,
    ) {
        prefs.edit()
            .putLong(KEY_NEXT_ALARM_MILLIS, epochMillis)
            .putLong(KEY_PENDING_EXERCISE_ID, exerciseId)
            .putString(KEY_PENDING_EXERCISE_NAME, exerciseName)
            .putInt(KEY_PENDING_TARGET_REPS, targetReps)
            .putBoolean(KEY_IS_ALARM_PENDING, false)
            .apply()
    }

    fun setAlarmPending(pending: Boolean) {
        prefs.edit().putBoolean(KEY_IS_ALARM_PENDING, pending).apply()
    }

    fun setBaseInterval(minutes: Long) {
        prefs.edit().putLong(KEY_BASE_INTERVAL_MINUTES, minutes).apply()
    }

    fun setDailySetTarget(target: Int) {
        prefs.edit().putInt(KEY_DAILY_SET_TARGET, target).apply()
    }

    fun setShowDailyTarget(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_DAILY_TARGET, show).apply()
    }

    fun setBypassDnd(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BYPASS_DND, enabled).apply()
    }

    /** `null` ou string vazia → reseta para o som padrão do sistema. */
    fun setAlarmSoundUri(uri: String?) {
        prefs.edit().apply {
            if (uri.isNullOrBlank()) remove(KEY_ALARM_SOUND_URI)
            else putString(KEY_ALARM_SOUND_URI, uri)
        }.apply()
    }

    fun setLastCheck(epochMillis: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK_MILLIS, epochMillis).apply()
    }

    fun setOvershootRepeatEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OVERSHOOT_ENABLED, enabled).apply()
    }

    fun setOvershootRepeatMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_OVERSHOOT_MINUTES, MAX_OVERSHOOT_MINUTES)
        prefs.edit().putInt(KEY_OVERSHOOT_MINUTES, clamped).apply()
    }

    fun setCalendarIntegrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_ENABLED, enabled).apply()
    }

    fun setCalendarSelectedIds(ids: Set<Long>) {
        prefs.edit().putString(KEY_CALENDAR_SELECTED_IDS, ids.toCsv()).apply()
    }

    fun setCalendarShowTitles(show: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_SHOW_TITLES, show).apply()
    }

    fun addCalendarOverriddenEventId(eventId: Long) {
        val current = calendarOverriddenEventIds + eventId
        prefs.edit().putString(KEY_CALENDAR_OVERRIDDEN_IDS, current.toCsv()).apply()
    }

    fun setActiveDaysOfWeek(days: Set<DayOfWeek>) {
        prefs.edit().putString(KEY_ACTIVE_DAYS_OF_WEEK, days.toDayOfWeekCsv()).apply()
    }

    fun setLanguageTag(tag: String) {
        prefs.edit().putString(KEY_LANGUAGE_TAG, tag).apply()
    }

    fun clearSession() {
        prefs.edit()
            .putBoolean(KEY_IS_SESSION_ACTIVE, false)
            .putLong(KEY_NEXT_ALARM_MILLIS, 0L)
            .putLong(KEY_PENDING_EXERCISE_ID, -1L)
            .putString(KEY_PENDING_EXERCISE_NAME, "")
            .putInt(KEY_PENDING_TARGET_REPS, 0)
            .putBoolean(KEY_IS_ALARM_PENDING, false)
            .putLong(KEY_LAST_CHECK_MILLIS, 0L)
            .apply()
    }

    // ── Flow reativo ─────────────────────────────────────────────
    // Emite um contador monotônico toda vez que QUALQUER preferência muda.
    // O collector re-lê os campos que precisa.
    //
    // **Importante:** o valor emitido precisa SER ÚNICO por emissão.
    // Uma versão anterior emitia `Unit` com `distinctUntilChanged()`, o que
    // bloqueava silenciosamente todas as emissões depois da primeira (pois
    // `Unit == Unit` sempre). Resultado: após qualquer mudança inicial, o
    // observer parava de receber updates — bug que travava `startSession()`
    // após um `stopSession()`.

    fun observeChanges(): Flow<Long> = callbackFlow {
        var seq = 0L
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(seq++)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Emit inicial para que collectors tenham o estado atual
        trySend(seq++)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

private fun String?.toLongSet(): Set<Long> =
    if (this.isNullOrBlank()) emptySet()
    else this.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()

private fun Set<Long>.toCsv(): String = joinToString(",")

private fun String.toDayOfWeekSet(): Set<DayOfWeek> =
    if (this.isBlank()) emptySet()
    else this.split(",").mapNotNull { token ->
        token.trim().toIntOrNull()?.takeIf { it in 1..7 }?.let { DayOfWeek.of(it) }
    }.toSet()

private fun Set<DayOfWeek>.toDayOfWeekCsv(): String =
    joinToString(",") { it.value.toString() }

