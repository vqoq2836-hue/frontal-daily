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
 * 홈 화면 위젯: 지금 할 미션 하나만 보여 주고, 그 자리에서 체크·넘기기를 합니다.
 * 체크하면 다음 미션이 위젯에 나타납니다.
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
            rv.setTextViewText(R.id.w_head, "오늘 ${info.score}/${info.goal}점 · ${info.passed}/${info.total} 지남")

            val m = info.current
            if (m == null) {
                rv.setTextViewText(R.id.w_title, if (info.total == 0) "앱을 한 번 열어 주세요" else "오늘 미션을 모두 지나왔어요")
                rv.setTextViewText(R.id.w_sub, if (info.score >= info.goal) "목표 달성! 푹 자는 것까지가 오늘의 미션이에요." else "앱에서 지나간 미션을 다시 열 수 있어요.")
                rv.setViewVisibility(R.id.w_buttons, View.GONE)
                return rv
            }

            val id = m.optString("id")
            val time = m.optString("time")
            rv.setTextViewText(R.id.w_title, (if (time.isNotEmpty()) "$time  " else "") + m.optString("title"))
            val extra = when (id) {
                "steps" -> if (info.steps != null) "지금 ${"%,d".format(info.steps)}보 · " else ""
                "sleep" -> if (info.sleepH != null) "어젯밤 ${info.sleepH}시간 · " else ""
                else -> ""
            }
            rv.setTextViewText(R.id.w_sub, extra + "작게: " + m.optString("mini") + "\n제대로: " + m.optString("full"))
            rv.setViewVisibility(R.id.w_buttons, View.VISIBLE)

            val base = Reminders.notificationId(id) * 4
            rv.setOnClickPendingIntent(R.id.w_mini, Reminders.actionIntent(ctx, Reminders.ACTION_DONE, id, 1, base + 101))
            rv.setOnClickPendingIntent(R.id.w_full, Reminders.actionIntent(ctx, Reminders.ACTION_DONE, id, 2, base + 102))
            rv.setOnClickPendingIntent(R.id.w_skip, Reminders.actionIntent(ctx, Reminders.ACTION_SKIP, id, 0, base + 103))
            return rv
        }
    }
}
