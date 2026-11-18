package com.bumki.frontal

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 미션 시간에 알림을 보내고, 체크하지 않으면 NAG_MINUTES 간격으로 최대 MAX_NAGS 번 다시 묻습니다.
 * 웹 화면이 syncState 로 오늘의 체크 현황과 미션 시간을 넘겨주면 그것을 기준으로 판단합니다.
 */
object Reminders {
    const val CHANNEL = "missions"
    const val NAG_MINUTES = 30L
    const val MAX_NAGS = 3

    const val ACTION_ALARM = "com.bumki.frontal.ALARM"
    const val ACTION_DONE = "com.bumki.frontal.DONE"
    const val ACTION_SNOOZE = "com.bumki.frontal.SNOOZE"
    const val ACTION_SKIP = "com.bumki.frontal.SKIP"
    const val EXTRA_ID = "id"
    const val EXTRA_NAG = "nag"
    const val EXTRA_LVL = "lvl"

    private const val PREFS = "frontal"
    private const val KEY_STATE = "state"
    private const val KEY_PENDING = "pending"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today(): String = LocalDate.now().toString()

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "미션 알림", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "미션 시간과 '했어요?' 다시 묻기 알림"
            nm.createNotificationChannel(ch)
        }
    }

    // ---------- 상태 ----------
    fun saveState(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_STATE, json).apply()
    }

    private fun state(ctx: Context): JSONObject =
        runCatching { JSONObject(prefs(ctx).getString(KEY_STATE, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun missions(ctx: Context): List<JSONObject> {
        val arr = state(ctx).optJSONArray("missions") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun mission(ctx: Context, id: String): JSONObject? = missions(ctx).firstOrNull { it.optString("id") == id }

    /** 오늘 이미 체크했거나(웹 기록), 알림에서 체크했거나, 오늘 모드에서 쉬는 미션이면 true */
    fun isSettled(ctx: Context, id: String): Boolean {
        val s = state(ctx)
        if (s.optString("date") == today()) {
            if ((s.optJSONObject("done")?.optInt(id, 0) ?: 0) > 0) return true
            val skip = s.optJSONArray("skip")
            if (skip != null) for (i in 0 until skip.length()) if (skip.optString(i) == id) return true
        }
        val pending = pendingArray(ctx)
        for (i in 0 until pending.length()) {
            val p = pending.getJSONObject(i)
            if (p.optString("date") == today() && p.optString("id") == id) return true
        }
        return false
    }

    // ---------- 위젯에 보여 줄 정보 ----------
    data class WidgetInfo(
        val current: JSONObject?, val score: Int, val goal: Int,
        val passed: Int, val total: Int, val steps: Long?, val sleepH: Double?
    )

    fun widgetInfo(ctx: Context): WidgetInfo {
        val s = state(ctx)
        val ms = missions(ctx)
        val sameDay = s.optString("date") == today()
        val done = if (sameDay) s.optJSONObject("done") else null
        var score = if (sameDay) s.optInt("score", 0) else 0
        val goal = if (sameDay) s.optInt("goal", 9) else 9
        // 앱을 열기 전 알림·위젯에서 누른 체크도 점수에 미리 반영
        val counted = HashSet<String>()
        val pending = pendingArray(ctx)
        for (i in 0 until pending.length()) {
            val p = pending.getJSONObject(i)
            val id = p.optString("id")
            if (p.optString("date") != today() || id in counted) continue
            if ((done?.optInt(id, 0) ?: 0) == 0) score += p.optInt("lvl", 0)
            counted.add(id)
        }
        val ids = ms.map { it.optString("id") }
        val passed = ids.count { isSettled(ctx, it) }
        val current = ms.firstOrNull { !isSettled(ctx, it.optString("id")) }
        val steps = if (sameDay && s.has("steps") && !s.isNull("steps")) s.optLong("steps") else null
        val sleepH = if (sameDay && s.has("sleepH") && !s.isNull("sleepH")) s.optDouble("sleepH") else null
        return WidgetInfo(current, score, goal, passed, ms.size, steps, sleepH)
    }

    // ---------- 알림에서 누른 체크 (웹이 다음에 열릴 때 가져감) ----------
    private fun pendingArray(ctx: Context): JSONArray =
        runCatching { JSONArray(prefs(ctx).getString(KEY_PENDING, "[]") ?: "[]") }.getOrDefault(JSONArray())

    fun addPending(ctx: Context, id: String, lvl: Int) {
        val arr = pendingArray(ctx)
        arr.put(JSONObject().put("date", today()).put("id", id).put("lvl", lvl).put("at", System.currentTimeMillis()))
        prefs(ctx).edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    fun takePending(ctx: Context): String {
        val s = prefs(ctx).getString(KEY_PENDING, "[]") ?: "[]"
        prefs(ctx).edit().putString(KEY_PENDING, "[]").apply()
        return s
    }

    // ---------- 예약 ----------
    private fun requestCode(id: String, nag: Boolean) = (id.hashCode() and 0x0FFFFFFF) * 2 + if (nag) 1 else 0

    private fun alarmIntent(ctx: Context, id: String, nag: Int, isNag: Boolean): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_NAG, nag)
        return PendingIntent.getBroadcast(
            ctx, requestCode(id, isNag), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAlarm(ctx: Context, atMillis: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exactOk = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    /** 각 미션의 '다음 알림 시각'을 예약합니다. 오늘 시간이 지났거나 이미 했으면 내일로. */
    fun scheduleAll(ctx: Context) {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        for (m in missions(ctx)) {
            val id = m.optString("id")
            val time = m.optString("time")
            if (id.isEmpty() || !Regex("^\\d{1,2}:\\d{2}$").matches(time)) continue
            val lt = runCatching { LocalTime.parse(if (time.length == 4) "0$time" else time) }.getOrNull() ?: continue
            var at = now.toLocalDate().atTime(lt).atZone(zone)
            if (!at.isAfter(now) || isSettled(ctx, id)) at = at.plusDays(1)
            setAlarm(ctx, at.toInstant().toEpochMilli(), alarmIntent(ctx, id, 0, false))
        }
    }

    fun scheduleNag(ctx: Context, id: String, nag: Int, minutes: Long = NAG_MINUTES) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        setAlarm(ctx, at, alarmIntent(ctx, id, nag, true))
    }

    fun cancelNag(ctx: Context, id: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(alarmIntent(ctx, id, 0, true))
    }

    // ---------- 알림 ----------
    private fun canNotify(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notificationId(id: String) = (id.hashCode() and 0x0FFFFFFF)

    fun actionIntent(ctx: Context, action: String, id: String, lvl: Int, code: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_LVL, lvl)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showMission(ctx: Context, id: String, nag: Int) {
        if (!canNotify(ctx)) return
        val m = mission(ctx, id) ?: return
        val title = m.optString("title", "미션")
        val head = if (nag == 0) "지금: $title" else "아직이에요 ($nag/$MAX_NAGS) · $title 했어요?"
        val body = if (nag == 0) m.optString("cue", "") + " → " + title
        else "작게라도 하면 1점이에요. " + m.optString("mini", "")

        val open = PendingIntent.getActivity(
            ctx, notificationId(id),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val base = notificationId(id) * 4
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(head)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "작게 했어요", actionIntent(ctx, ACTION_DONE, id, 1, base + 1))
            .addAction(0, "제대로 했어요", actionIntent(ctx, ACTION_DONE, id, 2, base + 2))
            .addAction(0, "30분 뒤", actionIntent(ctx, ACTION_SNOOZE, id, nag, base + 3))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(notificationId(id), n)
        } catch (_: SecurityException) { }
    }

    fun dismiss(ctx: Context, id: String) {
        NotificationManagerCompat.from(ctx).cancel(notificationId(id))
    }

    fun showTest(ctx: Context) {
        if (!canNotify(ctx)) return
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("알림 테스트")
            .setContentText("이 알림이 보이면 미션 알림도 정상으로 울려요.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(1, n)
        } catch (_: SecurityException) { }
    }
}
