package com.bumki.frontal

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 폰에 연결된 캘린더(구글 계정 캘린더 포함)에 일정을 직접 넣습니다.
 * 폰 캘린더에 들어간 일정은 구글 계정 동기화로 구글 캘린더에 올라갑니다.
 */
object CalendarWriter {
    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun granted(ctx: Context) = PERMISSIONS.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun list(ctx: Context): String {
        val out = JSONArray()
        if (!granted(ctx)) return out.toString()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.OWNER_ACCOUNT
        )
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null
        )?.use { c ->
            while (c.moveToNext()) {
                val account = c.getString(2) ?: ""
                val owner = c.getString(5) ?: ""
                out.put(
                    JSONObject()
                        .put("id", c.getLong(0))
                        .put("name", c.getString(1) ?: "")
                        .put("account", account)
                        .put("type", c.getString(3) ?: "")
                        .put("primary", c.getInt(4) == 1 || (owner.isNotEmpty() && owner == account))
                )
            }
        }
        return out.toString()
    }

    fun add(ctx: Context, json: String): String {
        return try {
            if (!granted(ctx)) return JSONObject().put("ok", false).put("error", "no_permission").toString()
            val o = JSONObject(json)
            val date = LocalDate.parse(o.getString("date"))
            val v = ContentValues()
            v.put(CalendarContract.Events.CALENDAR_ID, o.getLong("calendarId"))
            v.put(CalendarContract.Events.TITLE, o.optString("title", "새 일정"))
            val loc = o.optString("location")
            if (loc.isNotEmpty()) v.put(CalendarContract.Events.EVENT_LOCATION, loc)
            v.put(CalendarContract.Events.DESCRIPTION, "전두엽 데일리에서 말로 넣은 일정")
            val allDay = o.optBoolean("allDay") || o.optString("start").isEmpty()
            if (allDay) {
                val s = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                v.put(CalendarContract.Events.DTSTART, s)
                v.put(CalendarContract.Events.DTEND, s + 86_400_000L)
                v.put(CalendarContract.Events.ALL_DAY, 1)
                v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                val zone = ZoneId.systemDefault()
                val st = LocalTime.parse(o.getString("start"))
                val enText = o.optString("end")
                val en = if (enText.isNotEmpty()) LocalTime.parse(enText) else st.plusHours(1)
                val startMs = date.atTime(st).atZone(zone).toInstant().toEpochMilli()
                val endDate = if (en <= st) date.plusDays(1) else date
                val endMs = endDate.atTime(en).atZone(zone).toInstant().toEpochMilli()
                v.put(CalendarContract.Events.DTSTART, startMs)
                v.put(CalendarContract.Events.DTEND, endMs)
                v.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            }
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
                ?: return JSONObject().put("ok", false).put("error", "insert_failed").toString()
            val id = ContentUris.parseId(uri)
            if (!allDay) {
                runCatching {
                    val r = ContentValues()
                    r.put(CalendarContract.Reminders.EVENT_ID, id)
                    r.put(CalendarContract.Reminders.MINUTES, o.optInt("remind", 30))
                    r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                    ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
                }
            }
            JSONObject().put("ok", true).put("id", id).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName).toString()
        }
    }

    fun delete(ctx: Context, id: Long): Boolean = runCatching {
        ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    }.getOrDefault(false)
}
