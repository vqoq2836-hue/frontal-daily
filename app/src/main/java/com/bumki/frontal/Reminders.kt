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
    const val CHANNEL = "routine_alarm"
    const val BOARD_CHANNEL = "todo_board"
    const val BOARD_ID = 9200
    const val ACTION_TODO_DONE = "com.bumki.frontal.TODO_DONE"
    const val NAG_MINUTES = 10L
    const val MAX_NAGS = 2

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
            val ch = NotificationChannel(CHANNEL, "루틴 알림", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "할 일 시각에 알려 주고, 안 했으면 10분 뒤 다시 알려 줘요"
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 400, 200, 400)
            ch.enableLights(true)
            ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(BOARD_CHANNEL) == null) {
            // 소리·진동 없이 잠금화면에 늘 떠 있는 '오늘 할 일' 판 (무음 알림 숨기기에 걸리지 않게 DEFAULT 중요도)
            val ch = NotificationChannel(BOARD_CHANNEL, "잠금화면 할 일", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "아침에 적은 할 일 중 남은 것을 잠금화면에 늘 보여 줘요"
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
    }

    // ---------- 상태 ----------
    fun saveState(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_STATE, json).apply()
    }

    fun stateObj(ctx: Context): JSONObject = state(ctx)

    private fun state(ctx: Context): JSONObject =
        runCatching { JSONObject(prefs(ctx).getString(KEY_STATE, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun missions(ctx: Context): List<JSONObject> {
        val arr = state(ctx).optJSONArray("missions") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun mission(ctx: Context, id: String): JSONObject? = missions(ctx).firstOrNull { it.optString("id") == id }

    /** 오늘 이미 체크했거나(웹 기록), 알림에서 체크했거나, 오늘 모드에서 쉬는 미션이면 true */
    fun items(ctx: Context): List<JSONObject> = itemsFor(ctx, LocalDate.now())

    /** 그날의 할 일 목록: 오늘 앱이 넘겨준 목록이 있으면 그것, 없으면 요일(평일/주말) 기본 목록 */
    fun itemsFor(ctx: Context, date: LocalDate): List<JSONObject> {
        val s = state(ctx)
        val arr = if (s.optString("date") == date.toString()) s.optJSONArray("items") else {
            val weekend = date.dayOfWeek == java.time.DayOfWeek.SATURDAY || date.dayOfWeek == java.time.DayOfWeek.SUNDAY
            s.optJSONObject("itemsByType")?.optJSONArray(if (weekend) "weekend" else "weekday") ?: s.optJSONArray("items")
        }
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun item(ctx: Context, id: String): JSONObject? = items(ctx).firstOrNull { it.optString("id") == id }

    /** 시간대(b:로 시작) 알림은 그 시간대 할 일이 모두 끝났을 때 '끝난 것'으로 봅니다. */
    fun isSettled(ctx: Context, id: String): Boolean {
        if (id.startsWith("b:")) {
            val its = items(ctx).filter { it.optString("block") == id }
            if (its.isNotEmpty()) return its.all { isItemSettled(ctx, it.optString("id")) }
        }
        return isItemSettled(ctx, id)
    }

    fun remainingInBlock(ctx: Context, blockId: String): List<JSONObject> =
        items(ctx).filter { it.optString("block") == blockId && !isItemSettled(ctx, it.optString("id")) }

    fun isItemSettled(ctx: Context, id: String): Boolean {
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
        val current: JSONObject?, val state: String, val score: Int, val goal: Int,
        val doneCount: Int, val total: Int, val steps: Long?, val sleepH: Double?, val empty: Boolean
    )

    fun widgetInfo(ctx: Context): WidgetInfo {
        val s = state(ctx)
        val its = items(ctx)
        val sameDay = s.optString("date") == today()
        val done = if (sameDay) s.optJSONObject("done") else null
        var score = if (sameDay) s.optInt("score", 0) else 0
        val goal = if (sameDay) s.optInt("goal", 20) else 20
        val counted = HashSet<String>()
        val pending = pendingArray(ctx)
        for (i in 0 until pending.length()) {
            val p = pending.getJSONObject(i)
            val id = p.optString("id")
            if (p.optString("date") != today() || id in counted) continue
            if ((done?.optInt(id, 0) ?: 0) == 0) score += p.optInt("lvl", 0)
            counted.add(id)
        }
        val steps = if (sameDay && s.has("steps") && !s.isNull("steps")) s.optLong("steps") else null
        val sleepH = if (sameDay && s.has("sleepH") && !s.isNull("sleepH")) s.optDouble("sleepH") else null
        if (its.isEmpty()) return WidgetInfo(null, "empty", score, goal, 0, 0, steps, sleepH, true)

        val now = LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }
        val sorted = its.sortedBy { it.optString("time") }
        // 지금 시간대(블록)
        val cur = sorted.firstOrNull { it.optString("start") <= now && now < it.optString("end") }
            ?: sorted.firstOrNull { it.optString("start") > now } ?: sorted.last()
        val block = cur.optString("block")
        val due = sorted.firstOrNull {
            it.optString("block") == block && it.optString("time") <= now && !isItemSettled(ctx, it.optString("id"))
        }
        val next = due ?: sorted.firstOrNull { it.optString("time") > now && !isItemSettled(ctx, it.optString("id")) }
        val doneCount = sorted.count { isItemSettled(ctx, it.optString("id")) }
        return WidgetInfo(next, if (due != null) "now" else if (next != null) "next" else "done",
            score, goal, doneCount, sorted.size, steps, sleepH, false)
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
    private fun parseTime(t: String): LocalTime? {
        if (!Regex("^\\d{1,2}:\\d{2}$").matches(t)) return null
        return runCatching { LocalTime.parse(if (t.length == 4) "0$t" else t) }.getOrNull()
    }

    /** 할 일마다 다음 알림 시각을 예약합니다. 오늘 이미 지났거나 했으면 내일 목록에서 찾습니다. */
    fun scheduleAll(ctx: Context) {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val todayDate = now.toLocalDate()
        val scheduled = HashSet<String>()
        for (it in itemsFor(ctx, todayDate)) {
            val id = it.optString("id")
            val lt = parseTime(it.optString("time")) ?: continue
            val at = todayDate.atTime(lt).atZone(zone)
            if (at.isAfter(now) && !isItemSettled(ctx, id)) {
                setAlarm(ctx, at.toInstant().toEpochMilli(), alarmIntent(ctx, id, 0, false))
                scheduled.add(id)
            }
        }
        val tomorrow = todayDate.plusDays(1)
        for (it in itemsFor(ctx, tomorrow)) {
            val id = it.optString("id")
            if (id in scheduled) continue
            val lt = parseTime(it.optString("time")) ?: continue
            setAlarm(ctx, tomorrow.atTime(lt).atZone(zone).toInstant().toEpochMilli(), alarmIntent(ctx, id, 0, false))
            scheduled.add(id)
        }
        // 아침에 적은 할 일이 남아 있으면 정해진 시각(점심·퇴근·저녁)에 알려 줌
        val st = state(ctx)
        if (st.optString("date") == todayDate.toString() && remainingTodos(ctx).isNotEmpty()) {
            val times = st.optJSONArray("todoTimes")
            if (times != null) for (k in 0 until times.length()) {
                val tm = times.optString(k)
                val lt = parseTime(tm) ?: continue
                val at = todayDate.atTime(lt).atZone(zone)
                if (at.isAfter(now)) setAlarm(ctx, at.toInstant().toEpochMilli(), alarmIntent(ctx, "todo@$tm", 0, false))
            }
        }
        // 앱에서 '10분 뒤'를 누른 할 일
        val s = state(ctx)
        if (s.optString("date") == todayDate.toString()) {
            val snz = s.optJSONObject("snoozed")
            snz?.keys()?.forEach { id ->
                val t = snz.optLong(id, 0)
                if (t > System.currentTimeMillis() && !isItemSettled(ctx, id)) setAlarm(ctx, t, alarmIntent(ctx, id, 1, true))
            }
        }
        // 자정이 지나면 잠금화면 할 일(알림·배경)을 새 날로 바꿈
        setAlarm(ctx, tomorrow.atStartOfDay(zone).plusSeconds(30).toInstant().toEpochMilli(), alarmIntent(ctx, "board@midnight", 0, false))
        updateBoard(ctx)
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
        val it = item(ctx, id)
        if (it != null) { showItem(ctx, it, nag); return }
        val m = mission(ctx, id) ?: return
        if (id.startsWith("b:")) { showBlock(ctx, id, m, nag); return }
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

    private fun showBlock(ctx: Context, id: String, m: JSONObject, nag: Int) {
        val left = remainingInBlock(ctx, id)
        if (left.isEmpty()) return
        val title = m.optString("title", "할 일")
        val head = if (nag == 0) "지금: $title 시간이에요" else "아직 ${left.size}개 남았어요 ($nag/$MAX_NAGS) · $title"
        val body = left.joinToString("\n") { "• " + it.optString("title") }
        val open = PendingIntent.getActivity(
            ctx, notificationId(id),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val first = left.first()
        val fid = first.optString("id")
        val base = notificationId(fid) * 4
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(head)
            .setContentText(left.joinToString(" · ") { it.optString("title") })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
        val short = first.optString("title").let { if (it.length > 8) it.take(8) + "…" else it }
        if (first.optBoolean("two")) {
            b.addAction(0, "$short 작게", actionIntent(ctx, ACTION_DONE, fid, 1, base + 1))
            b.addAction(0, "$short 제대로", actionIntent(ctx, ACTION_DONE, fid, 2, base + 2))
        } else {
            b.addAction(0, "$short 했어요", actionIntent(ctx, ACTION_DONE, fid, 1, base + 1))
        }
        b.addAction(0, "30분 뒤", actionIntent(ctx, ACTION_SNOOZE, id, nag, notificationId(id) * 4 + 3))
        try {
            NotificationManagerCompat.from(ctx).notify(notificationId(id), b.build())
        } catch (_: SecurityException) { }
    }

    fun remainingTodos(ctx: Context): List<JSONObject> {
        val s = state(ctx)
        if (s.optString("date") != today()) return emptyList()
        val arr = s.optJSONArray("todos") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("done") }
    }

    private fun todayTodos(ctx: Context): List<JSONObject> {
        val s = state(ctx)
        if (s.optString("date") != today()) return emptyList()
        val arr = s.optJSONArray("todos") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** 잠금화면 버튼으로 끝낸 할 일: 판을 바로 고치고, 앱을 열면 기록에 반영되도록 줄 세워 둠 */
    fun markTodoDone(ctx: Context, todoId: String) {
        val s = state(ctx)
        if (s.optString("date") != today()) return
        val arr = s.optJSONArray("todos") ?: return
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.optString("id") == todoId) t.put("done", true)
        }
        saveState(ctx, s.toString())
        val p = pendingArray(ctx)
        p.put(JSONObject().put("date", today()).put("kind", "todo").put("id", todoId).put("at", System.currentTimeMillis()))
        prefs(ctx).edit().putString(KEY_PENDING, p.toString()).apply()
    }

    /** 잠금화면 판이 왜 안 보이는지 앱 화면에서 짚어 줄 수 있게 상태를 모아 줌 */
    fun boardStatus(ctx: Context): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = nm.getNotificationChannel(BOARD_CHANNEL)
        val active = runCatching { nm.activeNotifications.any { it.id == BOARD_ID } }.getOrDefault(false)
        val cr = ctx.contentResolver
        val o = JSONObject()
            .put("permission", canNotify(ctx))
            .put("appEnabled", NotificationManagerCompat.from(ctx).areNotificationsEnabled())
            .put("channel", ch?.importance ?: -1)
            .put("channelLock", ch?.lockscreenVisibility ?: -999)
            .put("todos", todayTodos(ctx).size)
            .put("left", remainingTodos(ctx).size)
            .put("setting", state(ctx).optBoolean("todoBoard", true))
            .put("active", active)
        runCatching { o.put("lsShow", android.provider.Settings.Secure.getInt(cr, "lock_screen_show_notifications")) }
        runCatching { o.put("lsPrivate", android.provider.Settings.Secure.getInt(cr, "lock_screen_allow_private_notifications")) }
        return o.toString()
    }

    /** 잠금화면에 고정되는 '오늘 할 일' 판. 할 일이 바뀔 때마다 같은 자리에서 조용히 고쳐 씀 */
    fun updateBoard(ctx: Context) {
        runCatching { LockWallpaper.update(ctx) }
        val nm = NotificationManagerCompat.from(ctx)
        val all = todayTodos(ctx)
        if (!state(ctx).optBoolean("todoBoard", true) || all.isEmpty() || !canNotify(ctx)) {
            nm.cancel(BOARD_ID); return
        }
        ensureChannel(ctx)
        val left = all.filter { !it.optBoolean("done") }.sortedBy { if (it.optBoolean("top")) 0 else 1 }
        val done = all.filter { it.optBoolean("done") }
        val zone = ZoneId.systemDefault()
        val untilMidnight = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - System.currentTimeMillis()
        val open = PendingIntent.getActivity(
            ctx, BOARD_ID,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, BOARD_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(maxOf(60_000L, untilMidnight))
        if (left.isEmpty()) {
            b.setContentTitle("오늘 할 일 모두 끝 ✓")
                .setContentText("적어 둔 ${done.size}개를 다 해냈어요")
                .setOngoing(false).setAutoCancel(true)
                .setTimeoutAfter(30 * 60_000L)
        } else {
            val mark = { t: JSONObject -> (if (t.optBoolean("top")) "★ " else "□ ") + t.optString("text") }
            val style = NotificationCompat.InboxStyle()
            left.take(6).forEach { style.addLine(mark(it)) }
            done.take(maxOf(0, 7 - minOf(6, left.size))).forEach {
                val sp = android.text.SpannableString("✓ " + it.optString("text"))
                sp.setSpan(android.text.style.StrikethroughSpan(), 0, sp.length, 0)
                style.addLine(sp)
            }
            if (left.size > 6) style.setSummaryText("외 ${left.size - 6}개 더")
            b.setContentTitle("할 일 ${left.size}개 남음" + if (done.isNotEmpty()) " · ${done.size}개 끝" else "")
                .setContentText(left.joinToString("  ") { mark(it) })
                .setStyle(style)
                .setOngoing(true)
            // 알림 버튼은 최대 3개: 남은 할 일마다 하나씩, 잠금을 풀지 않고 바로 체크
            val maxLen = if (left.size == 1) 14 else 7
            left.take(3).forEachIndexed { i, t ->
                val label = t.optString("text").let { if (it.length > maxLen) it.take(maxLen - 1) + "…" else it }
                b.addAction(0, "✓ $label", actionIntent(ctx, ACTION_TODO_DONE, t.optString("id"), 0, BOARD_ID + 1 + i))
            }
        }
        try { nm.notify(BOARD_ID, b.build()) } catch (_: SecurityException) { }
    }

    /** "오늘 할 일 2개 남았어요" 알림 */
    fun showTodos(ctx: Context) {
        if (!canNotify(ctx)) return
        val left = remainingTodos(ctx)
        if (left.isEmpty()) return
        val body = left.sortedBy { if (it.optBoolean("top")) 0 else 1 }
            .joinToString("\n") { (if (it.optBoolean("top")) "★ " else "• ") + it.optString("text") }
        val open = PendingIntent.getActivity(
            ctx, 9100,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("오늘 할 일 ${left.size}개 남았어요")
            .setContentText(left.joinToString(" · ") { it.optString("text") })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(9100, n)
        } catch (_: SecurityException) { }
        buzz(ctx)
    }

    /** 평일(설정에 따라 주말도)에는 휴대폰이 무음이어도 알람처럼 진동합니다. */
    fun buzz(ctx: Context, force: Boolean = false) {
        if (!force) {
            val v = state(ctx).optJSONObject("vibrate")
            val d = LocalDate.now().dayOfWeek
            val weekend = d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY
            val on = if (weekend) v?.optBoolean("weekend", false) ?: false else v?.optBoolean("weekday", true) ?: true
            if (!on) return
        }
        try {
            val effect = android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500, 250, 700), -1)
            val alarmAudio = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 33) {
                val vib = ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                vib.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            } else if (Build.VERSION.SDK_INT >= 31) {
                val vib = ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                vib.vibrate(effect, alarmAudio)
            } else {
                @Suppress("DEPRECATION")
                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                vib.vibrate(effect, alarmAudio)
            }
        } catch (_: Exception) { }
    }

    /** 할 일 하나 알림: 체크·미루기·넘기기 전까지 알림창 맨 위에 남아 있어요. */
    private fun showItem(ctx: Context, it: JSONObject, nag: Int) {
        val id = it.optString("id")
        val title = it.optString("title")
        val time = it.optString("time")
        val head = if (nag == 0) "$time · $title" else "아직이에요 · $title"
        val body = if (nag == 0) {
            val cue = it.optString("cue")
            if (cue.isNotEmpty() && cue != it.optString("blockTitle")) "$cue → $title" else "${it.optString("blockTitle")} 루틴 · 지금 할 시간이에요"
        } else "괜찮아요, 지금 해도 돼요. 작게라도 하면 하루가 이어져요."
        val open = PendingIntent.getActivity(
            ctx, notificationId(id),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val base = notificationId(id) * 4
        val lvl = if (it.optBoolean("two")) 2 else 1
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(head)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .addAction(0, "했어요", actionIntent(ctx, ACTION_DONE, id, lvl, base + 1))
            .addAction(0, "${NAG_MINUTES}분 뒤", actionIntent(ctx, ACTION_SNOOZE, id, nag, base + 3))
            .addAction(0, "넘기기", actionIntent(ctx, ACTION_SKIP, id, 0, base + 2))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(notificationId(id), n)
        } catch (_: SecurityException) { }
        buzz(ctx)
    }

    /** 할 일을 체크한 뒤: 끝난 시간대의 알림·재알림은 정리하고, 남은 시간대 알림은 목록을 새로 고칩니다. */
    fun refreshBlockNotifications(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val active = nm.activeNotifications.map { it.id }.toSet()
        for (m in missions(ctx)) {
            val bid = m.optString("id")
            if (!bid.startsWith("b:")) continue
            if (isSettled(ctx, bid)) { cancelNag(ctx, bid); dismiss(ctx, bid) }
            else if (notificationId(bid) in active) showBlock(ctx, bid, m, 0)
        }
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
        buzz(ctx, force = true)
    }
}
