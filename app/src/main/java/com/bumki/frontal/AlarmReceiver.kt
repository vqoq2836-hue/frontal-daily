package com.bumki.frontal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        when (intent.action) {
            Reminders.ACTION_ALARM -> {
                val nag = intent.getIntExtra(Reminders.EXTRA_NAG, 0)
                if (!Reminders.isSettled(ctx, id)) {
                    Reminders.showMission(ctx, id, nag)
                    if (nag < Reminders.MAX_NAGS) Reminders.scheduleNag(ctx, id, nag + 1)
                }
                // 내일 같은 시간 알림 예약
                if (nag == 0) Reminders.scheduleAll(ctx)
            }
            Reminders.ACTION_DONE -> {
                val lvl = intent.getIntExtra(Reminders.EXTRA_LVL, 1)
                Reminders.addPending(ctx, id, lvl)
                Reminders.cancelNag(ctx, id)
                Reminders.dismiss(ctx, id)
            }
            Reminders.ACTION_SNOOZE -> {
                val nag = intent.getIntExtra(Reminders.EXTRA_LVL, 0)
                Reminders.dismiss(ctx, id)
                Reminders.scheduleNag(ctx, id, (nag + 1).coerceAtMost(Reminders.MAX_NAGS))
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.ensureChannel(ctx)
        Reminders.scheduleAll(ctx)
    }
}
