package com.gtg.app.data.repository

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.gtg.app.data.local.SessionPreferences
import com.gtg.app.domain.model.InactivityBlock
import com.gtg.app.domain.model.Recurrence
import com.gtg.app.domain.repository.CalendarEventRepository
import com.gtg.app.domain.repository.CalendarInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lê o Calendar Provider local (`CalendarContract`). Sem cache em Room — o
 * provider já é o cache do sistema.
 *
 * Por que `CalendarContract.Instances` em vez de `Events`: `Events` retorna o
 * "master" do RRULE sem expandir recorrências. `Instances` expande RRULE,
 * RDATE e EXDATE dentro dos bounds passados via `ContentUris.appendId`.
 */
@Singleton
class CalendarEventRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionPrefs: SessionPreferences,
) : CalendarEventRepository {

    override suspend fun listAvailableCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasReadCalendarPermission()) return@withContext emptyList()

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.SYNC_EVENTS} = 1"

        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, " +
                    "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val result = mutableListOf<CalendarInfo>()
                while (cursor.moveToNext()) {
                    result += CalendarInfo(
                        id = cursor.getLong(0),
                        displayName = cursor.getString(1) ?: "(sem nome)",
                        accountName = cursor.getString(2) ?: "",
                        colorArgb = cursor.getInt(3),
                    )
                }
                result
            }
        }.getOrNull().orEmpty()
    }

    override suspend fun getBlocksOn(date: LocalDate): List<InactivityBlock> =
        getBlocksInRange(date, date)[date].orEmpty()

    override suspend fun getBlocksInRange(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
    ): Map<LocalDate, List<InactivityBlock>> = withContext(Dispatchers.IO) {
        if (!sessionPrefs.calendarIntegrationEnabled) return@withContext emptyMap()
        val selectedIds = sessionPrefs.calendarSelectedIds
        if (selectedIds.isEmpty()) return@withContext emptyMap()
        if (!hasReadCalendarPermission()) return@withContext emptyMap()

        val zone = ZoneId.systemDefault()
        val rangeStartMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEndMs = endDateInclusive.plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { b ->
            ContentUris.appendId(b, rangeStartMs)
            ContentUris.appendId(b, rangeEndMs)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,        // 0
            CalendarContract.Instances.TITLE,           // 1
            CalendarContract.Instances.BEGIN,           // 2
            CalendarContract.Instances.END,             // 3
        )

        val placeholders = selectedIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders) " +
            "AND ${CalendarContract.Events.ALL_DAY} = 0 " +
            "AND ${CalendarContract.Events.AVAILABILITY} = " +
            "${CalendarContract.Events.AVAILABILITY_BUSY} " +
            "AND (${CalendarContract.Events.STATUS} IS NULL OR " +
            "${CalendarContract.Events.STATUS} != ${CalendarContract.Events.STATUS_CANCELED}) " +
            "AND (${CalendarContract.Events.SELF_ATTENDEE_STATUS} IS NULL OR " +
            "${CalendarContract.Events.SELF_ATTENDEE_STATUS} != " +
            "${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})"

        val selectionArgs = selectedIds.map { it.toString() }.toTypedArray()
        val overridden = sessionPrefs.calendarOverriddenEventIds
        val showTitles = sessionPrefs.calendarShowTitles

        runCatching {
            context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val result = mutableMapOf<LocalDate, MutableList<InactivityBlock>>()
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(0)
                    if (eventId in overridden) continue

                    val title = cursor.getString(1).orEmpty()
                    val beginMs = cursor.getLong(2)
                    val endMs = cursor.getLong(3)

                    val begin = Instant.ofEpochMilli(beginMs).atZone(zone).toLocalDateTime()
                    val end = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDateTime()

                    // Particiona o evento por dia do range que ele cobre.
                    // Eventos que cruzam meia-noite aparecem em cada dia coberto,
                    // com horário "clamped" à fronteira.
                    var currentDate = begin.toLocalDate().coerceAtLeast(startDate)
                    val endDateInternal = end.toLocalDate().coerceAtMost(endDateInclusive)
                    while (!currentDate.isAfter(endDateInternal)) {
                        val startTime = if (begin.toLocalDate() == currentDate) {
                            begin.toLocalTime()
                        } else {
                            LocalTime.MIDNIGHT
                        }
                        val endTime = if (end.toLocalDate() == currentDate) {
                            end.toLocalTime()
                        } else {
                            LocalTime.of(23, 59)
                        }
                        if (startTime.isBefore(endTime)) {
                            result.getOrPut(currentDate) { mutableListOf() } += InactivityBlock(
                                // ID negativo distingue dos persistidos no Room.
                                id = -eventId,
                                title = if (showTitles) title.ifBlank { "Ocupado" } else "Ocupado",
                                startTime = startTime,
                                endTime = endTime,
                                specificDate = currentDate,
                                recurrence = Recurrence.NONE,
                            )
                        }
                        currentDate = currentDate.plusDays(1)
                    }
                }
                result
            }
        }.getOrNull().orEmpty()
    }

    private fun hasReadCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
}
