package com.bumki.frontal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "grp:a|b|c" 처럼 묶음 알림에서 온 id 를 하나씩 풀어 줌 */
private fun groupIds(id: String): List<String> =
    if (id.startsWith("grp:")) id.removePrefix("grp:").split("|").filter { it.isNotEmpty() } else listOf(id)

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        when (intent.action) {
            Reminders.ACTION_TODO_DONE -> {
                Reminders.markTodoDone(ctx, id)
                Reminders.updateBoard(ctx)
                MissionWidget.updateAll(ctx)
            }
            Reminders.ACTION_ALARM -> {
                Reminders.updateBoard(ctx)
                if (id.startsWith("board@")) { Reminders.scheduleAll(ctx); MissionWidget.updateAll(ctx); return }
                if (id.startsWith("todo@")) { Reminders.showTodos(ctx); return }
                if (id.startsWith("report@")) { Reminders.showReport(ctx); Reminders.scheduleAll(ctx); return }
                val nag = intent.getIntExtra(Reminders.EXTRA_NAG, 0)
                // 같은 시각에 묶인 할 일은 맨 앞 하나가 묶음 알림을 대표해서 띄움
                val sib = Reminders.siblings(ctx, id)
                if (sib.size > 1 && sib.first().optString("id") != id) {
                    if (nag == 0) Reminders.scheduleAll(ctx)
                    MissionWidget.updateAll(ctx)
                    return
                }
                if (!Reminders.isSettled(ctx, id)) {
                    Reminders.showMission(ctx, id, nag)
                    if (nag < Reminders.MAX_NAGS) Reminders.scheduleNag(ctx, id, nag + 1)
                }
                // 내일 같은 시간 알림 예약
                if (nag == 0) Reminders.scheduleAll(ctx)
                MissionWidget.updateAll(ctx)
            }
            Reminders.ACTION_DONE -> {
                val lvl = intent.getIntExtra(Reminders.EXTRA_LVL, 1)
                for (one in groupIds(id)) {
                    Reminders.addPending(ctx, one, lvl)
                    Reminders.cancelNag(ctx, one)
                    Reminders.dismiss(ctx, one)
                }
                Reminders.refreshBlockNotifications(ctx)
                MissionWidget.updateAll(ctx)
            }
            Reminders.ACTION_SKIP -> {
                for (one in groupIds(id)) {
                    Reminders.addPending(ctx, one, 0)
                    Reminders.cancelNag(ctx, one)
                    Reminders.dismiss(ctx, one)
                }
                Reminders.refreshBlockNotifications(ctx)
                MissionWidget.updateAll(ctx)
            }
            Reminders.ACTION_SNOOZE -> {
                // 직접 미룬 건 횟수에 세지 않고 10분 뒤 다시 알려 줘요
                Reminders.dismiss(ctx, id)
                Reminders.scheduleNag(ctx, id, 1)
                MissionWidget.updateAll(ctx)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.ensureChannel(ctx)
        Reminders.scheduleAll(ctx)
        MissionWidget.updateAll(ctx)
    }
}
