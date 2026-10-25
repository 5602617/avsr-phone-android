package io.github.lejw0925.virtualscreen.systemtools

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import io.github.lejw0925.virtualscreen.core.OperationEnvelope
import java.time.ZoneId

/** Public Android APIs only. These methods never open an Activity or permission dialog. */
class PhoneSystemTools(context: Context) {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    fun getNextAlarm(): OperationEnvelope = try {
        confirmed("next_alarm" to nextAlarm(context), "scope" to "next_alarm_only", "full_alarm_list_available" to false)
    } catch (_: Exception) {
        rejected("NEXT_ALARM_READ_FAILED")
    }

    /** Registration is not dispatch. Only the phone user's button consumes this request. */
    fun setAlarm(owner: String, requestId: String, hour: Int, minute: Int, label: String = "", days: List<Int> = emptyList(),
                 binding: Map<String, String> = emptyMap()): OperationEnvelope {
        if (hour !in 0..23 || minute !in 0..59 || days.any { it !in 1..7 }) return rejected("INVALID_ALARM_TIME")
        return AlarmRequests.register(owner, requestId, hour, minute, label, days.distinct(), binding)
    }

    fun getAlarmRequest(owner: String, requestId: String): OperationEnvelope = AlarmRequests.status(owner, requestId)

    /** Wire this to MCP owner disconnect and service shutdown; it never repeats an action. */
    fun invalidatePendingAlarmRequests(owner: String? = null) = AlarmRequests.invalidate(owner)

