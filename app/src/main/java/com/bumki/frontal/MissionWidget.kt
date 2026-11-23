package com.bumki.frontal

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/**
 * 홈 화면 위젯: 지금 시간대에서 아직 안 한 할 일을 하나씩 보여 주고, 그 자리에서 체크·넘기기를 합니다.
 * 체크하면 같은 시간대의 다음 할 일이 나타납니다.
 */
class MissionWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = build(ctx)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MissionWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun build(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_mission)
            val info = Reminders.widgetInfo(ctx)

            val open = PendingIntent.getActivity(
                ctx, 9001,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.w_root, open)

            if (info.empty) {
                rv.setTextViewText(R.id.w_head, "범키데일리")
                rv.setTextViewText(R.id.w_title, "앱을 한 번 열어 주세요")
                rv.setTextViewText(R.id.w_sub, "앱을 열면 오늘 루틴이 위젯에 나타나요.")
                rv.setViewVisibility(R.id.w_buttons, View.GONE)
                return rv
            }

            val m = info.current
            val todoLeft = Reminders.remainingTodos(ctx).size
            rv.setTextViewText(R.id.w_head, (if (todoLeft > 0) "할 일 ${todoLeft}개 남음 · " else "") + "${info.doneCount}/${info.total}")
            if (m == null) {
                rv.setTextViewText(R.id.w_time, "")
                rv.setTextViewText(R.id.w_title, "오늘 루틴 끝")
                rv.setTextViewText(R.id.w_sub, if (info.score >= info.goal) "목표 달성! 푹 자는 것까지가 루틴이에요." else "내일 첫 알림부터 다시 시작해요.")
                rv.setViewVisibility(R.id.w_buttons, View.GONE)
                return rv
            }
            rv.setTextViewText(R.id.w_time, (if (info.state == "next") "다음 " else "지금 ") + m.optString("time"))

            val id = m.optString("id")
            val two = m.optBoolean("two")
            rv.setTextViewText(R.id.w_title, m.optString("title"))
            val extra = when (id) {
                "steps" -> if (info.steps != null) "지금 ${"%,d".format(info.steps)}보 · " else ""
                "sleep" -> if (info.sleepH != null) "어젯밤 ${info.sleepH}시간 · " else ""
                else -> ""
            }
            rv.setTextViewText(
                R.id.w_sub,
                if (two) extra + "제대로: " + m.optString("full") else extra + m.optString("blockTitle") + " 루틴"
            )
            rv.setViewVisibility(R.id.w_buttons, View.VISIBLE)

            val base = Reminders.notificationId(id) * 4
            if (two) {
                rv.setViewVisibility(R.id.w_mini, View.VISIBLE)
                rv.setTextViewText(R.id.w_full, "제대로 했어요")
                rv.setOnClickPendingIntent(R.id.w_mini, Reminders.actionIntent(ctx, Reminders.ACTION_DONE, id, 1, base + 101))
                rv.setOnClickPendingIntent(R.id.w_full, Reminders.actionIntent(ctx, Reminders.ACTION_DONE, id, 2, base + 102))
            } else {
                rv.setViewVisibility(R.id.w_mini, View.GONE)
                rv.setTextViewText(R.id.w_full, "했어요")
                rv.setOnClickPendingIntent(R.id.w_full, Reminders.actionIntent(ctx, Reminders.ACTION_DONE, id, 1, base + 102))
            }
            rv.setOnClickPendingIntent(R.id.w_skip, Reminders.actionIntent(ctx, Reminders.ACTION_SKIP, id, 0, base + 103))
            return rv
        }
    }
}