    fun listCalendars(): OperationEnvelope {
        permissions(Manifest.permission.READ_CALENDAR)?.let { return it }
        return try {
            val rows = mutableListOf<Map<String, Any?>>()
            val cursor = resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_TIME_ZONE, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE), null, null, "${Calendars._ID} ASC")
                ?: return rejected("CALENDAR_READ_FAILED")
            cursor.use {
                while (it.moveToNext()) rows += mapOf("calendar_id" to it.getLong(0), "name" to it.getString(1),
                    "time_zone" to it.getString(2), "can_write" to (it.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR), "visible" to (it.getInt(4) != 0))
            }
            confirmed("calendars" to rows)
        } catch (_: SecurityException) { permissionsFailure(Manifest.permission.READ_CALENDAR) }
        catch (_: Exception) { rejected("CALENDAR_READ_FAILED") }
    }

    /** Instances expands recurring events in the requested millisecond interval. */
    fun readEvents(startMillis: Long, endMillis: Long, calendarId: Long? = null, limit: Int = 100): OperationEnvelope {
        permissions(Manifest.permission.READ_CALENDAR)?.let { return it }
        if (endMillis <= startMillis) return rejected("INVALID_EVENT_INTERVAL")
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, startMillis); ContentUris.appendId(it, endMillis) }.build()
        val projection = arrayOf(Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.EVENT_LOCATION)
        return try {
            val rows = mutableListOf<Map<String, Any?>>()
            val cursor = resolver.query(uri, projection, calendarId?.let { "${Instances.CALENDAR_ID} = ?" }, calendarId?.let { arrayOf(it.toString()) }, "${Instances.BEGIN} ASC")
                ?: return rejected("CALENDAR_READ_FAILED")
            val maximum = limit.coerceIn(1, 500)
            var truncated: Boolean
            cursor.use {
                while (rows.size < maximum && it.moveToNext()) rows += mapOf("event_id" to it.getLong(0), "calendar_id" to it.getLong(1),
                    "title" to it.getString(2), "start_ms" to it.getLong(3), "end_ms" to it.getLong(4), "all_day" to (it.getInt(5) != 0), "location" to it.getString(6))
                truncated = it.moveToNext()
            }
            confirmed("events" to rows, "start_ms" to startMillis, "end_ms" to endMillis, "truncated" to truncated)
        } catch (_: SecurityException) { permissionsFailure(Manifest.permission.READ_CALENDAR) }
        catch (_: Exception) { rejected("CALENDAR_READ_FAILED") }
    }

    /** Inserts once, then independently queries that exact provider URI. No automatic retries. */
    fun createEvent(calendarId: Long, title: String, startMillis: Long, endMillis: Long, timeZone: String,
                    description: String = "", location: String = "", allDay: Boolean = false): OperationEnvelope {
        permissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)?.let { return it }
        if (endMillis <= startMillis) return rejected("INVALID_EVENT_INTERVAL")
        if (runCatching { ZoneId.of(timeZone) }.isFailure) return rejected("INVALID_EVENT_TIME_ZONE")
        // CalendarProvider requires all-day boundaries to be UTC midnight and end to be exclusive.
        if (allDay && (startMillis % 86_400_000L != 0L || endMillis % 86_400_000L != 0L || timeZone != "UTC")) return rejected("ALL_DAY_REQUIRES_UTC_MIDNIGHT")
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId); put(Events.TITLE, title); put(Events.DTSTART, startMillis); put(Events.DTEND, endMillis)
            put(Events.EVENT_TIMEZONE, timeZone); put(Events.DESCRIPTION, description); put(Events.EVENT_LOCATION, location); put(Events.ALL_DAY, if (allDay) 1 else 0)
        }
        val uri = try { resolver.insert(Events.CONTENT_URI, values) }
        catch (_: Exception) { return unknown("CALENDAR_INSERT_UNCONFIRMED", "retry_safe" to false) }
            ?: return unknown("CALENDAR_INSERT_UNCONFIRMED", "retry_safe" to false)
        val projection = arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.ALL_DAY)
        val row = try {
            resolver.query(uri, projection, null, null, null)?.use { if (it.moveToFirst()) eventRow(it) else null }
        } catch (_: Exception) { null }
        val common = arrayOf("event_uri" to uri.toString(), "event" to row, "retry_safe" to false, "scope" to "local_calendar_provider", "cloud_sync_confirmed" to false)
        val matches = row != null && row["calendar_id"] == calendarId && row["title"] == title && row["start_ms"] == startMillis &&
            row["end_ms"] == endMillis && row["time_zone"] == timeZone && row["description"] == description && row["location"] == location && row["all_day"] == allDay
        return if (matches) confirmed(*common) else OperationEnvelope.failure("CALENDAR_EVENT_UNCONFIRMED", null,
            "execution_status" to "accepted", "effect_status" to "unconfirmed", "confirmation" to "unconfirmed", *common)
    }

    private fun eventRow(c: Cursor): Map<String, Any?> = mapOf("event_id" to c.getLong(0), "calendar_id" to c.getLong(1), "title" to c.getString(2),
        "start_ms" to c.getLong(3), "end_ms" to c.getLong(4), "time_zone" to c.getString(5), "description" to c.getString(6).orEmpty(), "location" to c.getString(7).orEmpty(), "all_day" to (c.getInt(8) != 0))

    private fun permissions(vararg required: String): OperationEnvelope? = required.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        .takeIf { it.isNotEmpty() }?.let { permissionsFailure(*it.toTypedArray()) }

    private fun permissionsFailure(vararg required: String) = OperationEnvelope.failure("CALENDAR_PERMISSION_REQUIRED", null,
        "execution_status" to "not_executed", "effect_status" to "not_applied", "confirmation" to "requires_user_action",
        "required_permissions" to required.toList(), "user_action" to "open_apk_system_tools_then_tap_calendar_permission", "permission_activity" to SystemToolsPermissionActivity::class.java.name,
        "opens_main_screen_automatically" to false)
}

internal fun nextAlarm(context: Context): Map<String, Any?> {
    val alarm = context.getSystemService(AlarmManager::class.java).nextAlarmClock
    return mapOf("observed_at_ms" to System.currentTimeMillis(), "present" to (alarm != null), "trigger_time_ms" to alarm?.triggerTime,
        "creator_package" to alarm?.showIntent?.creatorPackage, "scope" to "next_alarm_only")
}

internal fun confirmed(vararg values: Pair<String, Any?>) = OperationEnvelope.success("execution_status" to "accepted", "effect_status" to "observed", "confirmation" to "confirmed", *values)
internal fun rejected(code: String) = OperationEnvelope.failure(code, null, "execution_status" to "rejected", "effect_status" to "not_applied", "confirmation" to "not_executed")
internal fun unknown(code: String, vararg values: Pair<String, Any?>) = OperationEnvelope.failure(code, null, "execution_status" to "unknown", "effect_status" to "unconfirmed", "confirmation" to "unconfirmed", *values)
